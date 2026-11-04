package town.lampas.haruhi.client.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.network.ChoiceView;
import town.lampas.haruhi.network.DialogueChoicePayload;
import town.lampas.haruhi.network.DialogueDismissPayload;
import town.lampas.haruhi.network.DialogueNodePayload;

/**
 * The visual-novel dialogue screen. A text box runs along the bottom with the speaker's name on a plate inside it. The
 * text types out at the configured speed; a click, Space or Enter finishes it, and once it is finished a further press
 * continues when the node has no choices. Choices stack above the box on the right, and the number keys or a click pick
 * them once the text is finished. A portrait sits above the box on the left: the node's 2D portrait when it names one that
 * exists, else a 3D render of the speaker when it is loaded.
 *
 * <p>Input only sends the session id, node id and choice index; the server checks each one. Escape sends a dismiss and
 * closes the screen.
 */
public final class DialogueScreen extends Screen {
    private static final int MARGIN = 12;
    private static final int PADDING = 14;
    private static final int TOP_PADDING = 10;
    private static final int BOTTOM_PADDING = 18;
    private static final int MIN_BOX_HEIGHT = 84;
    private static final int NAME_HEIGHT = 14;
    private static final int NAME_GAP = 6;
    private static final int ROW_HEIGHT = 16;
    private static final int ROW_GAP = 2;
    private static final int CHOICE_NUMBER_WIDTH = 16;
    /** The space between a choice row's edges and its label. */
    private static final int CHOICE_INSET = 6;

    private static final int BOX_FILL = 0xD0101018;
    private static final int BOX_EDGE = 0xFF8A7F6A;
    private static final int NAME_FILL = 0xE0284050;
    private static final int NAME_TEXT = 0xFFFFD27F;
    private static final int TEXT = 0xFFF2EDE2;
    private static final int HINT = 0xFFB8B09E;
    private static final int CHOICE_FILL = 0xB0101018;
    private static final int CHOICE_HOVER_FILL = 0xD0304060;
    private static final int CHOICE_TEXT = 0xFFF2EDE2;
    private static final int CHOICE_HOVER_TEXT = 0xFFFFE08A;
    private static final int CHOICE_LOCKED_TEXT = 0xFF77736A;
    private static final int PORTRAIT_FILL = 0xB0000000;
    /** How long a sent choice blocks further input. The server ignores a stale pick silently, so this must expire. */
    private static final long AWAIT_TIMEOUT_MILLIS = 2000L;

    private final double textSpeed;
    private DialogueNodePayload node;
    private int sessionId;
    private Typewriter typewriter;
    /** When a choice or continue was last sent, or -1. Input waits for the next node, up to {@link #AWAIT_TIMEOUT_MILLIS}. */
    private long awaitingSinceMillis = -1L;

    private List<FormattedCharSequence> lines = List.of();
    private int totalChars;
    private int boxX;
    private int boxY;
    private int boxWidth;
    private int boxHeight;
    private int textX;
    private int textY;
    private int textWidth;
    private int lineHeight;
    private final List<ChoiceRow> choiceRows = new ArrayList<>();

    /** A choice's place on screen from the last frame, for mouse picks. {@code position} indexes the node's choices. */
    private record ChoiceRow(int position, int x0, int y0, int x1, int y1) {}

    public DialogueScreen(DialogueNodePayload first, double textSpeed) {
        super(first.speakerName());
        this.textSpeed = textSpeed;
        this.node = first;
        this.sessionId = first.sessionId();
        this.typewriter = new Typewriter(textSpeed, Util.getMillis());
    }

    /** The server's id for the conversation on this screen. */
    public int sessionId() {
        return sessionId;
    }

    /** The entity this screen names as the speaker, when the node names one. */
    public OptionalInt speakerEntityId() {
        return node == null ? OptionalInt.empty() : node.speakerEntityId();
    }

    /** Replaces the shown node with the next one of the same conversation, and types it out from the start. */
    public void showNode(DialogueNodePayload payload) {
        this.node = payload;
        this.sessionId = payload.sessionId();
        this.awaitingSinceMillis = -1L;
        this.typewriter = new Typewriter(textSpeed, Util.getMillis());
        layout();
    }

    @Override
    protected void init() {
        layout();
    }

