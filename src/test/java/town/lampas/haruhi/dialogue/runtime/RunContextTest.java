package town.lampas.haruhi.dialogue.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;

/**
 * What a condition, action or provider sees of the conversation's anchor (ALINA-PLAN H2): the anchor of a conversation
 * opened at a place, and none otherwise.
 */
class RunContextTest {
    private static final Identifier GRAPH = Identifier.fromNamespaceAndPath("haruhi_test", "board");
    private static final CompiledGraph EMPTY_GRAPH = new CompiledGraph(GRAPH, "hello", Map.of());

    @Test
    void aContextReportsTheAnchorOfItsSession() {
        DialogueAnchor anchor = new DialogueAnchor(Level.OVERWORLD, new Vec3(10.5, 64, -3.5), 6.0);
        DialogueSession session =
                new DialogueSession(1, null, null, anchor, ContentBundle.EMPTY, EMPTY_GRAPH, null, 1, null);

        assertEquals(Optional.of(anchor), RunContext.of(session, "hello").anchor());
    }

    @Test
    void aContextWithoutAnAnchorReportsNone() {
        DialogueSession session = new DialogueSession(1, null, null, ContentBundle.EMPTY, EMPTY_GRAPH, null, 1, null);

        assertEquals(Optional.empty(), RunContext.of(session, "hello").anchor());
    }

    @Test
    void anAnchorReachesAboveZeroAndAtMostSixtyFourBlocks() {
        Vec3 here = Vec3.ZERO;
        assertThrows(IllegalArgumentException.class, () -> new DialogueAnchor(Level.OVERWORLD, here, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new DialogueAnchor(Level.OVERWORLD, here, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new DialogueAnchor(Level.OVERWORLD, here, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new DialogueAnchor(Level.OVERWORLD, here, 64.5));
        assertEquals(64.0, new DialogueAnchor(Level.OVERWORLD, here, 64.0).maxDistance());
    }

    @Test
    void anAnchorPositionIsFinite() {
        assertThrows(IllegalArgumentException.class,
                () -> new DialogueAnchor(Level.OVERWORLD, new Vec3(Double.NaN, 64, 0), 4.0));
        assertThrows(IllegalArgumentException.class,
                () -> new DialogueAnchor(Level.OVERWORLD, new Vec3(0, Double.POSITIVE_INFINITY, 0), 4.0));
        assertThrows(IllegalArgumentException.class,
                () -> new DialogueAnchor(Level.OVERWORLD, new Vec3(0, 64, Double.NEGATIVE_INFINITY), 4.0));
    }
}
