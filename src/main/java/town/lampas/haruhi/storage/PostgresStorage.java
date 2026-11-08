package town.lampas.haruhi.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.resources.Identifier;

/**
 * Stores Haruhi state in PostgreSQL through a HikariCP pool. Every call runs on a dedicated executor, never on the
 * server thread. The executor has as many threads as the pool has connections, so a task that starts work always gets a
 * connection. The pool's default schema is the configured schema, so the migrations and every query use unqualified
 * table names. Call {@link #migrate()} before first use.
 */
final class PostgresStorage implements HaruhiStorage {
    /**
     * How long one read from the server may wait before the connection fails. A server that stops answering otherwise
     * holds its thread and connection forever. A {@code socketTimeout} in the {@code jdbc_url} replaces this, and the
     * server limits follow the value that the URL gives. See {@link PostgresConnectionSettings}.
     */
    private static final int SOCKET_TIMEOUT_SECONDS = 10;
    private static final String LOCK_ACTIVATIONS = "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String REVISION_COLUMNS =
            "id, digest, bundle, author, created_at, activated_at, activated_by, active";
    private static final String SELECT_REVISION_BY_ID =
            "SELECT " + REVISION_COLUMNS + " FROM content_revision WHERE id = ?";
    private static final String SELECT_REVISION_BY_DIGEST =
            "SELECT " + REVISION_COLUMNS + " FROM content_revision WHERE digest = ?";
    private static final String SELECT_ACTIVE_REVISION =
            "SELECT " + REVISION_COLUMNS + " FROM content_revision WHERE active";
    private static final String UPSERT_FLAG = """
            INSERT INTO flags (character_id, namespace, flag, value, updated_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (character_id, namespace, flag)
            DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
            """;
    private static final String DELETE_FLAG = "DELETE FROM flags WHERE character_id = ? AND namespace = ? AND flag = ?";
    private static final String RECORD_VISIT = """
            INSERT INTO visits (character_id, graph, npc, count, last_visit) VALUES (?, ?, ?, 1, ?)
            ON CONFLICT (character_id, graph, npc)
            DO UPDATE SET count = visits.count + 1, last_visit = EXCLUDED.last_visit
            RETURNING count, last_visit
            """;
    private static final String INSERT_REVISION = """
            INSERT INTO content_revision (digest, bundle, author, created_at, active) VALUES (?, ?, ?, ?, false)
            ON CONFLICT (digest) DO NOTHING
            """;
    private static final String DEACTIVATE_OTHERS =
            "UPDATE content_revision SET active = false WHERE active AND id <> ?";
    private static final String ACTIVATE =
            "UPDATE content_revision SET active = true, activated_at = ?, activated_by = ? WHERE id = ?";
    private static final String LIST_REVISIONS = """
            SELECT id, digest, author, created_at, activated_at, activated_by, active FROM content_revision
            WHERE id < ? ORDER BY id DESC LIMIT ?
            """;

    private final String schema;
    private final HikariDataSource dataSource;
    private final StorageExecutor executor;

