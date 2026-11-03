package town.lampas.haruhi.client;

import net.fabricmc.api.ClientModInitializer;
import town.lampas.haruhi.client.dialogue.DialogueClient;
import town.lampas.haruhi.client.npc.NpcClient;

/** Client entrypoint. Only this package may reference {@code net.minecraft.client} or {@code com.geckolib}. */
public final class HaruhiClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        NpcClient.init();
        DialogueClient.init();
    }
}
