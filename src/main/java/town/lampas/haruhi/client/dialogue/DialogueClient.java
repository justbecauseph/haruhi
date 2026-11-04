package town.lampas.haruhi.client.dialogue;

import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import town.lampas.haruhi.client.npc.NpcClient;
import town.lampas.haruhi.network.DialogueClosePayload;
import town.lampas.haruhi.network.DialogueNodePayload;

/**
 * Owned by the client component. Receives {@link DialogueNodePayload} and opens or updates the {@link DialogueScreen};
 * receives {@link DialogueClosePayload} and closes the screen when its session matches. A node that names an animation
 * and a speaker plays that animation on the speaker. Text speed comes from {@code config/haruhi-client.json}.
 */
public final class DialogueClient {
    private static volatile double textSpeed = DialogueClientConfig.DEFAULT_TEXT_SPEED;

    private DialogueClient() {}

    public static void init() {
        textSpeed = DialogueClientConfig.load(
                FabricLoader.getInstance().getConfigDir().resolve(DialogueClientConfig.FILE_NAME));
        ClientPlayNetworking.registerGlobalReceiver(DialogueNodePayload.TYPE,
                (payload, context) -> context.client().execute(() -> showNode(payload)));
        ClientPlayNetworking.registerGlobalReceiver(DialogueClosePayload.TYPE,
                (payload, context) -> context.client().execute(() -> close(payload.sessionId())));
    }

    /** The entity the open dialogue screen names as its speaker, or empty. Client thread only. */
    public static OptionalInt speakerEntityId() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return screen instanceof DialogueScreen dialogue ? dialogue.speakerEntityId() : OptionalInt.empty();
    }

    /** Whether the open dialogue screen names entity {@code entityId} as its speaker. Client thread only. */
    public static boolean isSpeaking(int entityId) {
        OptionalInt speaker = speakerEntityId();
        return speaker.isPresent() && speaker.getAsInt() == entityId;
    }

    private static void showNode(DialogueNodePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof DialogueScreen dialogue
                && DialogueSession.matches(dialogue.sessionId(), payload.sessionId())) {
            dialogue.showNode(payload);
        } else {
            minecraft.gui.setScreen(new DialogueScreen(payload, textSpeed));
        }
        if (payload.animation().isPresent() && payload.speakerEntityId().isPresent()) {
            NpcClient.playAnimation(payload.speakerEntityId().getAsInt(), payload.animation().get());
        }
    }

    private static void close(int sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof DialogueScreen dialogue
                && DialogueSession.matches(dialogue.sessionId(), sessionId)) {
            minecraft.gui.setScreen(null);
        }
    }
}
