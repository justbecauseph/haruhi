package town.lampas.haruhi.dialogue.builtin;

import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.dialogue.DialogueConfig;
import town.lampas.haruhi.dialogue.api.ActionType;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;

/** Registers every built-in condition and action under the {@code haruhi} namespace. */
public final class Builtins {

    private Builtins() {}

    /**
     * Registers the built-ins. Call once, during init, because registering an id twice throws. The config supplies the
     * command allowlist and permission level that {@code haruhi:command} uses, and {@code haruhi:give} hands its items over
     * through {@code rewards}, which the caller ticks.
     */
    public static void register(DialogueConfig config, PendingRewards rewards) {
        DialogueRegistries.registerCondition(Haruhi.id("all_of"), Conditions::allOf);
        DialogueRegistries.registerCondition(Haruhi.id("any_of"), Conditions::anyOf);
        DialogueRegistries.registerCondition(Haruhi.id("not"), Conditions::not);
        DialogueRegistries.registerCondition(Haruhi.id("flag"), Conditions::flag);
        DialogueRegistries.registerCondition(Haruhi.id("visits"), Conditions::visits);
        DialogueRegistries.registerCondition(Haruhi.id("predicate"), WorldConditions::predicate);
        DialogueRegistries.registerCondition(Haruhi.id("has_item"), WorldConditions::hasItem);
        DialogueRegistries.registerCondition(Haruhi.id("advancement"), WorldConditions::advancement);
        DialogueRegistries.registerCondition(Haruhi.id("tag"), WorldConditions::tag);
        DialogueRegistries.registerCondition(Haruhi.id("permission"), WorldConditions::permission);
        DialogueRegistries.registerCondition(Haruhi.id("time"), WorldConditions::time);
        DialogueRegistries.registerCondition(Haruhi.id("weather"), WorldConditions::weather);

        DialogueRegistries.registerAction(Haruhi.id("flag/set"), Actions::flagSet);
        DialogueRegistries.registerAction(Haruhi.id("flag/add"), Actions::flagAdd);
        DialogueRegistries.registerAction(Haruhi.id("flag/clear"), Actions::flagClear);
        DialogueRegistries.registerAction(Haruhi.id("goto"), Actions::goTo);
        DialogueRegistries.registerAction(Haruhi.id("end"), Actions::end);
        DialogueRegistries.registerAction(Haruhi.id("open"), Actions::open);
        DialogueRegistries.registerAction(Haruhi.id("animation"), Actions::animation);
        DialogueRegistries.registerAction(Haruhi.id("sound"), Actions::sound);
        DialogueRegistries.registerAction(Haruhi.id("message"), Actions::message);
        DialogueRegistries.registerAction(Haruhi.id("give"), GiveAction.parser(rewards));
        DialogueRegistries.registerAction(Haruhi.id("command"), CommandAction.parser(config));
    }

    /**
     * The {@code haruhi:command} parser for a config, the same one {@link #register} installs. A GameTest uses it to run the
     * action at permission levels other than the server's config.
     */
    public static ActionType commandParser(DialogueConfig config) {
        return CommandAction.parser(config);
    }
}
