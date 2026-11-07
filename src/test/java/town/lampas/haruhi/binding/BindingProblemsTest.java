package town.lampas.haruhi.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.NpcProfile;

/** What {@code /haruhi bindings} marks on a binding, against the active content and the block now at the position. */
class BindingProblemsTest {
    private static final Identifier GRAPH = Identifier.fromNamespaceAndPath("haruhi", "test/lectern");
    private static final Identifier SPEAKER = Identifier.fromNamespaceAndPath("haruhi", "test/clerk");
    private static final Identifier CHEST = Identifier.withDefaultNamespace("chest");
    private static final Identifier STONE = Identifier.withDefaultNamespace("stone");
    private static final Identifier SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    private static final ContentBundle WITH_BOTH = new ContentBundle(ContentBundle.SCHEMA, Map.of(),
            Map.of(SPEAKER, new NpcProfile(SPEAKER, Component.literal("Clerk"), Optional.empty(), SKIN,
                    Optional.empty(), Optional.empty(), Optional.empty(), 1.0F)),
            Map.of(GRAPH, new DialogueGraph(GRAPH, "hello", Map.of())));

    @Test
    void aBindingWhoseGraphAndSpeakerExistHasNoProblems() {
        assertEquals(List.of(), BindingProblems.of(new Binding(GRAPH, Optional.of(SPEAKER)), WITH_BOTH));
        assertEquals(List.of(), BindingProblems.of(
                new BlockBinding(GRAPH, Optional.empty(), CHEST), WITH_BOTH, CHEST));
    }

    @Test
    void aRevisionWithoutTheGraphOrTheSpeakerMarksBoth() {
        assertEquals(List.of(BindingProblems.MISSING_GRAPH, BindingProblems.MISSING_SPEAKER),
                BindingProblems.of(new Binding(GRAPH, Optional.of(SPEAKER)), ContentBundle.EMPTY));
        assertEquals(List.of(BindingProblems.MISSING_GRAPH),
                BindingProblems.of(new Binding(GRAPH, Optional.empty()), ContentBundle.EMPTY),
                "a binding without a speaker cannot miss one");
    }

    @Test
    void aBlockBindingWhoseBlockChangedNamesTheBlockNowThere() {
        List<String> problems = BindingProblems.of(new BlockBinding(GRAPH, Optional.empty(), CHEST), WITH_BOTH, STONE);

        assertEquals(List.of("block is now minecraft:stone"), problems);
        assertEquals(" [block is now minecraft:stone]", BindingProblems.suffix(problems));
        assertEquals("", BindingProblems.suffix(List.of()));
    }
}
