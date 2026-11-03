package town.lampas.haruhi.dialogue.api;

/** A parsed, ready-to-run action. Runs on the server thread after the server has re-checked the choice. */
@FunctionalInterface
public interface DialogueAction {
    DialogueAction NONE = context -> {};

    void run(DialogueContext context);
}
