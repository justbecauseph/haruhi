package town.lampas.haruhi.client.dialogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;
import town.lampas.haruhi.client.npc.HeadshotRenderer;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.network.ChoiceView;
import town.lampas.haruhi.network.DialogueChoicePayload;
import town.lampas.haruhi.network.DialogueDismissPayload;
import town.lampas.haruhi.network.DialogueNodePayload;

/**
 * The visual-novel dialogue screen. A text box runs along the bottom. Inside it, on the left, a square headshot shows
 * the speaker: the node's 2D portrait when it names one that exists (stretched to the square, so portrait textures
 * should be square), else a 3D render of the speaker's head and shoulders when the speaker is loaded, else nothing, and
 * the text then starts at the box's left padding. To the right of the headshot are the speaker's name, a divider and
 * the text ({@link DialogueLayout}). Text with more lines than the box holds is shown in pages ({@link TextPages}), with
 * a page indicator at the bottom right; turning a page does not ask the server. Each page types out at the configured
 * speed; a click, Space or Enter finishes it, and once it is finished a further press turns to the next page, or on the
 * last page continues when the node has no choices. Choices stack above the box on the right once the last page is
 * shown, and the number keys or a click pick them once it is finished. When more choices are offered than fit,
 * the list scrolls with the mouse wheel or the Up and Down keys, and the box leaves room above it for at least three of
 * them. The screen draws no background, so the world stays in view behind it; {@link DialogueHud} hides the HUD
 * elements the box would cover and moves the chat above the box ({@link #boxTop()}).
 *
 * <p>Input only sends the session id, node id and choice index; the server checks each one, and knows nothing of pages.
 * Escape sends a dismiss and closes the screen.
 */
public final class DialogueScreen extends Screen {
    private static final int MARGIN = DialogueLayout.MARGIN;
    private static final int PADDING = DialogueLayout.PADDING;
    private static final int ROW_HEIGHT = 16;
    private static final int ROW_GAP = 2;
    private static final int CHOICE_NUMBER_WIDTH = 16;
    /** The space between a choice row's edges and its label. */
    private static final int CHOICE_INSET = 6;
    /** The space between the lowest choice row and the box. */
    private static final int CHOICE_GAP = 8;
    /** The space between the page indicator and the hint left of it. */
    private static final int INDICATOR_GAP = 8;

    private static final int BOX_FILL = 0xD0101018;
    private static final int BOX_EDGE = 0xFF8A7F6A;
    private static final int NAME_TEXT = 0xFFFFD27F;
    private static final int TEXT = 0xFFF2EDE2;
    private static final int HINT = 0xFFB8B09E;
    private static final int CHOICE_FILL = 0xB0101018;
    private static final int CHOICE_HOVER_FILL = 0xD0304060;
    private static final int CHOICE_TEXT = 0xFFF2EDE2;
    private static final int CHOICE_HOVER_TEXT = 0xFFFFE08A;
    private static final int CHOICE_LOCKED_TEXT = 0xFF77736A;
    private static final int PORTRAIT_FILL = 0xB0000000;
    private static final String MORE_ABOVE = "\u25B2";
    private static final String MORE_BELOW = "\u25BC";
    /** How long a sent choice blocks further input. The server ignores a stale pick silently, so this must expire. */
    private static final long AWAIT_TIMEOUT_MILLIS = 2000L;

    private final double textSpeed;
    private DialogueNodePayload node;
    private int sessionId;
    /** Types out the current page from {@link #typeFrom}. */
    private Typewriter typewriter;
    /** When a choice or continue was last sent, or -1. Input waits for the next node, up to {@link #AWAIT_TIMEOUT_MILLIS}. */
    private long awaitingSinceMillis = -1L;

    private List<FormattedCharSequence> lines = List.of();
    /** The pages of {@link #lines}, from the last layout; null before the first layout of the node. */
    private TextPages pages;
    /** The page on screen, from 0. */
    private int page;
    /** The character offset on the current page where its typing starts; the characters before it show at once. */
    private int typeFrom;
    /**
     * How far the text has been shown, as a character offset: no less than the end of the typing on any page shown so
     * far. A page that starts before it shows that much at once, which matters after a resize re-wraps the text.
     */
    private int revealed;
    private DialogueLayout box;
    private int lineHeight;
    /** The node's portrait when its texture exists, from the last layout. */
    private Optional<Identifier> portrait = Optional.empty();
    /** Whether the last layout made room for a headshot, which it does when there is a portrait or a loaded speaker. */
    private boolean headshotLaidOut;
    private final List<ChoiceRow> choiceRows = new ArrayList<>();
    /** The position of the choice scrolled to the top of the choice list, as the last frame clamped it. */
    private int choiceScroll;

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

