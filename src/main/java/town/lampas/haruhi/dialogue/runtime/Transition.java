package town.lampas.haruhi.dialogue.runtime;

import net.minecraft.resources.Identifier;

/** Where the conversation goes after an action set it: another node, the end, or another graph. */
record Transition(Kind kind, String node, Identifier graph) {

    enum Kind {
        GOTO,
        END,
        OPEN
    }

    static Transition goTo(String node) {
        return new Transition(Kind.GOTO, node, null);
    }

    static Transition end() {
        return new Transition(Kind.END, null, null);
    }

    static Transition open(Identifier graph) {
        return new Transition(Kind.OPEN, null, graph);
    }
}
