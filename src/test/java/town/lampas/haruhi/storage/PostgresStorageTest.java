package town.lampas.haruhi.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the storage contract against {@link PostgresStorage} on a PostgreSQL container, plus checks that only the
 * database can show: migrations, the schema they land in, and the unique index. Skipped when Docker is not running.
 *
 * <p>This uses {@code @EnabledIf} and starts the container by hand, where the project convention is
 * {@code @Testcontainers(disabledWithoutDocker = true)}. That annotation lives in {@code testcontainers-junit-jupiter},
 * which the build does not yet declare.
 */
@EnabledIf("dockerAvailable")
class PostgresStorageTest extends HaruhiStorageContractTest {
    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-04T10:01:00Z");
    private static final FlagKey MET = new FlagKey("haruhi:example/greeter", "met");
    private static final String BUNDLE = "{\"schema\":1}";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
            .withDatabaseName("haruhi_test")
            .withUsername("haruhi")
            .withPassword("haruhi");

    static boolean dockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    @BeforeAll
    static void startDatabase() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

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

            try (Connection connection = connect();
                    PreparedStatement raw = connection.prepareStatement(
                            "UPDATE " + schema + ".content_revision SET active = true WHERE id = ?")) {
                raw.setLong(1, second.id());
                SQLException refused = assertThrows(SQLException.class, raw::executeUpdate);
                assertEquals("23505", refused.getSQLState());
            }
        }
    }

    private static PostgresStorage open(String schema) {
        return new PostgresStorage(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), schema, 2);
    }

    private static String newSchema() {
        return "t" + UUID.randomUUID().toString().replace("-", "");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
}
