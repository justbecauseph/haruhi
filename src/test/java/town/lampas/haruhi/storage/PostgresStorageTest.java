package town.lampas.haruhi.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the storage contract against {@link PostgresStorage} on a PostgreSQL container, plus checks that only the
 * database can show: migrations, the schema they land in, privileges, the unique index, locking between sessions, and
 * a write or read that waits on a lock. Skipped when Docker is not running.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresStorageTest extends HaruhiStorageContractTest {
    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-04T10:01:00Z");
    private static final Instant T2 = Instant.parse("2026-11-04T10:02:00Z");
    private static final FlagKey MET = new FlagKey("haruhi:example/greeter", "met");
    private static final UUID ALICE = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    private static final VisitKey GREETER = new VisitKey(
            Identifier.parse("haruhi:example/greeter"), Identifier.parse("haruhi:example/greeter_npc"));
    private static final String BUNDLE = "{\"schema\":1}";
    private static final String ROLE_PASSWORD = "secret";
    /** Longer than any socket timeout the storage sets, so a read that gets an answer finishes first. */
    private static final long NO_ANSWER_LIMIT_SECONDS = 30;
    private static final long LOCK_WAIT_LIMIT_SECONDS = 10;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("haruhi_test")
            .withUsername("haruhi")
            .withPassword("haruhi");

    @Override
    protected HaruhiStorage createStorage() {
        PostgresStorage storage = open(newSchema());
        storage.migrate();
        return storage;
    }

    @Test
    void migrationsCreateTheTablesInTheConfiguredSchemaAndRunOnce() throws SQLException {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            storage.migrate();
        }

        try (Connection connection = connect()) {
            assertEquals(List.of("content_revision", "flags", "schema_version", "visits"),
                    column(connection, "SELECT table_name FROM information_schema.tables "
                            + "WHERE table_schema = ? ORDER BY table_name", schema));
            assertEquals(List.of("1"),
                    column(connection, "SELECT version FROM " + schema + ".schema_version ORDER BY version"));
        }
    }

    @Test
    void aRoleThatOwnsTheSchemaCanMigrateItWithoutCreateOnTheDatabase() throws SQLException {
        String schema = newSchema();
        String role = newRole();
        try (Connection admin = connect()) {
            execute(admin, "CREATE ROLE " + role + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            execute(admin, "CREATE SCHEMA " + schema + " AUTHORIZATION " + role);
            assertFalse(holds(admin, "SELECT has_database_privilege(?, current_database(), 'CREATE')", role));
        }

        try (PostgresStorage first = open(schema, role, ROLE_PASSWORD)) {
            first.migrate();
        }
        try (PostgresStorage second = open(schema, role, ROLE_PASSWORD)) {
            second.migrate();
        }

        try (Connection connection = connect()) {
            assertEquals(List.of("1"),
                    column(connection, "SELECT version FROM " + schema + ".schema_version ORDER BY version"));
        }
    }

    @Test
    void aRoleWithNoCreateRightsRestartsAgainstAMigratedSchema() throws SQLException {
        String schema = newSchema();
        String role = newRole();
        try (PostgresStorage owner = open(schema)) {
            owner.migrate();
        }
        try (Connection admin = connect()) {
            execute(admin, "CREATE ROLE " + role + " LOGIN PASSWORD '" + ROLE_PASSWORD + "'");
            execute(admin, "GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            execute(admin, "GRANT SELECT ON " + schema + ".schema_version TO " + role);
            assertFalse(holds(admin, "SELECT has_schema_privilege(?, ?, 'CREATE')", role, schema));
            assertFalse(holds(admin, "SELECT has_database_privilege(?, current_database(), 'CREATE')", role));
        }

        try (PostgresStorage restart = open(schema, role, ROLE_PASSWORD)) {
            restart.migrate();
        }
    }

    @Test
    void stateWrittenBeforeARestartIsReadAfterIt() {
        String schema = newSchema();
        UUID character = UUID.randomUUID();
        try (PostgresStorage first = open(schema)) {
            first.migrate();
            await(first.writeFlags(character, List.of(FlagChange.set(MET, 2))));
            StoredRevision stored = await(first.storeRevision("digest-a", BUNDLE, "alice", T0));
            await(first.activateRevision(stored.id(), "operator", T1));
        }

        try (PostgresStorage second = open(schema)) {
            second.migrate();
            assertEquals(Map.of(MET, 2), await(second.loadFlags(character)));
            StoredRevision active = await(second.activeRevision()).orElseThrow();
            assertEquals("digest-a", active.digest());
            assertEquals(Optional.of(T1), active.activatedAt());
        }
    }

    @Test
    void theDatabaseItselfRefusesASecondActiveRevision() throws SQLException {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            StoredRevision first = await(storage.storeRevision("digest-a", BUNDLE, "alice", T0));
            StoredRevision second = await(storage.storeRevision("digest-b", BUNDLE, "alice", T0));
            await(storage.activateRevision(first.id(), "operator", T1));

            try (Connection connection = connect()) {
                SQLException refused = assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE " + schema + ".content_revision SET active = true WHERE id = " + second.id()));
                assertEquals("23505", refused.getSQLState());
            }
        }
    }

    @Test
    void twoServersActivatingDifferentRevisionsAtOnceBothSucceedAndLeaveOneActive() throws Exception {
        String schema = newSchema();
        try (PostgresStorage setup = open(schema);
                PostgresStorage serverA = openWithOneConnection(schema);
                PostgresStorage serverB = openWithOneConnection(schema)) {
            setup.migrate();
            StoredRevision previous = await(setup.storeRevision("digest-a", BUNDLE, "alice", T0));
            StoredRevision one = await(setup.storeRevision("digest-b", BUNDLE, "alice", T0));
            StoredRevision two = await(setup.storeRevision("digest-c", BUNDLE, "alice", T0));
            await(setup.activateRevision(previous.id(), "operator", T0));

            try (Connection blocker = connect()) {
                blocker.setAutoCommit(false);
                // Holds the active row, so both activations reach it and wait for it.
                execute(blocker, "SELECT id FROM " + schema + ".content_revision WHERE id = " + previous.id()
                        + " FOR UPDATE");
                CompletableFuture<Void> first = serverA.activateRevision(one.id(), "operator", T1);
                CompletableFuture<Void> second = serverB.activateRevision(two.id(), "operator", T2);
                awaitBlockedOnLocks(blocker, 2);
                blocker.commit();

                await(first);
                await(second);
            }

            List<RevisionSummary> listing = await(setup.listRevisions(10));
            List<Long> active = listing.stream().filter(RevisionSummary::active).map(RevisionSummary::id).toList();
            assertEquals(1, active.size());
            assertTrue(active.get(0) == one.id() || active.get(0) == two.id(),
                    "the active revision is " + active.get(0) + ", not one of the two activated");
        }
    }

    /** A read that waits on a lock fails on the server's lock_timeout (55P03), not on the client's socket timeout. */
    @Test
    void aReadThatWaitsOnALockFailsOnTheServersLockTimeout() throws SQLException {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            try (Connection blocker = connect()) {
                blocker.setAutoCommit(false);
                // The read waits behind this lock. The server's lock_timeout fails it, so the caller gets SQLState
                // 55P03 and not a socket timeout.
                execute(blocker, "LOCK TABLE " + schema + ".flags IN ACCESS EXCLUSIVE MODE");
                CompletableFuture<Map<FlagKey, Integer>> read = storage.loadFlags(UUID.randomUUID());

                assertFailsOnTheServerLockTimeout(read);
                blocker.rollback();
            }
        }
    }

    /**
     * An autocommit write that waits on a lock fails on the server's lock timeout, so it is rolled back. A write the
     * client has given up on would stay queued and count the visit once the lock is released, and a retry would count it
     * twice.
     */
    @Test
    void anAutocommitVisitThatWaitsOnALockIsNotCountedWhenTheLockIsReleased() throws Exception {
        String schema = newSchema();
        try (PostgresStorage storage = open(schema)) {
            storage.migrate();
            assertBlockedVisitIsNotCounted(schema, storage);
        }
    }

    /**
     * A socketTimeout in the jdbc_url replaces the default, and the server limits follow it. At 5 seconds the lock limit
     * is 2, so the write still fails on the server before the socket read fails.
     */
    @Test
    void aSocketTimeoutInTheUrlStillFailsALockWaitOnTheServer() throws Exception {
        String schema = newSchema();
        try (PostgresStorage storage = openWithUrl(schema, "socketTimeout=5")) {
            storage.migrate();
            assertBlockedVisitIsNotCounted(schema, storage);
        }
    }

    /**
     * The URL's own options come before the server limits, so a lock_timeout of 0 in the URL cannot switch the lock limit
     * off. Without that, the read would wait until the test's own limit ends it.
     */
    @Test
    void theServerLimitsWinOverALockTimeoutInTheUrlsOptions() throws Exception {
        String schema = newSchema();
        String options = "-c lock_timeout=0";
        try (PostgresStorage storage = openWithUrl(schema,
                "options=" + URLEncoder.encode(options, StandardCharsets.UTF_8))) {
            storage.migrate();
            try (Connection blocker = connect()) {
                blocker.setAutoCommit(false);
                execute(blocker, "LOCK TABLE " + schema + ".flags IN ACCESS EXCLUSIVE MODE");
                CompletableFuture<Map<FlagKey, Integer>> read = storage.loadFlags(UUID.randomUUID());

                assertFailsOnTheServerLockTimeout(read);
                blocker.rollback();
            }
        }
    }

    /**
     * With the default URL a pooled connection reads with a 10-second socket timeout. Its lock_timeout is half the socket
     * timeout (5 seconds), and its statement_timeout is two seconds less (8 seconds).
     */
    @Test
    void aPooledConnectionCarriesTheDefaultSocketTimeoutAndItsServerLimits() {
        try (PostgresStorage storage = open(newSchema())) {
            assertEquals(new PostgresStorage.SessionLimits(10_000, "5s", "8s"), await(storage.sessionLimits()));
        }
    }

    /**
     * A socketTimeout of 6 seconds in the URL sets the socket read timeout to 6 seconds, the lock_timeout to 3 seconds
     * (half), and the statement_timeout to 4 seconds (two less).
     */
    @Test
    void aPooledConnectionCarriesTheSocketTimeoutFromTheUrlAndItsServerLimits() {
        try (PostgresStorage storage = openWithUrl(newSchema(), "socketTimeout=6")) {
            assertEquals(new PostgresStorage.SessionLimits(6_000, "3s", "4s"), await(storage.sessionLimits()));
        }
    }

    /** A blank options parameter in the URL keeps the lock limit, so a lock wait still fails on the server. */
    @Test
    void aLockWaitStillFailsOnTheServerWhenTheUrlHasABlankOptionsParameter() throws Exception {
        String schema = newSchema();
        try (PostgresStorage storage = openWithUrl(schema, "options=")) {
            storage.migrate();
            assertBlockedVisitIsNotCounted(schema, storage);
        }
    }

    /**
     * Holds a lock on the visits table and records a visit that must wait on it. The visit must fail on the server's lock
     * timeout. Once the lock is released and the server session has finished, the visit must not be counted.
     */
    private static void assertBlockedVisitIsNotCounted(String schema, PostgresStorage storage) throws Exception {
        await(storage.recordVisit(ALICE, GREETER, T0));
        try (Connection blocker = connect()) {
            blocker.setAutoCommit(false);
            execute(blocker, "LOCK TABLE " + schema + ".visits IN ACCESS EXCLUSIVE MODE");
            CompletableFuture<VisitRecord> visit = storage.recordVisit(ALICE, GREETER, T1);

            assertFailsOnTheServerLockTimeout(visit);
            blocker.rollback();
            awaitNoRunningStatementOn(blocker, "visits");
        }

        assertEquals(Map.of(GREETER, new VisitRecord(1, T0)), await(storage.loadVisits(ALICE)));
    }

    /**
     * The statement must fail with the server's lock timeout, SQLState 55P03. A 08006 means the client's socket read gave
     * up first, and the server may still run the statement once the lock is released.
     */
    private static void assertFailsOnTheServerLockTimeout(CompletableFuture<?> pending) {
        CompletionException failure = assertThrows(CompletionException.class,
                () -> pending.orTimeout(NO_ANSWER_LIMIT_SECONDS, TimeUnit.SECONDS).join());

        assertFalse(failure.getCause() instanceof TimeoutException,
                "the statement was still waiting after " + NO_ANSWER_LIMIT_SECONDS + " seconds");
        assertEquals("55P03", assertInstanceOf(SQLException.class, failure.getCause()).getSQLState());
    }

    /** A storage whose URL is the container's with one more parameter, as an operator's jdbc_url would carry it. */
    private static PostgresStorage openWithUrl(String schema, String parameter) {
        String base = POSTGRES.getJdbcUrl();
        String url = base + (base.contains("?") ? "&" : "?") + parameter;
        return new PostgresStorage(url, POSTGRES.getUsername(), POSTGRES.getPassword(), schema, 2);
    }

    private static PostgresStorage open(String schema) {
        return open(schema, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static PostgresStorage open(String schema, String username, String password) {
        return new PostgresStorage(POSTGRES.getJdbcUrl(), username, password, schema, 2);
    }

    /** A storage whose one connection exists as soon as it is built, as a separate server's would. */
    private static PostgresStorage openWithOneConnection(String schema) {
        return new PostgresStorage(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), schema, 1);
    }

    private static String newSchema() {
        return "t" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String newRole() {
        return "r" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** The first column of the single row a query returns, as a boolean. */
    private static boolean holds(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    /** The first column of each row, as text. */
    private static List<String> column(Connection connection, String sql, String... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    /** Waits until {@code count} sessions of this database are blocked on a lock, so the test knows they queued. */
    private static void awaitBlockedOnLocks(Connection observer, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_LIMIT_SECONDS);
        int blocked = blockedOnLocks(observer);
        while (blocked < count) {
            if (System.nanoTime() > deadline) {
                fail("only " + blocked + " sessions were blocked on a lock; expected " + count + ". Sessions: "
                        + sessions(observer));
            }
            Thread.sleep(20);
            blocked = blockedOnLocks(observer);
        }
    }

    private static int blockedOnLocks(Connection observer) throws SQLException {
        try (Statement statement = observer.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM pg_stat_activity "
                        + "WHERE datname = current_database() AND wait_event_type = 'Lock'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    /**
     * Waits until no other session of this database is running a statement on the table. A write that was still queued
     * behind a lock has then finished, one way or the other.
     */
    private static void awaitNoRunningStatementOn(Connection observer, String table) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOCK_WAIT_LIMIT_SECONDS);
        while (runningStatementsOn(observer, table) > 0) {
            if (System.nanoTime() > deadline) {
                fail("a statement on " + table + " is still running. Sessions: " + sessions(observer));
            }
            Thread.sleep(20);
        }
    }

    private static int runningStatementsOn(Connection observer, String table) throws SQLException {
        try (PreparedStatement statement = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity "
                + "WHERE datname = current_database() AND pid <> pg_backend_pid() AND state = 'active' "
                + "AND query LIKE ?")) {
            statement.setString(1, "%" + table + "%");
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static String sessions(Connection observer) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement statement = observer.createStatement();
                ResultSet rows = statement.executeQuery("SELECT pid, state, wait_event_type, wait_event, "
                        + "left(query, 60) FROM pg_stat_activity WHERE datname = current_database()")) {
            while (rows.next()) {
                lines.add(rows.getInt(1) + " " + rows.getString(2) + " " + rows.getString(3) + "/"
                        + rows.getString(4) + " " + rows.getString(5));
            }
        }
        return String.join(" | ", lines);
    }
}
