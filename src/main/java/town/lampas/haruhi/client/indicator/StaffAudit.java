package town.lampas.haruhi.client.indicator;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import town.lampas.haruhi.Haruhi;

/** Client state and optional keybind for the gamemaster-only staff audit overlay ({@code /haruhi audit}). */
final class StaffAudit {
    private static @Nullable KeyMapping auditKey;
    private static volatile boolean enabled;

    private StaffAudit() {}

    static void init() {
        auditKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.haruhi.audit",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                KeyMapping.Category.register(Haruhi.id("haruhi"))));
        ClientTickEvents.END_CLIENT_TICK.register(StaffAudit::onEndTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> enabled = false);
    }

    static boolean isActive(@Nullable LocalPlayer player) {
        return enabled && hasPermission(player);
    }

    static void handleServerToggle(Optional<Boolean> requested) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (!hasPermission(player)) {
            enabled = false;
            return;
        }
        enabled = requested.orElse(!enabled);
        sendStatus(player);
    }

    private static void onEndTick(Minecraft minecraft) {
        if (auditKey == null) {
            return;
        }
        LocalPlayer player = minecraft.player;
        while (auditKey.consumeClick()) {
            if (!hasPermission(player)) {
                enabled = false;
                continue;
            }
            enabled = !enabled;
            sendStatus(player);
        }
    }

    private static boolean hasPermission(@Nullable LocalPlayer player) {
        return player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static void sendStatus(LocalPlayer player) {
        player.sendOverlayMessage(Component.translatable(
                enabled ? "message.haruhi.audit.on" : "message.haruhi.audit.off"));
    }
}
