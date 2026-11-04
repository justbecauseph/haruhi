package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import town.lampas.haruhi.content.model.ActionSpec;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueChoice;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.DialogueNode;
import town.lampas.haruhi.content.model.NpcProfile;

import static town.lampas.haruhi.content.ContentTestSupport.GRAPH;
import static town.lampas.haruhi.content.ContentTestSupport.NPC;
import static town.lampas.haruhi.content.ContentTestSupport.TEXTURE;
import static town.lampas.haruhi.content.ContentTestSupport.addNode;
import static town.lampas.haruhi.content.ContentTestSupport.baseBundle;
import static town.lampas.haruhi.content.ContentTestSupport.bundleOf;
import static town.lampas.haruhi.content.ContentTestSupport.graph;
import static town.lampas.haruhi.content.ContentTestSupport.id;
import static town.lampas.haruhi.content.ContentTestSupport.node;
import static town.lampas.haruhi.content.ContentTestSupport.npc;
import static town.lampas.haruhi.content.ContentTestSupport.problems;
import static town.lampas.haruhi.content.ContentTestSupport.validate;

/**
 * Parsing and validation of bundle JSON. Expected values come from the format in AGENTS.md: the field rules, the
 * reference rules, reachability, and the requirement that every problem is reported in one error.
 */
class BundleValidatorTest {

    @BeforeAll
    static void boot() {
        ContentTestSupport.boot();
    }

    @Test
    void validBundleParsesIntoRecords() {
        DataResult<ValidatedBundle> result = validate(baseBundle());
        assertTrue(result.error().isEmpty(), () -> String.join("\n", problems(result)));
        ContentBundle bundle = bundleOf(result);

        assertEquals(ContentBundle.SCHEMA, bundle.schema());
        assertEquals(Set.of("seen"), bundle.flagNamespaces().get(GRAPH).flags());

        NpcProfile guard = bundle.npcs().get(id(NPC));
        assertEquals("Guard", guard.name().getString());
        assertEquals(id(TEXTURE), guard.texture());
        assertEquals(Optional.of(id(GRAPH)), guard.dialogue());
        assertEquals(Optional.empty(), guard.model());
        assertEquals(1.5f, guard.scale());

        DialogueGraph gate = bundle.dialogues().get(id(GRAPH));
        assertEquals("hello", gate.start());
        assertEquals(Set.of("hello", "bye"), gate.nodes().keySet());

        DialogueNode hello = gate.nodes().get("hello");
        assertEquals(Optional.of(id(NPC)), hello.speaker());
        assertEquals(Optional.of("Halt."), hello.text().map(Component::getString));
        assertFalse(hello.isRouter());

        ActionSpec setFlag = hello.onEnter().get(0);
        assertEquals(id("haruhi_test:set_flag"), setFlag.type());
        assertEquals("seen", setFlag.args().get("flag").getAsString());
        assertFalse(setFlag.args().has("type"));

        DialogueChoice pass = hello.choices().get(0);
        assertEquals(Optional.of("Pass"), pass.text().map(Component::getString));
        assertEquals(Optional.of("bye"), pass.next());
        assertEquals(DialogueChoice.Unavailable.HIDDEN, pass.whenUnavailable());
        assertEquals(1, pass.onlyIf().size());
        assertEquals(id("haruhi_test:flag_check"), pass.onlyIf().get(0).type());
        assertFalse(pass.onlyIf().get(0).args().has("type"));

        assertTrue(result.result().orElseThrow().digest().matches("[0-9a-f]{64}"));
    }

