package town.lampas.haruhi.storage;

import java.util.Objects;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.Haruhi;

/**
 * A visit counter: one dialogue graph opened with one NPC profile. A graph opened without an NPC (a block, a
 * command) uses {@link #NO_NPC}.
 */
public record VisitKey(Identifier graph, Identifier npc) {
    public static final Identifier NO_NPC = Haruhi.id("none");

    public VisitKey {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(npc);
    }
}
