package town.lampas.haruhi.dialogue.presenter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.NpcProfile;
import town.lampas.haruhi.dialogue.runtime.CompiledGraph;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.PresentedNode;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;
import town.lampas.haruhi.network.DialogueNodePayload;

class DialogueNodePayloadsTest {
    private static final Identifier GRAPH = Identifier.fromNamespaceAndPath("haruhi", "test/graph");
    private static final Identifier GREETER = Identifier.fromNamespaceAndPath("haruhi", "test/greeter");
    private static final Identifier PROFILE_PORTRAIT = Identifier.fromNamespaceAndPath("haruhi",
            "textures/portrait/greeter.png");
    private static final Identifier NODE_PORTRAIT = Identifier.fromNamespaceAndPath("haruhi",
            "textures/portrait/node.png");
    private static final Identifier SKIN = Identifier.fromNamespaceAndPath("minecraft",
            "textures/entity/player/wide/steve.png");

    private static NpcProfile profile(Optional<Identifier> portrait) {
        return new NpcProfile(GREETER, Component.literal("Greeter"), Optional.empty(), SKIN, Optional.empty(),
                portrait, Optional.empty(), 1.0F);
    }

    private static PresentedNode presented(Optional<Identifier> speaker, Optional<Identifier> nodePortrait) {
        DialogueNode node = new DialogueNode("hello", speaker, Optional.of(Component.literal("Hi.")),
                Optional.empty(), nodePortrait, List.of(), List.of(), Optional.empty(), List.of());
        return new PresentedNode(GRAPH, node, List.of());
    }

    /** A session with no interlocutor, so the speaker is the node's own profile. */
    private static DialogueSession session(NpcProfile npc) {
        ContentBundle content = new ContentBundle(ContentBundle.SCHEMA, Map.of(), Map.of(GREETER, npc), Map.of());
        return new DialogueSession(1, null, null, content, new CompiledGraph(GRAPH, "hello", Map.of()), null, 1, null);
    }

    @Test
    void aNodeWithoutItsOwnPortraitShowsTheSpeakerProfilesPortrait() {
        DialogueNodePayload payload = DialogueNodePayloads.node(
                session(profile(Optional.of(PROFILE_PORTRAIT))),
                presented(Optional.of(GREETER), Optional.empty()), TextResolver.AS_WRITTEN);

        assertEquals("Greeter", payload.speakerName().getString(), "the name comes from the same profile");
        assertEquals(Optional.of(PROFILE_PORTRAIT), payload.portrait());
    }

    @Test
    void aNodesOwnPortraitOverridesTheSpeakerProfilesPortrait() {
        DialogueNodePayload payload = DialogueNodePayloads.node(
                session(profile(Optional.of(PROFILE_PORTRAIT))),
                presented(Optional.of(GREETER), Optional.of(NODE_PORTRAIT)), TextResolver.AS_WRITTEN);

        assertEquals(Optional.of(NODE_PORTRAIT), payload.portrait());
    }

    /** Marks the text it resolves, so a test can see which fields went through it. */
    private static final TextResolver MARKING = TextResolver.of(text -> Component.literal("resolved " + text.getString()));

    private static VisibleChoice choice(int choiceIndex, int providedIndex, String text, boolean enabled, String reason) {
        return new VisibleChoice(choiceIndex, providedIndex, Component.literal(text), enabled,
                Optional.of(Component.literal(reason)), List.of(), Optional.empty());
    }

    @Test
    void bundleTextIsResolvedAndProviderEntriesAreSentAsGiven() {
        DialogueNode node = new DialogueNode("hello", Optional.of(GREETER), Optional.of(Component.literal("Hi.")),
                Optional.empty(), Optional.empty(), List.of(), List.of(), Optional.empty(), List.of());
        PresentedNode presented = new PresentedNode(GRAPH, node, List.of(
                choice(0, -1, "Stay", false, "Not yet."),
                choice(1, 0, "Entry from a provider", false, "Provider reason")));

        DialogueNodePayload payload =
                DialogueNodePayloads.node(session(profile(Optional.empty())), presented, MARKING);

        assertEquals("resolved Greeter", payload.speakerName().getString(), "the speaker's name");
        assertEquals("resolved Hi.", payload.text().getString(), "the node text");
        assertEquals("resolved Stay", payload.choices().get(0).text().getString(), "a static choice");
        assertEquals("resolved Not yet.", payload.choices().get(0).unavailableReason().orElseThrow().getString(),
                "a static choice's unavailable message");
        assertEquals("Entry from a provider", payload.choices().get(1).text().getString(),
                "a provider entry, which may hold player-made text, is not resolved");
        assertEquals("Provider reason", payload.choices().get(1).unavailableReason().orElseThrow().getString(),
                "a provider entry's reason is not resolved either");
    }

    @Test
    void aSpeakerProfileWithoutAPortraitGivesNoPortrait() {
        DialogueNodePayload payload = DialogueNodePayloads.node(
                session(profile(Optional.empty())),
                presented(Optional.of(GREETER), Optional.empty()), TextResolver.AS_WRITTEN);

        assertEquals(Optional.empty(), payload.portrait());
    }
}