    /** The top edge of the text box in GUI pixels, from the last layout; empty before the first layout. */
    OptionalInt boxTop() {
        return box == null ? OptionalInt.empty() : OptionalInt.of(box.boxY());
    }

    /** The left edge of the choice rows drawn in the last frame, in GUI pixels; empty when none were drawn. */
    OptionalInt choiceLeft() {
        return choiceRows.stream().mapToInt(ChoiceRow::x0).min();
    }

    /** The top edge of the highest choice row drawn in the last frame, in GUI pixels; empty when none were drawn. */
    OptionalInt choiceTop() {
        return choiceRows.stream().mapToInt(ChoiceRow::y0).min();
    }

    /** The entity this screen names as the speaker, when the node names one. */
    public OptionalInt speakerEntityId() {
        return node == null ? OptionalInt.empty() : node.speakerEntityId();
    }

    /**
     * Replaces the shown node with the next one of the same conversation, and types it out from its first page. That
     * includes the node on screen sent again, whether a choice leads back to it or the server refused a pick: the payload
     * does not say which, and a node that repeats itself should be read again from the start.
     */
    public void showNode(DialogueNodePayload payload) {
        this.node = payload;
        this.sessionId = payload.sessionId();
        this.awaitingSinceMillis = -1L;
        this.choiceScroll = 0;
        this.pages = null;
        this.typewriter = new Typewriter(textSpeed, Util.getMillis());
        layout();
    }

    @Override
    protected void init() {
        layout();
    }

    /**
     * Works out the box, the headshot frame, the wrapped lines and their pages for the current window size, the node, and
     * whether there is a headshot to show. When the node was laid out before (a resize, a GUI scale change, a headshot
     * that appears or goes), the reader stays at the same place in the text: on the page that holds the first character
     * of the page they were on, with what they had been shown still shown and the typing going on from there.
     */
    private void layout() {
        if (node == null || width <= 0 || height <= 0) {
            return;
        }
        long now = Util.getMillis();
        boolean relayout = pages != null;
        int pageStartGlyphs = 0;
        int revealedGlyphs = 0;
        if (relayout) {
            // Counts of non-whitespace characters, which do not change when the text wraps to another width.
            revealed = Math.max(revealed, visibleEnd(now));
            pageStartGlyphs = pages.glyphsBefore(pages.page(page).startChar());
            revealedGlyphs = pages.glyphsBefore(revealed);
        }
        portrait = node.portrait().filter(NpcLookAssets::resourceExists);
        headshotLaidOut = portrait.isPresent() || speakerEntity() != null;
        lineHeight = font.lineHeight + 2;
        // Leave room above the box for a few choice rows, so a long text cannot push them into the box or off screen.
        int choiceRoom = ChoiceLayout.reservedHeight(node.choices().size(), minChoicePitch(), CHOICE_GAP);
        Component text = node.text();
        box = DialogueLayout.compute(width, height, headshotLaidOut, hasName(), font.lineHeight, lineHeight,
                choiceRoom, textWidth -> font.split(text, textWidth).size());
        lines = font.split(text, box.textWidth());
        pages = TextPages.paginate(codepoints(lines), box.pageLines());
        if (relayout) {
            page = pages.pageAt(pages.charOfGlyph(pageStartGlyphs));
            revealed = pages.charOfGlyph(revealedGlyphs);
        } else {
            page = 0;
            revealed = 0;
        }
        startPage(now);
    }

    /** Starts typing the current page at {@code now}, from where the text has been shown up to. */
    private void startPage(long now) {
        TextPages.Page current = pages.page(page);
        typeFrom = Math.clamp(revealed, current.startChar(), current.endChar());
        typewriter = new Typewriter(textSpeed, now);
    }

