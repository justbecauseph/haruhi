package town.lampas.haruhi.client.api;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.client.npc.HeadshotRenderer;

/**
 * The look of the visual-novel dialogue screen, for another mod's screen to draw in the same style: the text box with
 * its one-pixel edge, the square headshot frame, and the colours of the speaker's name, the text and the choices. Use
 * these from a screen's {@code extractRenderState}, on the client thread. A screen that implements
 * {@link DialogueStyleScreen} also gets the HUD treatment the dialogue screen gets.
 *
 * <p>The colours are ARGB. None of the drawing methods fills the rest of the screen, so the world shows through.
 */
public final class DialogueStyle {
    /** The fill of the text box. */
    public static final int BOX_FILL = 0xD0101018;
    /** The one-pixel edge of the text box and of the headshot frame, and the divider under the speaker's name. */
    public static final int BOX_EDGE = 0xFF8A7F6A;
    /** The fill behind a headshot or portrait. */
    public static final int PORTRAIT_FILL = 0xB0000000;
    /** The speaker's name. */
    public static final int NAME_TEXT = 0xFFFFD27F;
    /** The body text. */
    public static final int TEXT = 0xFFF2EDE2;
    /** A choice that can be picked. */
    public static final int CHOICE_TEXT = 0xFFF2EDE2;
    /** A choice that cannot be picked. */
    public static final int CHOICE_LOCKED_TEXT = 0xFF77736A;
    /** A choice under the mouse pointer, or otherwise selected. */
    public static final int CHOICE_HOVER_TEXT = 0xFFFFE08A;

    private DialogueStyle() {}

    /**
     * Draws the text box from {@code (x0, y0)} to {@code (x1, y1)} in GUI pixels: the {@link #BOX_FILL} fill, with the
     * {@link #BOX_EDGE} edge just inside the rectangle. Client thread only, from a screen's {@code extractRenderState}.
     */
    public static void drawBox(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        graphics.fill(x0, y0, x1, y1, BOX_FILL);
        drawEdge(graphics, x0, y0, x1, y1);
    }

    /**
     * Draws a one-pixel {@link #BOX_EDGE} line just inside the rectangle from {@code (x0, y0)} to {@code (x1, y1)}.
     * Client thread only, from a screen's {@code extractRenderState}.
     */
    public static void drawEdge(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        graphics.fill(x0, y0, x1, y0 + 1, BOX_EDGE);
        graphics.fill(x0, y1 - 1, x1, y1, BOX_EDGE);
        graphics.fill(x0, y0, x0 + 1, y1, BOX_EDGE);
        graphics.fill(x1 - 1, y0, x1, y1, BOX_EDGE);
    }

    /**
     * Draws the headshot square from {@code (x0, y0)} to {@code (x1, y1)} in GUI pixels, with its {@link #BOX_EDGE}
     * edge one pixel outside it. When {@code speaker} is not null, the speaker's head is drawn in the square by
     * {@link HeadshotRenderer#draw}; otherwise the square is an empty frame. Client thread only, from a screen's
     * {@code extractRenderState}.
     */
    public static void drawHeadshot(GuiGraphicsExtractor graphics, @Nullable LivingEntity speaker,
                                    int x0, int y0, int x1, int y1) {
        drawFrame(graphics, x0, y0, x1, y1);
        if (speaker != null) {
            HeadshotRenderer.draw(graphics, speaker, x0, y0, x1, y1);
        }
    }

    /**
     * Draws the square from {@code (x0, y0)} to {@code (x1, y1)} in GUI pixels with the 2D {@code texture} stretched
     * over it, inside the same frame as {@link #drawHeadshot}. A texture that is not square is stretched too, so use a
     * square one. Client thread only, from a screen's {@code extractRenderState}.
     */
    public static void drawPortrait(GuiGraphicsExtractor graphics, Identifier texture, int x0, int y0, int x1, int y1) {
        drawFrame(graphics, x0, y0, x1, y1);
        graphics.blit(texture, x0, y0, x1, y1, 0.0F, 1.0F, 0.0F, 1.0F);
    }

    /** The {@link #PORTRAIT_FILL} square with its {@link #BOX_EDGE} edge one pixel outside it. */
    private static void drawFrame(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        drawEdge(graphics, x0 - 1, y0 - 1, x1 + 1, y1 + 1);
        graphics.fill(x0, y0, x1, y1, PORTRAIT_FILL);
    }
}
