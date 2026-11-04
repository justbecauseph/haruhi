package town.lampas.haruhi.content;

import com.mojang.serialization.DataResult;
import java.util.OptionalLong;
import java.util.function.Consumer;
import town.lampas.haruhi.content.model.ContentBundle;

/** The active content revision. Server thread only. */
public interface ContentService {

    /** The active bundle, or {@link ContentBundle#EMPTY} before any revision is active. Never null. */
    ContentBundle active();

    /** The id of the active stored revision; empty before any is active or after {@link #activateUnstored}. */
    OptionalLong activeRevisionId();

    /** Called on the server thread every time a bundle becomes active, including the one loaded at startup. */
    void addActivationListener(Consumer<ContentBundle> listener);

    /**
     * Parses bundle JSON (the format in AGENTS.md) and validates it completely: references resolve, every condition,
     * action and provider parses through {@link town.lampas.haruhi.dialogue.api.DialogueRegistries}, every node is
     * reachable. The error message lists every problem found, one per line.
     */
    DataResult<ContentBundle> parseAndValidate(String bundleJson);

    /**
     * Makes an already validated bundle active without storing a revision. For GameTests and previews only; a restart
     * reverts to the stored active revision.
     */
    void activateUnstored(ContentBundle bundle);

    /**
     * True once this server's startup activation has finished, whether it activated a revision, the config bundle,
     * the seed, or nothing because validation failed. GameTests wait for this before activating their own fixtures,
     * so a late startup activation cannot replace them. False again after the server stops.
     */
    default boolean isStartupComplete() {
        return true;
    }
}
