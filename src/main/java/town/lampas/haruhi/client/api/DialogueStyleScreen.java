package town.lampas.haruhi.client.api;

import java.util.OptionalInt;

/**
 * Marks a screen as a dialogue screen for the HUD, as the Haruhi dialogue screen is. While the screen is open, the
 * vanilla elements its box would cover (hotbar, status bars, experience bar and level, held-item name, crosshair) are
 * not drawn, and the chat moves up so that it ends above the box. Implement it on a screen that draws its box with
 * {@link DialogueStyle}. Client thread only.
 */
public interface DialogueStyleScreen {
    /**
     * The GUI y of the top edge of this screen's box, in GUI pixels. The HUD calls it each frame, on the client
     * thread, and keeps the chat's bottom edge a few pixels above it. Empty, the default, leaves the chat where vanilla
     * puts it.
     */
    default OptionalInt hudBoxTop() {
        return OptionalInt.empty();
    }
}