    /** The character offset the current page has been typed up to at {@code now}. */
    private int visibleEnd(long now) {
        int end = pages.page(page).endChar();
        return typeFrom + typewriter.visibleChars(now, Math.max(0, end - typeFrom));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (node == null || box == null || pages == null) {
            return;
        }
        LivingEntity speaker = speakerEntity();
        // The speaker can load or unload while the screen is open, which adds or removes the 3D headshot.
        if (portrait.isEmpty() && (speaker != null) != headshotLaidOut) {
            layout();
        }
        int visible = visibleEnd(Util.getMillis());
        boolean pageDone = visible >= pages.page(page).endChar();

        drawBox(graphics);
        drawHeadshot(graphics, speaker);
        drawName(graphics);
        drawText(graphics, visible);
        drawChoices(graphics, mouseX, mouseY);
        drawFooter(graphics, pageDone);
    }

    /**
     * No blur and no menu background, as the chat screen draws none, so the world shows behind the dialogue. What
     * {@link Screen#extractBackground} does after its background still runs: the HUD's deferred subtitles.
     */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    private void drawBox(GuiGraphicsExtractor graphics) {
        int right = box.boxX() + box.boxWidth();
        int bottom = box.boxY() + box.boxHeight();
        graphics.fill(box.boxX(), box.boxY(), right, bottom, BOX_FILL);
        drawEdge(graphics, box.boxX(), box.boxY(), right, bottom);
    }

    /** A one-pixel {@link #BOX_EDGE} line just inside the rectangle from {@code (x0, y0)} to {@code (x1, y1)}. */
    private static void drawEdge(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        graphics.fill(x0, y0, x1, y0 + 1, BOX_EDGE);
        graphics.fill(x0, y1 - 1, x1, y1, BOX_EDGE);
        graphics.fill(x0, y0, x0 + 1, y1, BOX_EDGE);
        graphics.fill(x1 - 1, y0, x1, y1, BOX_EDGE);
    }

    /** The headshot square with an edge around it: the portrait, else the speaker's head, else an empty frame. */
    private void drawHeadshot(GuiGraphicsExtractor graphics, LivingEntity speaker) {
        if (!box.hasFrame()) {
            return;
        }
        int x0 = box.frameX();
        int y0 = box.frameY();
        int x1 = x0 + box.frameSize();
        int y1 = y0 + box.frameSize();
        drawEdge(graphics, x0 - 1, y0 - 1, x1 + 1, y1 + 1);
        graphics.fill(x0, y0, x1, y1, PORTRAIT_FILL);
        if (portrait.isPresent()) {
            graphics.blit(portrait.get(), x0, y0, x1, y1, 0.0F, 1.0F, 0.0F, 1.0F);
        } else if (speaker != null) {
            HeadshotRenderer.draw(graphics, speaker, x0, y0, x1, y1);
        }
    }

    /** The speaker's name at the top of the text column, cut to the column's width, with the divider under it. */
    private void drawName(GuiGraphicsExtractor graphics) {
        if (!hasName()) {
            return;
        }
        List<FormattedCharSequence> name = font.split(node.speakerName(), box.textWidth());
        if (!name.isEmpty()) {
            graphics.text(font, name.getFirst(), box.textX(), box.nameY(), NAME_TEXT);
        }
        graphics.fill(box.textX(), box.dividerY(), box.textRight(), box.dividerY() + DialogueLayout.DIVIDER_HEIGHT,
                BOX_EDGE);
    }

    /** Draws the current page's lines top to bottom, up to the character offset {@code visible}, with their styles. */
    private void drawText(GuiGraphicsExtractor graphics, int visible) {
        TextPages.Page current = pages.page(page);
        int y = box.textY();
        for (int line = current.firstLine(); line < current.endLine(); line++) {
            int take = Math.clamp(visible - pages.lineStart(line), 0, pages.lineLength(line));
            if (take > 0) {
                graphics.text(font, prefix(lines.get(line), take), box.textX(), y, TEXT);
            }
            y += lineHeight;
        }
    }

