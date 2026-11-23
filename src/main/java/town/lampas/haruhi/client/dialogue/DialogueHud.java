package town.lampas.haruhi.client.dialogue;

import java.util.List;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.api.DialogueStyleScreen;

/**
 * The vanilla HUD while a dialogue screen is open: a {@link DialogueScreen}, or any screen that implements
 * {@link DialogueStyleScreen}. The screen draws no background, so without this the HUD draws crisp under the dialogue
 * box: the hotbar below it, and the status bars, experience bar, crosshair and held-item name through it. While the
 * screen is open, the elements in {@link #HIDDEN} are not drawn, and the chat, which stays because {@code haruhi:message}
 * posts dialogue text to it, moves up so its bottom edge sits {@link #CHAT_GAP} pixels above the box, or above the
 * choice list when the list reaches across the chat. A {@link DialogueStyleScreen} has no choice list, so the chat only
 * moves above its box. Every other element (boss bar, titles, subtitles, scoreboard, mob effects, player list, overlay
 * message) draws as it does without a dialogue. The decisions are pure functions, so they are unit tested.
 */
final class DialogueHud {
    /** The vanilla HUD elements not drawn while a dialogue screen is open. */
    static final List<Identifier> HIDDEN = List.of(
            VanillaHudElements.HOTBAR,
            VanillaHudElements.ARMOR_BAR,
            VanillaHudElements.HEALTH_BAR,
            VanillaHudElements.FOOD_BAR,
            VanillaHudElements.AIR_BAR,
            VanillaHudElements.MOUNT_HEALTH,
            VanillaHudElements.INFO_BAR,
            VanillaHudElements.EXPERIENCE_LEVEL,
            VanillaHudElements.HELD_ITEM_TOOLTIP,
            VanillaHudElements.CROSSHAIR);
    /**
     * How far above the bottom of the screen the vanilla chat's bottom edge is, in GUI pixels: {@code ChatComponent}
     * draws its newest line up from {@code floor((guiHeight - 40) / chatScale)} in chat-scaled pixels. Below chat scale 1
     * the rounding lifts the edge by under a pixel more, which only widens the gap.
     */
    static final int CHAT_BOTTOM_RISE = 40;
    /** The space between the moved chat's bottom edge and the dialogue box's top edge, in GUI pixels. */
    static final int CHAT_GAP = 4;
    /**
     * What the chat's background adds to its line width on the right, in GUI pixels at chat scale 1: {@code ChatComponent}
     * translates by 4 and fills from -4 to width + 8.
     */
    static final int CHAT_SIDES = 12;
    /**
     * The height of the queued-messages line vanilla draws under the chat while chat delay holds messages back, in
     * chat-scaled pixels: a fill from the chat's bottom edge to 9 below it.
     */
    static final int QUEUE_STRIP_HEIGHT = 9;

    private DialogueHud() {}

    /** Wraps the hidden elements and the chat. Called once, from {@link DialogueClient#init()}. */
    static void register() {
        for (Identifier element : HIDDEN) {
            HudElementRegistry.replaceElement(element, original -> (graphics, deltaTracker) -> {
                if (!hides(element, openScreen() != null)) {
                    original.extractRenderState(graphics, deltaTracker);
                }
            });
        }
        HudElementRegistry.replaceElement(VanillaHudElements.CHAT, original -> (graphics, deltaTracker) -> {
            Screen screen = openScreen();
            int shift = 0;
            if (screen != null) {
                Minecraft minecraft = Minecraft.getInstance();
                double chatScale = minecraft.options.chatScale().get();
                int chatRight = (int) Math.ceil(ChatComponent.getWidth(minecraft.options.chatWidth().get()) * chatScale)
                        + CHAT_SIDES;
                int queued = minecraft.gui.chatListener().queueSize() > 0
                        ? (int) Math.ceil(QUEUE_STRIP_HEIGHT * chatScale)
                        : 0;
                shift = chatShift(graphics.guiHeight(), chatFloorFor(screen, chatRight), queued);
            }
            if (shift == 0) {
                original.extractRenderState(graphics, deltaTracker);
                return;
            }
            // The chat draws through graphics.pose() relative to what it finds there, so a translation moves all of it.
            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(0.0F, shift);
            try {
                original.extractRenderState(graphics, deltaTracker);
            } finally {
                pose.popMatrix();
            }
        });
    }

    /** Whether the HUD element {@code element} is left out of this frame. */
    static boolean hides(Identifier element, boolean dialogueOpen) {
        return dialogueOpen && HIDDEN.contains(element);
    }

    /**
     * Whether {@code screen} is a dialogue screen for the HUD: a {@link DialogueScreen}, or a
     * {@link DialogueStyleScreen}. Takes an Object, so a unit test can pass a screen without building a Minecraft
     * {@link Screen}, which needs a client.
     */
    static boolean isDialogueScreen(@Nullable Object screen) {
        return screen instanceof DialogueScreen || screen instanceof DialogueStyleScreen;
    }

    /**
     * The lowest GUI y the chat may reach this frame. That is the box's top edge, unless the choice list, which stacks up
     * from the box on the right, reaches left of {@code chatRight}, the chat's right edge: then the chat would sit behind
     * the choice rows, so it goes above the top row instead. Empty without a box.
     */
    static OptionalInt chatFloor(OptionalInt boxTop, OptionalInt choiceLeft, OptionalInt choiceTop, int chatRight) {
        if (boxTop.isEmpty()) {
            return OptionalInt.empty();
        }
        int floor = boxTop.getAsInt();
        if (choiceLeft.isPresent() && choiceTop.isPresent() && choiceLeft.getAsInt() < chatRight) {
            floor = Math.min(floor, choiceTop.getAsInt());
        }
        return OptionalInt.of(floor);
    }

    /**
     * How far to move the chat down this frame, in GUI pixels; negative moves it up. The chat's bottom edge, plus the
     * {@code belowChat} pixels vanilla draws under it (the queued-messages line while chat delay holds messages back),
     * goes {@link #CHAT_GAP} pixels above {@code floor}. Without a floor, or when the chat already ends that far above
     * it, the chat stays where vanilla puts it.
     */
    static int chatShift(int guiHeight, OptionalInt floor, int belowChat) {
        if (floor.isEmpty()) {
            return 0;
        }
        int chatBottom = guiHeight - CHAT_BOTTOM_RISE + belowChat;
        return Math.min(0, floor.getAsInt() - CHAT_GAP - chatBottom);
    }

    /** The chat's floor for the open dialogue screen: its box, and for a {@link DialogueScreen} its choice list too. */
    private static OptionalInt chatFloorFor(Screen screen, int chatRight) {
        if (screen instanceof DialogueScreen dialogue) {
            return chatFloor(dialogue.boxTop(), dialogue.choiceLeft(), dialogue.choiceTop(), chatRight);
        }
        if (screen instanceof DialogueStyleScreen style) {
            return chatFloor(style.hudBoxTop(), OptionalInt.empty(), OptionalInt.empty(), chatRight);
        }
        return OptionalInt.empty();
    }

    /** The open screen when it is a dialogue screen, else null. Client thread only. */
    private static Screen openScreen() {
        Screen screen = Minecraft.getInstance().gui.screen();
        return isDialogueScreen(screen) ? screen : null;
    }
}