    /** Works out the box, the wrapped lines and the text's character count for the current window size. */
    private void layout() {
        if (node == null || width <= 0 || height <= 0) {
            return;
        }
        boxWidth = width - 2 * MARGIN;
        boxX = MARGIN;
        textX = boxX + PADDING;
        textWidth = boxWidth - 2 * PADDING;
        lineHeight = font.lineHeight + 2;
        lines = font.split(node.text(), textWidth);
        totalChars = countCharacters(lines);

        int namePart = hasName() ? NAME_HEIGHT + NAME_GAP : 0;
        int wanted = TOP_PADDING + namePart + lines.size() * lineHeight + BOTTOM_PADDING;
        boxHeight = Math.min(Math.max(MIN_BOX_HEIGHT, wanted), height - 2 * MARGIN);
        boxY = height - MARGIN - boxHeight;
        textY = boxY + TOP_PADDING + namePart;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (node == null) {
            return;
        }
        int visible = typewriter.visibleChars(Util.getMillis(), totalChars);
        boolean textDone = visible >= totalChars;

        drawBox(graphics);
        drawNameplate(graphics);
        drawText(graphics, visible);
        drawPortrait(graphics);
        drawChoices(graphics, mouseX, mouseY);
        drawContinueHint(graphics, textDone);
    }

    private void drawBox(GuiGraphicsExtractor graphics) {
        int right = boxX + boxWidth;
        int bottom = boxY + boxHeight;
        graphics.fill(boxX, boxY, right, bottom, BOX_FILL);
        graphics.fill(boxX, boxY, right, boxY + 1, BOX_EDGE);
        graphics.fill(boxX, bottom - 1, right, bottom, BOX_EDGE);
        graphics.fill(boxX, boxY, boxX + 1, bottom, BOX_EDGE);
        graphics.fill(right - 1, boxY, right, bottom, BOX_EDGE);
    }

    private void drawNameplate(GuiGraphicsExtractor graphics) {
        if (!hasName()) {
            return;
        }
        int x = textX;
        int y = boxY + TOP_PADDING - 4;
        int plateWidth = font.width(node.speakerName()) + 12;
        graphics.fill(x, y, x + plateWidth, y + NAME_HEIGHT, NAME_FILL);
        graphics.text(font, node.speakerName(), x + 6, y + 3, NAME_TEXT);
    }

    /** Draws the first {@code visible} characters of the wrapped lines, top to bottom. */
    private void drawText(GuiGraphicsExtractor graphics, int visible) {
        int remaining = visible;
        int y = textY;
        for (FormattedCharSequence line : lines) {
            int take = Math.min(codepoints(line), remaining);
            if (take > 0) {
                graphics.text(font, prefix(line, take), textX, y, TEXT);
            }
            remaining -= take;
            y += lineHeight;
        }
    }

