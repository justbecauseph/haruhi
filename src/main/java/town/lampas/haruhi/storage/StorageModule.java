package town.lampas.haruhi.storage;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * Owned by the storage component. Reads {@code config/haruhi.json}, builds the configured {@link HaruhiStorage}
 * (in-memory or PostgreSQL), runs migrations, registers it with {@link HaruhiServices#setStorage} and closes it when
 * the server stops.
 *
 * <p>{@link #init()} builds the storage and registers it, so later modules can read it. The migrations run on
 * {@code SERVER_STARTING}. This module registers that listener first, so they finish before content or dialogue reads
 * the database. A singleplayer session can start the server again in the same JVM. The storage closes on
 * {@code SERVER_STOPPED}, and the next start builds a fresh one.
 */
public final class StorageModule {
    private static final String CONFIG_FILE = "haruhi.json";

    private static StorageConfig config;
    private static HaruhiStorage current;

    private StorageModule() {}

    public static void init() {
        config = StorageConfig.loadOrCreate(FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE));
        open();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> start());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> stop());
    }

    private static void open() {
        current = build(config);
        HaruhiServices.setStorage(current);
    }

    private static void start() {
        if (current == null) {
            open();
        }
        if (current instanceof PostgresStorage postgres) {
            postgres.migrate();
        }
    }

    private static void stop() {
        HaruhiStorage stopping = current;
        current = null;
        if (stopping != null) {
            stopping.close();
        }
    }

    private static HaruhiStorage build(StorageConfig settings) {
        return switch (settings.backend()) {
            case MEMORY -> {
                Haruhi.LOGGER.info("Haruhi storage: in-memory backend. Flags, visits and content revisions are lost "
                        + "when the server stops.");
                yield new InMemoryStorage();
            }
            case POSTGRES -> {
                Haruhi.LOGGER.info("Haruhi storage: PostgreSQL backend, schema {}", settings.schema());
                yield new PostgresStorage(settings.jdbcUrl(), settings.username(),
                        password(settings.passwordEnv()), settings.schema(), settings.poolSize());
            }
        };
    }

    private static String password(String variable) {
        String value = System.getenv(variable);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "Haruhi storage is set to PostgreSQL, but the environment variable " + variable + " is not set");
        }
        return value;
    }
}
