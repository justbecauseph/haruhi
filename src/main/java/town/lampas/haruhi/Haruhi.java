package town.lampas.haruhi;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import town.lampas.haruhi.content.ContentModule;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.network.HaruhiNetworking;
import town.lampas.haruhi.npc.NpcModule;
import town.lampas.haruhi.storage.StorageModule;

/**
 * Common entrypoint. Each module registers its own services in {@link town.lampas.haruhi.api.HaruhiServices}; the order
 * below is the dependency order, so a module may read the services of the modules initialised before it.
 */
public final class Haruhi implements ModInitializer {
    public static final String MOD_ID = "haruhi";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        HaruhiNetworking.registerPayloadTypes();
        StorageModule.init();
        ContentModule.init();
        DialogueModule.init();
        NpcModule.init();
    }
}