    @Test
    void scaleDefaultsToOne() {
        JsonObject bundle = baseBundle();
        npc(bundle).remove("scale");
        assertEquals(1.0f, bundleOf(validate(bundle)).npcs().get(id(NPC)).scale());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "4.5"})
    void scaleOutsideZeroToFourIsRejected(String scale) {
        JsonObject bundle = baseBundle();
        npc(bundle).add("scale", JsonParser.parseString(scale));
        assertTrue(problems(validate(bundle)).contains(
                "npc " + NPC + ": scale must be above 0 and at most 4"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.5", "4"})
    void scaleInsideZeroToFourIsAccepted(String scale) {
        JsonObject bundle = baseBundle();
        npc(bundle).add("scale", JsonParser.parseString(scale));
        assertTrue(validate(bundle).error().isEmpty());
    }

    @Test
    void npcNameIsRequired() {
        JsonObject bundle = baseBundle();
        npc(bundle).remove("name");
        assertEquals(List.of("npc " + NPC + ": name is required"), problems(validate(bundle)));
    }

    @Test
    void npcTextureIsRequired() {
        JsonObject bundle = baseBundle();
        npc(bundle).remove("texture");
        assertEquals(List.of("npc " + NPC + ": texture is required"), problems(validate(bundle)));
    }

    @Test
    void npcNameMayBeAComponentObject() {
        JsonObject bundle = baseBundle();
        npc(bundle).add("name", JsonParser.parseString("{\"text\":\"Guard\",\"color\":\"red\"}"));
        assertEquals("Guard", bundleOf(validate(bundle)).npcs().get(id(NPC)).name().getString());
    }

    @Test
    void npcDialogueMustBeAGraphInTheBundle() {
        JsonObject bundle = baseBundle();
        npc(bundle).addProperty("dialogue", "haruhi_test:missing");
        assertEquals(List.of("npc " + NPC + ": dialogue 'haruhi_test:missing' is not a dialogue in this bundle"),
                problems(validate(bundle)));
    }

    @Test
    void speakerMustBeAnNpcInTheBundle() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").addProperty("speaker", "haruhi_test:ghost");
        assertEquals(List.of(GRAPH + "#hello: speaker 'haruhi_test:ghost' is not an NPC in this bundle"),
                problems(validate(bundle)));
    }

    @Test
    void routerNodeHasNoTextOrChoices() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").add("random", ContentTestSupport.array("[{\"node\":\"bye\",\"weight\":1}]"));
        List<String> lines = problems(validate(bundle));
        assertTrue(lines.contains(GRAPH + "#hello: a router node has no text"), lines::toString);
        assertTrue(lines.contains(GRAPH + "#hello: a router node has no choices"), lines::toString);
    }

    @Test
    void routerBranchWeightMustBeAboveZero() {
        JsonObject bundle = baseBundle();
        addNode(bundle, "pick", "{\"random\":[{\"node\":\"bye\",\"weight\":0}]}");
        List<String> lines = problems(validate(bundle));
        assertTrue(lines.contains(GRAPH + "#pick, random entry 1: weight must be above 0"), lines::toString);
    }

    @Test
    void routerBranchMustNameANode() {
        JsonObject bundle = baseBundle();
        addNode(bundle, "pick", "{\"random\":[{\"node\":\"nowhere\",\"weight\":1}]}");
        List<String> lines = problems(validate(bundle));
        assertTrue(lines.contains(GRAPH + "#pick, random entry 1: node 'nowhere' is not a node in this graph"),
                lines::toString);
    }

    @Test
    void routerNeedsAtLeastOneBranch() {
        JsonObject bundle = baseBundle();
        addNode(bundle, "pick", "{\"random\":[]}");
        assertTrue(problems(validate(bundle)).contains(GRAPH + "#pick: random lists no branches"));
    }

    @Test
    void nodeWithoutRandomNeedsText() {
        JsonObject bundle = baseBundle();
        node(bundle, "bye").remove("text");
        assertEquals(List.of(GRAPH + "#bye: text is required"), problems(validate(bundle)));
    }

    @Test
    void choiceNeedsTextOrProvider() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject().remove("text");
        assertEquals(List.of(GRAPH + "#hello, choice 1: needs either text or provider"),
                problems(validate(bundle)));
    }

    @Test
    void whenUnavailableIsHiddenOrGrayedOut() {
        JsonObject bundle = baseBundle();
        JsonObject choice = node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject();
        choice.addProperty("when_unavailable", "grayed_out");
        assertEquals(DialogueChoice.Unavailable.GRAYED_OUT,
                bundleOf(validate(bundle)).dialogues().get(id(GRAPH)).nodes().get("hello").choices().get(0)
                        .whenUnavailable());

        choice.addProperty("when_unavailable", "shown");
        assertEquals(List.of(GRAPH + "#hello, choice 1: when_unavailable must be 'hidden' or 'grayed_out', not 'shown'"),
                problems(validate(bundle)));
    }

    @Test
    void choiceNextMustBeANode() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject().addProperty("next", "nowhere");
        assertEquals(List.of(GRAPH + "#hello, choice 1: next 'nowhere' is not a node in this graph"),
                problems(validate(bundle)));
    }

    @Test
    void nodeNextMustBeANode() {
        JsonObject bundle = baseBundle();
        node(bundle, "bye").addProperty("next", "nowhere");
        assertEquals(List.of(GRAPH + "#bye: next 'nowhere' is not a node in this graph"), problems(validate(bundle)));
    }

    @Test
    void startMustBeANode() {
        JsonObject bundle = baseBundle();
        graph(bundle).addProperty("start", "nowhere");
        assertTrue(problems(validate(bundle)).contains(GRAPH + ": start 'nowhere' is not a node in this graph"));
    }

    @Test
    void unreachableNodeIsRejected() {
        JsonObject bundle = baseBundle();
        addNode(bundle, "orphan", "{\"text\":\"Nobody comes here.\"}");
        assertEquals(List.of(GRAPH + "#orphan: node is not reachable from start 'hello'"),
                problems(validate(bundle)));
    }

    @Test
    void nodeReachedOnlyThroughARouterIsReachable() {
        JsonObject bundle = baseBundle();
        addNode(bundle, "route", "{\"random\":[{\"node\":\"bye\",\"weight\":1}]}");
        node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject().addProperty("next", "route");
        assertEquals(List.of(), problems(validate(bundle)));
    }

    @Test
    void nodeReachedOnlyThroughGotoIsReachable() {
        JsonObject bundle = baseBundle();
        addNode(bundle, "secret", "{\"text\":\"Psst.\"}");
        node(bundle, "hello").getAsJsonArray("on_enter")
                .add(JsonParser.parseString("{\"type\":\"haruhi:goto\",\"node\":\"secret\"}"));
        List<String> lines = problems(validate(bundle));
        // haruhi:goto is a built-in this test does not register, so its own problem is expected. The point of the test
        // is that the goto target is not reported as unreachable.
        assertFalse(lines.stream().anyMatch(line -> line.contains("not reachable")), lines::toString);
    }

    @Test
    void gotoTargetMustBeANode() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("on_enter")
                .add(JsonParser.parseString("{\"type\":\"haruhi:goto\",\"node\":\"nowhere\"}"));
        assertTrue(problems(validate(bundle)).contains(
                GRAPH + "#hello: goto node 'nowhere' is not a node in this graph"));
    }

    @Test
    void graphWithAProviderIsNotCheckedForReachability() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("choices")
                .add(JsonParser.parseString("{\"provider\":{\"type\":\"haruhi_test:provider\"}}"));
        addNode(bundle, "orphan", "{\"text\":\"Only a provider leads here.\"}");
        assertEquals(List.of(), problems(validate(bundle)));
    }

    @Test
    void aBrokenNodeDoesNotMakeItsNeighbourLookDangling() {
        JsonObject bundle = baseBundle();
        node(bundle, "bye").remove("text");
        // hello's choice points at bye, which failed to parse. That reference is valid, so only bye's own problem shows.
        assertEquals(List.of(GRAPH + "#bye: text is required"), problems(validate(bundle)));
    }

    @Test
    void unregisteredConditionNamesTheId() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonArray("only_if")
                .add(JsonParser.parseString("{\"type\":\"haruhi_test:missing\"}"));
        assertEquals(List.of(GRAPH + "#hello, choice 1: condition haruhi_test:missing is not registered"),
                problems(validate(bundle)));
    }

    @Test
    void unregisteredActionNamesTheId() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("on_enter")
                .add(JsonParser.parseString("{\"type\":\"haruhi_test:missing_action\"}"));
        assertEquals(List.of(GRAPH + "#hello: action haruhi_test:missing_action is not registered"),
                problems(validate(bundle)));
    }

    @Test
    void unregisteredProviderNamesTheId() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("choices")
                .add(JsonParser.parseString("{\"provider\":{\"type\":\"haruhi_test:missing_provider\"}}"));
        assertEquals(List.of(GRAPH + "#hello, choice 2: choice provider haruhi_test:missing_provider is not registered"),
                problems(validate(bundle)));
    }

    @Test
    void undeclaredFlagIsRefusedByTheTypeWithoutADoublePrefix() {
        JsonObject bundle = baseBundle();
        node(bundle, "hello").getAsJsonArray("on_enter").get(0).getAsJsonObject().addProperty("flag", "nope");
        assertEquals(List.of(GRAPH + "#hello: flag haruhi_test:gate/nope is not declared"),
                problems(validate(bundle)));
    }

    @Test
    void aTypeErrorWithoutItsOwnLocationGetsOne() {
        JsonObject bundle = baseBundle();
        JsonObject first = node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonArray("only_if").get(0).getAsJsonObject();
        first.addProperty("type", "haruhi_test:all_of");
        first.add("conditions", JsonParser.parseString("\"nope\""));
        // The all_of type's message does not start with graph#node, so the validator adds the choice's location.
        assertEquals(List.of(GRAPH + "#hello, choice 1: conditions must be an array"), problems(validate(bundle)));
    }

    @Test
    void nestedSpecsAreLeftToTheirOwnType() {
        JsonObject bundle = baseBundle();
        JsonObject first = node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonArray("only_if").get(0).getAsJsonObject();
        first.addProperty("type", "haruhi_test:all_of");
        first.add("conditions", JsonParser.parseString("[{\"type\":\"haruhi_test:not_registered_anywhere\"}]"));
        assertEquals(List.of(), problems(validate(bundle)));
    }

    @Test
    void everyProblemIsReportedInOneError() {
        JsonObject bundle = baseBundle();
        npc(bundle).remove("name");
        node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonArray("only_if")
                .add(JsonParser.parseString("{\"type\":\"haruhi_test:missing\"}"));
        addNode(bundle, "orphan", "{\"text\":\"Nobody comes here.\"}");

        List<String> lines = problems(validate(bundle));
        assertEquals(3, lines.size(), lines::toString);
        assertEquals(Set.of(
                        "npc " + NPC + ": name is required",
                        GRAPH + "#hello, choice 1: condition haruhi_test:missing is not registered",
                        GRAPH + "#orphan: node is not reachable from start 'hello'"),
                Set.copyOf(lines));
    }

    @Test
    void schemaIsRequiredAndMustBeOne() {
        JsonObject missing = baseBundle();
        missing.remove("schema");
        assertEquals(List.of("bundle: schema is required"), problems(validate(missing)));

        JsonObject wrong = baseBundle();
        wrong.addProperty("schema", 2);
        assertEquals(List.of("bundle: schema 2 is not supported, this build reads schema 1"),
                problems(validate(wrong)));
    }

    @Test
    void invalidJsonIsRefused() {
        List<String> lines = problems(validate("{"));
        assertEquals(1, lines.size(), lines::toString);
        assertTrue(lines.get(0).startsWith("bundle: not valid JSON"), lines::toString);
    }

    @Test
    void rootMustBeAnObject() {
        assertEquals(List.of("bundle: must be a JSON object"), problems(validate("[]")));
    }

    @Test
    void omittedSectionsAreEmpty() {
        ContentBundle bundle = bundleOf(validate("{\"schema\":1}"));
        assertTrue(bundle.npcs().isEmpty());
        assertTrue(bundle.dialogues().isEmpty());
        assertTrue(bundle.flagNamespaces().isEmpty());
    }

    @Test
    void flagNamespaceKeyMustBeAnId() {
        JsonObject bundle = baseBundle();
        bundle.add("flags", JsonParser.parseString("{\"Bad Key\":[\"x\"],\"haruhi_test:gate\":[\"seen\"]}"));
        assertEquals(List.of("bundle: flags key 'Bad Key' is not a valid id"), problems(validate(bundle)));
    }

    @Test
    void flagNamespaceValueMustBeAnArrayOfNames() {
        JsonObject bundle = baseBundle();
        bundle.add("flags", JsonParser.parseString("{\"haruhi_test:gate\":\"seen\"}"));
        List<String> lines = problems(validate(bundle));
        assertTrue(lines.contains("bundle: flags 'haruhi_test:gate' must be an array of flag names"), lines::toString);
    }

    @Test
    void digestIsStableUnderKeyOrderAndWhitespace() {
        JsonObject bundle = baseBundle();
        String compact = bundle.toString();
        String reordered = ContentTestSupport.reverseKeys(bundle).toString();
        String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(ContentTestSupport.reverseKeys(bundle));
        assertFalse(compact.equals(pretty));

        ValidatedBundle first = validate(compact).result().orElseThrow();
        ValidatedBundle second = validate(reordered).result().orElseThrow();
        ValidatedBundle third = validate(pretty).result().orElseThrow();
        assertEquals(first.digest(), second.digest());
        assertEquals(first.digest(), third.digest());
        assertEquals(first.canonicalJson(), third.canonicalJson());

        JsonObject changed = baseBundle();
        npc(changed).addProperty("name", "Gatekeeper");
        assertFalse(first.digest().equals(validate(changed).result().orElseThrow().digest()));
    }
}
