package town.lampas.haruhi.dialogue;

/**
 * Owned by the dialogue component. Registers the built-in conditions, actions and providers in
 * {@link town.lampas.haruhi.dialogue.api.DialogueRegistries}, the {@link town.lampas.haruhi.dialogue.api.DialogueService}
 * with {@link town.lampas.haruhi.api.HaruhiServices#setDialogue}, the server payload receivers, flag and visit loading
 * on join, and the vanilla dialog presenter.
 */
public final class DialogueModule {
    private DialogueModule() {}

    public static void init() {
        // Implemented by the dialogue component.
    }
}