    /** The choices, once the last page is on screen. */
    private void drawChoices(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        choiceRows.clear();
        List<ChoiceView> choices = node.choices();
        if (choices.isEmpty() || !onLastPage()) {
            return;
        }
        int widest = 0;
        for (ChoiceView choice : choices) {
            widest = Math.max(widest, font.width(choice.text()));
        }
        int boxX = box.boxX();
        int boxWidth = box.boxWidth();
        int rowWidth = Math.min(boxWidth * 3 / 5, widest + CHOICE_NUMBER_WIDTH + 12);
        int right = boxX + boxWidth - PADDING;
        int left = right - rowWidth;
        int labelWidth = rowWidth - 2 * CHOICE_INSET;
        ChoiceLayout.Placement placement = ChoiceLayout.place(choices.size(), MARGIN, box.boxY() - CHOICE_GAP,
                ROW_HEIGHT + ROW_GAP, ROW_HEIGHT, minChoicePitch(), choiceScroll);
        choiceScroll = placement.first();
        List<ChoiceLayout.Row> rows = placement.rows();

        for (ChoiceLayout.Row row : rows) {
            int i = row.choice();
            ChoiceView choice = choices.get(i);
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
        // Arrows beside the list when choices are scrolled out of view above or below it.
        if (!rows.isEmpty() && placement.hiddenAbove() > 0) {
            ChoiceLayout.Row top = rows.getFirst();
            graphics.text(font, MORE_ABOVE, left - font.width(MORE_ABOVE) - 3,
                    top.y() + (top.height() - font.lineHeight + 1) / 2, HINT);
        }
        if (!rows.isEmpty() && placement.hiddenBelow() > 0) {
            ChoiceLayout.Row bottom = rows.getLast();
            graphics.text(font, MORE_BELOW, left - font.width(MORE_BELOW) - 3,
                    bottom.y() + (bottom.height() - font.lineHeight + 1) / 2, HINT);
        }
    }

    /** The smallest choice pitch that holds a line of text: the line, a pixel above it, and a pixel between rows. */
    private int minChoicePitch() {
        return font.lineHeight + 2;
    }

    /** Scrolls the choice list by {@code rows}; positive shows later choices. The next frame clamps the offset. */
    private void scrollChoices(int rows) {
        choiceScroll = Math.max(0, choiceScroll + rows);
    }

    /**
     * The bottom right of the box: the page indicator when the text has more than one page, and left of it, once the page
     * is finished, the hint for what a press does next: turn the page, or on the last page continue when there are no
     * choices.
     */
    private void drawFooter(GuiGraphicsExtractor graphics, boolean pageDone) {
        int right = box.textRight();
        if (pages.size() > 1) {
            Component indicator = Component.translatable("screen.haruhi.dialogue.page", page + 1, pages.size());
            int indicatorWidth = font.width(indicator);
            graphics.text(font, indicator, right - indicatorWidth, box.hintY(), HINT);
            right -= indicatorWidth + INDICATOR_GAP;
        }
        if (!pageDone) {
            return;
        }
        Component hint;
        if (!onLastPage()) {
            hint = Component.translatable("screen.haruhi.dialogue.more");
        } else if (node.choices().isEmpty()) {
            hint = Component.translatable("screen.haruhi.dialogue.continue");
        } else {
            return;
        }
        graphics.text(font, hint, right - font.width(hint), box.hintY(), HINT);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            advance();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && choicesShown()) {
            scrollChoices(key == GLFW.GLFW_KEY_UP ? -1 : 1);
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

    /** The wheel scrolls the choice list. Scrolling a list that fits does nothing, because each frame clamps it. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0.0 && choicesShown()) {
            scrollChoices(scrollY > 0.0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    /**
     * Finishes typing the page; or turns to the next page when the page is finished, without asking the server; or, on
     * the last page, continues when the node has no choices.
     */
    private void advance() {
        if (node == null || pages == null || isAwaitingServer()) {
            return;
        }
        long now = Util.getMillis();
        if (visibleEnd(now) < pages.page(page).endChar()) {
            typewriter.complete();
            return;
        }
        if (!onLastPage()) {
            revealed = Math.max(revealed, visibleEnd(now));
            page++;
            startPage(now);
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

    /** Whether the last page is on screen and typed out in full, which is when choices can be picked. */
    private boolean isTextComplete() {
        return onLastPage() && visibleEnd(Util.getMillis()) >= pages.page(page).endChar();
    }

    private boolean onLastPage() {
        return pages != null && page == pages.size() - 1;
    }

    /** Whether the choice list is on screen: the node has choices and its last page is shown. */
    private boolean choicesShown() {
        return node != null && !node.choices().isEmpty() && onLastPage();
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

    /** The code points of each line, for {@link TextPages}. */
    private static List<int[]> codepoints(List<FormattedCharSequence> lines) {
        List<int[]> all = new ArrayList<>(lines.size());
        for (FormattedCharSequence line : lines) {
            StringBuilder text = new StringBuilder();
            line.accept((index, style, codepoint) -> {
                text.appendCodePoint(codepoint);
                return true;
            });
            all.add(text.codePoints().toArray());
        }
        return all;
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
