package town.lampas.haruhi.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import town.lampas.haruhi.dialogue.DialogueModule;

/**
 * Routes custom clicks from vanilla dialogs to the dialogue presenter. Vanilla only logs a custom click, and Fabric API has
 * no event for it. The injection runs before vanilla's own thread check, so it returns early off the server thread, and
 * vanilla then re-queues the packet onto the server thread, where this runs again.
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class DialogueClickMixin {

    @Inject(method = "handleCustomClickAction", at = @At("HEAD"), cancellable = true)
    private void haruhi$handleDialogueClick(ServerboundCustomClickActionPacket packet, CallbackInfo callback) {
        if (!((Object) this instanceof ServerGamePacketListenerImpl listener)) {
            return;
        }
        ServerPlayer player = listener.getPlayer();
        if (!player.level().getServer().isSameThread()) {
            return;
        }
        if (DialogueModule.handleCustomClick(player, packet.id(), packet.payload())) {
            callback.cancel();
        }
    }
}
