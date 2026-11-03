package town.lampas.haruhi.storage;

/**
 * Owned by the storage component. Reads {@code config/haruhi.json}, builds the configured {@link HaruhiStorage}
 * (in-memory or PostgreSQL), runs migrations, registers it with
 * {@link town.lampas.haruhi.api.HaruhiServices#setStorage} and closes it when the server stops.
 */
public final class StorageModule {
    private StorageModule() {}

    public static void init() {
        // Implemented by the storage component.
    }
}
