package town.lampas.haruhi.dialogue.api;

/** A parsed, ready-to-run condition. Must be side-effect free. */
@FunctionalInterface
public interface DialogueCondition {
    boolean test(DialogueContext context);
}
