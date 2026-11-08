package town.lampas.haruhi.content;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.remote.ContentMethods;
import town.lampas.haruhi.content.remote.ContentRpc;
import town.lampas.haruhi.content.remote.RemoteContentConfig;

/**
 * Owned by the content component. Parses and validates bundles, stores them as revisions through
 * {@link town.lampas.haruhi.api.HaruhiServices#storage()}, activates one, registers the {@link ContentService} with
 * {@link town.lampas.haruhi.api.HaruhiServices#setContent}, registers the {@code /haruhi content} commands and the
 * {@code haruhi:content/*} management methods, and reads {@code config/haruhi-content.json}.
 */
public final class ContentModule {
    private static volatile @Nullable ContentMethods methods;

    private ContentModule() {}

    public static void init() {
        ContentServiceImpl service = new ContentServiceImpl();
        HaruhiServices.setContent(service);
        RemoteContentConfig remote = remoteConfig();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            service.attach(server);
            methods = ContentMethods.create(service, remote, server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ContentStartup.run(service));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ContentMethods stopping = methods;
            methods = null;
            if (stopping != null) {
                stopping.close();
            }
            service.detach();
        });
        ContentCommands.register(service);
        ContentRpc.register(() -> methods);
    }

    /**
     * The remote content configuration. A file that cannot be read or does not validate is logged, and the server runs
     * with remote content disabled rather than not starting.
     */
    private static RemoteContentConfig remoteConfig() {
        try {
            RemoteContentConfig config = RemoteContentConfig.loadOrCreate(
                    FabricLoader.getInstance().getConfigDir().resolve(RemoteContentConfig.FILE));
            if (config.enabled()) {
                Haruhi.LOGGER.info("Haruhi remote content: fetching bundles from {}, at most {} bytes",
                        config.origin().orElseThrow(), config.maxBytes());
            }
            return config;
        } catch (IllegalStateException e) {
            Haruhi.LOGGER.error("{}; remote content is disabled", e.getMessage());
            return RemoteContentConfig.DISABLED;
        }
    }
}