    PostgresStorage(String jdbcUrl, String username, String password, String schema, int poolSize) {
        if (!StorageSupport.isSchemaName(schema)) {
            throw new IllegalArgumentException("Schema name " + schema + " is not a lowercase identifier");
        }
        PostgresConnectionSettings connection = PostgresConnectionSettings.of(jdbcUrl, SOCKET_TIMEOUT_SECONDS);
        this.schema = schema;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connection.jdbcUrl());
        config.setUsername(username);
        config.setPassword(password);
        config.setSchema(schema);
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("haruhi-storage");
        // Driver defaults. The PostgreSQL driver gives a URL parameter of the same name priority over these.
        config.addDataSourceProperty("socketTimeout", String.valueOf(SOCKET_TIMEOUT_SECONDS));
        config.addDataSourceProperty("tcpKeepAlive", "true");
        // Startup parameters of every connection: a lock wait or a long statement fails on the server before
        // socketTimeout fires, while the server answers. When the URL has options of its own, connection.jdbcUrl()
        // carries them too.
        config.addDataSourceProperty("options", connection.options());
        this.dataSource = new HikariDataSource(config);
        this.executor = new StorageExecutor(poolSize, "haruhi-storage-", StorageSupport.SHUTDOWN_GRACE);
    }

    /**
     * Applies the migrations the database has not seen yet. Blocks until they finish, so call it when the server
     * starts, never on a tick. A failure stops the server start, because the schema is then not usable.
     */
    void migrate() {
        try {
            PostgresMigrations.apply(dataSource, schema);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Haruhi storage could not migrate schema " + schema + ": " + e.getMessage(), e);
        }
    }

    /**
     * The socket read timeout and the server limits of one pooled connection, read through the storage's own executor
     * and pool. For tests: the socket read timeout in milliseconds, then the server's lock_timeout and statement_timeout
     * as the server shows them.
     */
    record SessionLimits(int networkTimeoutMillis, String lockTimeout, String statementTimeout) {}

    CompletableFuture<SessionLimits> sessionLimits() {
        return query(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet row = statement.executeQuery(
                            "SELECT current_setting('lock_timeout'), current_setting('statement_timeout')")) {
                row.next();
                return new SessionLimits(connection.getNetworkTimeout(), row.getString(1), row.getString(2));
            }
        });
    }

    @Override
    public CompletableFuture<Map<FlagKey, Integer>> loadFlags(UUID characterId) {
        return query(connection -> {
            Map<FlagKey, Integer> loaded = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT namespace, flag, value FROM flags WHERE character_id = ?")) {
                statement.setObject(1, characterId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        loaded.put(new FlagKey(rows.getString(1), rows.getString(2)), rows.getInt(3));
                    }
                }
            }
            return Map.copyOf(loaded);
        });
    }

    @Override
    public CompletableFuture<Void> writeFlags(UUID characterId, List<FlagChange> changes) {
        List<FlagChange> ordered = List.copyOf(changes);
        OffsetDateTime updatedAt = utc(StorageSupport.micros(Instant.now()));
        return inTransaction(connection -> {
            try (PreparedStatement upsert = connection.prepareStatement(UPSERT_FLAG);
                    PreparedStatement delete = connection.prepareStatement(DELETE_FLAG)) {
                for (FlagChange change : ordered) {
                    FlagKey key = change.key();
                    StorageSupport.requireStorable(key);
                    if (change.value().isPresent()) {
                        upsert.setObject(1, characterId);
                        upsert.setString(2, key.namespace());
                        upsert.setString(3, key.flag());
                        upsert.setInt(4, change.value().getAsInt());
                        upsert.setObject(5, updatedAt);
                        upsert.executeUpdate();
                    } else {
                        delete.setObject(1, characterId);
                        delete.setString(2, key.namespace());
                        delete.setString(3, key.flag());
                        delete.executeUpdate();
                    }
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Map<VisitKey, VisitRecord>> loadVisits(UUID characterId) {
        return query(connection -> {
            Map<VisitKey, VisitRecord> loaded = new HashMap<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT graph, npc, count, last_visit FROM visits WHERE character_id = ?")) {
                statement.setObject(1, characterId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        VisitKey key = new VisitKey(
                                Identifier.parse(rows.getString(1)), Identifier.parse(rows.getString(2)));
                        loaded.put(key, new VisitRecord(rows.getInt(3), instant(rows, 4)));
                    }
                }
            }
            return Map.copyOf(loaded);
        });
    }

    @Override
    public CompletableFuture<VisitRecord> recordVisit(UUID characterId, VisitKey key, Instant at) {
        OffsetDateTime visitedAt = utc(StorageSupport.micros(at));
        return query(connection -> {
            // One statement: the insert or the increment, and the row it leaves, are atomic.
            try (PreparedStatement statement = connection.prepareStatement(RECORD_VISIT)) {
                statement.setObject(1, characterId);
                statement.setString(2, key.graph().toString());
                statement.setString(3, key.npc().toString());
                statement.setObject(4, visitedAt);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    return new VisitRecord(row.getInt(1), instant(row, 2));
                }
            }
        });
    }

    @Override
    public CompletableFuture<StoredRevision> storeRevision(
            String digest, String bundleJson, String author, Instant at) {
        OffsetDateTime createdAt = utc(StorageSupport.micros(at));
        return inTransaction(connection -> {
            try (PreparedStatement insert = connection.prepareStatement(INSERT_REVISION)) {
                insert.setString(1, digest);
                insert.setString(2, bundleJson);
                insert.setString(3, author);
                insert.setObject(4, createdAt);
                insert.executeUpdate();
            }
            // Inserted now, or inserted earlier by a committed transaction, so the row exists either way.
            try (PreparedStatement select = connection.prepareStatement(SELECT_REVISION_BY_DIGEST)) {
                select.setString(1, digest);
                return firstRevision(select).orElseThrow(
                        () -> new SQLException("Content revision " + digest + " is missing after its insert"));
            }
        });
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> loadRevision(long revisionId) {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_REVISION_BY_ID)) {
                statement.setLong(1, revisionId);
                return firstRevision(statement);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<StoredRevision>> activeRevision() {
        return query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SELECT_ACTIVE_REVISION)) {
                return firstRevision(statement);
            }
        });
    }

    @Override
    public CompletableFuture<Void> activateRevision(long revisionId, String actor, Instant at) {
        OffsetDateTime activatedAt = utc(StorageSupport.micros(at));
        return inTransaction(connection -> {
            // Activations of one schema run one at a time. Under READ COMMITTED a statement sees only the rows
            // committed before it began, so an activation that began while another was open would not see the other's
            // new active row, and the unique index would reject it. The lock makes each activation begin after the
            // previous one commits.
            try (PreparedStatement lock = connection.prepareStatement(LOCK_ACTIVATIONS)) {
                lock.setString(1, "haruhi.activate." + schema);
                lock.execute();
            }
            // The partial unique index allows one active row, so the others are cleared before the target is set.
            // If the target does not exist, the rollback restores the previously active revision.
            try (PreparedStatement deactivate = connection.prepareStatement(DEACTIVATE_OTHERS);
                    PreparedStatement activate = connection.prepareStatement(ACTIVATE)) {
                deactivate.setLong(1, revisionId);
                deactivate.executeUpdate();
                activate.setObject(1, activatedAt);
                activate.setString(2, actor);
                activate.setLong(3, revisionId);
                if (activate.executeUpdate() != 1) {
                    throw new IllegalArgumentException("No content revision " + revisionId);
                }
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<List<RevisionSummary>> listRevisions(OptionalLong before, int limit) {
        if (limit < 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("limit must be positive, was " + limit));
        }
        long below = before.orElse(Long.MAX_VALUE);
        return query(connection -> {
            List<RevisionSummary> listing = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(LIST_REVISIONS)) {
                statement.setLong(1, below);
                statement.setInt(2, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        listing.add(new RevisionSummary(rows.getLong(1), rows.getString(2), rows.getString(3),
                                instant(rows, 4), activatedAt(rows, 5), Optional.ofNullable(rows.getString(6)),
                                rows.getBoolean(7)));
                    }
                }
            }
            return List.copyOf(listing);
        });
    }

    @Override
    public String backendName() {
        return "postgresql";
    }

    @Override
    public void close() {
        executor.close();
        dataSource.close();
    }

    /** A unit of JDBC work. Its result becomes the value of the returned future. */
    private interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Runs the work on the executor with a pooled connection in autocommit mode. */
    private <T> CompletableFuture<T> query(Work<T> work) {
        return executor.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Runs the work on the executor in one transaction: committed when it returns, rolled back when it throws. */
    private <T> CompletableFuture<T> inTransaction(Work<T> work) {
        return executor.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                }
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static Optional<StoredRevision> firstRevision(PreparedStatement statement) throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            if (!rows.next()) {
                return Optional.empty();
            }
            return Optional.of(new StoredRevision(
                    rows.getLong(1),
                    rows.getString(2),
                    rows.getString(3),
                    rows.getString(4),
                    instant(rows, 5),
                    activatedAt(rows, 6),
                    Optional.ofNullable(rows.getString(7)),
                    rows.getBoolean(8)));
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Optional<Instant> activatedAt(ResultSet row, int column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }
}
