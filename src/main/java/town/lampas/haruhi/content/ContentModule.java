package town.lampas.haruhi.content;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * Owned by the content component. Parses and validates bundles, stores them as revisions through
 * {@link town.lampas.haruhi.api.HaruhiServices#storage()}, activates one, registers the {@link ContentService} with
 * {@link town.lampas.haruhi.api.HaruhiServices#setContent} and registers the {@code /haruhi content} commands.
 */
public final class ContentModule {
    private ContentModule() {}

    public static void init() {
        ContentServiceImpl service = new ContentServiceImpl();
        HaruhiServices.setContent(service);
        ServerLifecycleEvents.SERVER_STARTING.register(service::attach);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ContentStartup.run(server, service));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> service.detach());
        ContentCommands.register(service);
    }
}
