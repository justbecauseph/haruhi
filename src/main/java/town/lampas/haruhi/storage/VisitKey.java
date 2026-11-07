package town.lampas.haruhi.storage;

import java.util.Objects;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.Haruhi;

/**
 * A visit counter: one dialogue graph opened with one NPC profile. A graph opened without an NPC uses the speaker
 * profile of the binding or caller that opened it, and {@link #NO_NPC} when there is none (a command, or a bound block
 * or entity without a speaker profile).
 */
public record VisitKey(Identifier graph, Identifier npc) {
    public static final Identifier NO_NPC = Haruhi.id("none");

    public VisitKey {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(npc);
    }
}
