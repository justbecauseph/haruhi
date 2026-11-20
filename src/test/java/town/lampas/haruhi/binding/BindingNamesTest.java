package town.lampas.haruhi.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.NpcProfile;

/** Unit tests for {@link BindingNames#nameOverride}. */
class BindingNamesTest {
    private static final Identifier GRAPH_NAMED = Identifier.fromNamespaceAndPath("haruhi", "test/named");
    private static final Identifier GRAPH_PLAIN = Identifier.fromNamespaceAndPath("haruhi", "test/plain");
    private static final Identifier GRAPH_MISSING = Identifier.fromNamespaceAndPath("haruhi", "test/missing");
    private static final Identifier CLERK = Identifier.fromNamespaceAndPath("haruhi", "test/clerk");
    private static final Identifier MISSING_NPC = Identifier.fromNamespaceAndPath("haruhi", "test/gone");
    private static final Identifier SKIN = Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    private static ContentBundle bundle;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        NpcProfile clerk = new NpcProfile(CLERK, Component.literal("Clerk"), Optional.empty(), SKIN, Optional.empty(),
                Optional.empty(), Optional.empty(), 1.0F);
        DialogueNode hello = new DialogueNode("hello", Optional.empty(), Optional.of(Component.literal("Hi")),
                Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty(), List.of());
        DialogueGraph named = new DialogueGraph(GRAPH_NAMED, "hello", Map.of("hello", hello),
                Optional.of(Component.literal("Crystal Ball")));
        DialogueGraph plain = new DialogueGraph(GRAPH_PLAIN, "hello", Map.of("hello", hello));
        bundle = new ContentBundle(1, Map.of(), Map.of(CLERK, clerk),
                Map.of(GRAPH_NAMED, named, GRAPH_PLAIN, plain), Map.of());
    }

    @Test
    void returnsSpeakerNameWhenNoLoadedNpcSpeaks() {
        Optional<Component> withoutSpeaker = BindingNames.nameOverride(
                new Binding(GRAPH_NAMED, Optional.empty()), bundle);
        Optional<Component> withMissingSpeaker = BindingNames.nameOverride(
                new Binding(GRAPH_NAMED, Optional.of(MISSING_NPC)), bundle);

        assertEquals("Crystal Ball", withoutSpeaker.map(Component::getString).orElse(null));
        assertEquals("Crystal Ball", withMissingSpeaker.map(Component::getString).orElse(null));
    }

    @Test
    void returnsEmptyWhenNpcSpeaksOrOverrideOrGraphIsMissing() {
        assertEquals(Optional.empty(), BindingNames.nameOverride(new Binding(GRAPH_NAMED, Optional.of(CLERK)), bundle));
        assertEquals(Optional.empty(), BindingNames.nameOverride(new Binding(GRAPH_PLAIN, Optional.empty()), bundle));
        assertEquals(Optional.empty(), BindingNames.nameOverride(new Binding(GRAPH_MISSING, Optional.empty()), bundle));
    }
}
