package town.lampas.haruhi.client.binding;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import town.lampas.haruhi.binding.BoundBlockHints;
import town.lampas.haruhi.network.BoundBlocksPayload;

/** Client receiver and disconnect cleanup for {@link BoundBlockHints}. */
public final class BindingClient {
    private BindingClient() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(BoundBlocksPayload.TYPE,
                (payload, context) -> context.client().execute(() -> BoundBlockHints.apply(payload)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BoundBlockHints.clear());
    }
}