    private void drawPortrait(GuiGraphicsExtractor graphics) {
        int portraitWidth = Math.min(160, Math.max(72, width / 5));
        int portraitHeight = Math.min(portraitWidth * 3 / 2, boxY - 2 * MARGIN - 8);
        if (portraitHeight < 40) {
            return;
        }
        int x = boxX + 4;
        int y = boxY - portraitHeight - 8;
        int right = x + portraitWidth;
        int bottom = y + portraitHeight;

        Optional<Identifier> portrait = node.portrait();
        if (portrait.isPresent() && NpcLookAssets.resourceExists(portrait.get())) {
            graphics.fill(x, y, right, bottom, PORTRAIT_FILL);
            graphics.blit(portrait.get(), x, y, right, bottom, 0.0F, 1.0F, 0.0F, 1.0F);
            return;
        }
        LivingEntity speaker = speakerEntity();
        if (speaker != null) {
            graphics.fill(x, y, right, bottom, PORTRAIT_FILL);
            // The inventory helper takes pixels per block. A 1.8-block NPC then fills about three quarters of the frame.
            InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x, y, right, bottom,
                    Math.max(1, portraitHeight * 5 / 12), 0.0625F, x + portraitWidth / 2.0F, y + portraitHeight / 2.0F,
                    speaker);
        }
    }

    private void drawChoices(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        choiceRows.clear();
        List<ChoiceView> choices = node.choices();
        if (choices.isEmpty()) {
            return;
        }
        int widest = 0;
        for (ChoiceView choice : choices) {
            widest = Math.max(widest, font.width(choice.text()));
        }
        int rowWidth = Math.min(boxWidth * 3 / 5, widest + CHOICE_NUMBER_WIDTH + 12);
        int right = boxX + boxWidth - PADDING;
        int left = right - rowWidth;
        int labelWidth = rowWidth - 2 * CHOICE_INSET;
        List<ChoiceLayout.Row> rows =
                ChoiceLayout.place(choices.size(), MARGIN, boxY - 8, ROW_HEIGHT + ROW_GAP, ROW_HEIGHT);

        for (int i = 0; i < choices.size(); i++) {
            ChoiceView choice = choices.get(i);
            ChoiceLayout.Row row = rows.get(i);
            int y0 = row.y();
            int y1 = y0 + row.height();
            boolean hovered = mouseX >= left && mouseX < right && mouseY >= y0 && mouseY < y1;
            choiceRows.add(new ChoiceRow(i, left, y0, right, y1));

            graphics.fill(left, y0, right, y1, hovered && choice.enabled() ? CHOICE_HOVER_FILL : CHOICE_FILL);
            int color = !choice.enabled() ? CHOICE_LOCKED_TEXT : hovered ? CHOICE_HOVER_TEXT : CHOICE_TEXT;
            String number = i < ChoiceKeys.MAX_KEYED_CHOICES ? (i + 1) + ". " : "";
            // The first line that fits the row. A label wider than the row is cut short, not drawn past the screen edge.
            List<FormattedCharSequence> fitted = font.split(Component.literal(number).append(choice.text()), labelWidth);
            if (!fitted.isEmpty()) {
                int textY = y0 + (row.height() - font.lineHeight + 1) / 2;
                graphics.text(font, fitted.getFirst(), left + CHOICE_INSET, textY, color);
            }

            if (hovered && !choice.enabled() && choice.unavailableReason().isPresent()) {
                graphics.setTooltipForNextFrame(font, choice.unavailableReason().get(), mouseX, mouseY);
            }
        }
    }

    private void drawContinueHint(GuiGraphicsExtractor graphics, boolean textDone) {
        if (!textDone || !node.choices().isEmpty()) {
            return;
        }
        Component hint = Component.translatable("screen.haruhi.dialogue.continue");
        int x = boxX + boxWidth - PADDING - font.width(hint);
        graphics.text(font, hint, x, boxY + boxHeight - 13, HINT);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            advance();
            return true;
        }
        int position = ChoiceKeys.positionForKey(key, node.choices().size());
        if (position >= 0 && isTextComplete()) {
            pick(position);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return super.mouseClicked(event, doubleClick);
        }
        int position = rowAt(event.x(), event.y());
        if (position >= 0 && isTextComplete()) {
            pick(position);
        } else {
            advance();
        }
        return true;
    }

    /** Escape: tell the server the player dismissed the conversation, then close. */
    @Override
    public void onClose() {
        if (node != null && ClientPlayNetworking.canSend(DialogueDismissPayload.TYPE)) {
            ClientPlayNetworking.send(new DialogueDismissPayload(sessionId));
        }
        Minecraft.getInstance().gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Finishes the typing, or continues when the text is finished and the node has no choices. */
    private void advance() {
        if (node == null || isAwaitingServer()) {
            return;
        }
        if (!isTextComplete()) {
            typewriter.complete();
            return;
        }
        if (node.choices().isEmpty()) {
            send(DialogueChoicePayload.CONTINUE);
        }
    }

    /** Sends the choice at {@code position} when it is available. Grayed-out choices pick nothing. */
    private void pick(int position) {
        if (node == null || isAwaitingServer() || !isTextComplete()) {
            return;
        }
        if (position < 0 || position >= node.choices().size()) {
            return;
        }
        ChoiceView choice = node.choices().get(position);
        if (choice.enabled()) {
            send(choice.index());
        }
    }

    private void send(int choiceIndex) {
        if (!ClientPlayNetworking.canSend(DialogueChoicePayload.TYPE)) {
            return;
        }
        awaitingSinceMillis = Util.getMillis();
        ClientPlayNetworking.send(new DialogueChoicePayload(sessionId, node.nodeId(), choiceIndex));
    }

    private boolean isAwaitingServer() {
        return awaitingSinceMillis >= 0L && Util.getMillis() - awaitingSinceMillis < AWAIT_TIMEOUT_MILLIS;
    }

    private int rowAt(double mouseX, double mouseY) {
        for (ChoiceRow row : choiceRows) {
            if (mouseX >= row.x0() && mouseX < row.x1() && mouseY >= row.y0() && mouseY < row.y1()) {
                return row.position();
            }
        }
        return -1;
    }

    private boolean isTextComplete() {
        return typewriter.isComplete(Util.getMillis(), totalChars);
    }

    private boolean hasName() {
        return node != null && !node.speakerName().getString().isEmpty();
    }

    /** The speaker as a loaded living entity, or null when it is not in the level. */
    private LivingEntity speakerEntity() {
        OptionalInt id = speakerEntityId();
        if (id.isEmpty() || Minecraft.getInstance().level == null) {
            return null;
        }
        Entity entity = Minecraft.getInstance().level.getEntity(id.getAsInt());
        return entity instanceof LivingEntity living ? living : null;
    }

    private static int countCharacters(List<FormattedCharSequence> lines) {
        int total = 0;
        for (FormattedCharSequence line : lines) {
            total += codepoints(line);
        }
        return total;
    }

    private static int codepoints(FormattedCharSequence line) {
        int[] count = {0};
        line.accept((index, style, codepoint) -> {
            count[0]++;
            return true;
        });
        return count[0];
    }

    /** The first {@code limit} characters of {@code line}, with their styles. */
    private static FormattedCharSequence prefix(FormattedCharSequence line, int limit) {
        return sink -> {
            int[] seen = {0};
            return line.accept((index, style, codepoint) -> {
                if (seen[0] >= limit) {
                    return false;
                }
                seen[0]++;
                return sink.accept(index, style, codepoint);
            });
        };
    }
}
