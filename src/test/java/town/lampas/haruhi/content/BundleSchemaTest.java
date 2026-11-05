package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.haruhi.content.ContentTestSupport.addNode;
import static town.lampas.haruhi.content.ContentTestSupport.baseBundle;
import static town.lampas.haruhi.content.ContentTestSupport.element;
import static town.lampas.haruhi.content.ContentTestSupport.graph;
import static town.lampas.haruhi.content.ContentTestSupport.node;
import static town.lampas.haruhi.content.ContentTestSupport.npc;
import static town.lampas.haruhi.content.ContentTestSupport.problems;
import static town.lampas.haruhi.content.ContentTestSupport.validate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import town.lampas.haruhi.BundleSchema;

/**
 * Keeps {@code docs/bundle.schema.json} in step with the bundle reader. The schema must name exactly the keys the
 * reader accepts, accept every bundle the repository ships or documents, and agree with the validator on the shape
 * rules both of them check. Rules only the validator can check (references, reachability, flag declarations) are left
 * out of the comparison.
 */
class BundleSchemaTest {
    private static final Pattern JSON_BLOCK = Pattern.compile("```json\\R(.*?)```", Pattern.DOTALL);

    private static BundleSchema schema;

    @BeforeAll
    static void boot() {
        ContentTestSupport.boot();
        schema = BundleSchema.load();
    }

    @Test
    void schemaIsValidDraft202012() {
        List<String> errors = schema.metaSchemaErrors();
        assertTrue(errors.isEmpty(), () -> String.join("\n", errors));
    }

    @Test
    void schemaNamesExactlyTheKeysTheReaderAccepts() {
        assertAll(
                () -> assertEquals(sorted(BundleReader.BUNDLE_KEYS), BundleSchema.propertyNames(schema.json()), "bundle"),
                () -> assertEquals(sorted(BundleReader.NPC_KEYS), properties("npc"), "npc"),
                () -> assertEquals(sorted(GraphReader.GRAPH_KEYS), properties("graph"), "graph"),
                () -> assertEquals(sorted(NodeReader.NODE_KEYS), properties("node"), "node"),
                () -> assertEquals(sorted(NodeReader.CHOICE_KEYS), properties("choice"), "choice"),
                () -> assertEquals(sorted(NodeReader.BRANCH_KEYS), properties("branch"), "router branch"));
    }

    @Test
    void schemaAcceptsEveryBundleTheRepositoryShipsOrDocuments() throws IOException {
        List<Path> files = new ArrayList<>();
        files.add(Path.of("src", "main", "resources", "haruhi", "seed_bundle.json"));
        try (Stream<Path> fixtures = Files.walk(Path.of("src", "gametest", "resources", "haruhi", "fixtures"))) {
            fixtures.filter(file -> file.toString().endsWith(".json")).sorted().forEach(files::add);
        }
        List<Executable> checks = new ArrayList<>();
        for (Path file : files) {
            JsonElement bundle = JsonParser.parseString(BundleSchema.read(file));
            checks.add(() -> assertAccepted(file.toString(), bundle));
        }

        List<Path> pages = new ArrayList<>(List.of(Path.of("AGENTS.md"), Path.of("README.md")));
        try (Stream<Path> docs = Files.list(Path.of("docs"))) {
            docs.filter(file -> file.toString().endsWith(".md")).sorted().forEach(pages::add);
        }
        int documented = 0;
        for (Path page : pages) {
            Matcher block = JSON_BLOCK.matcher(BundleSchema.read(page));
            int index = 0;
            while (block.find()) {
                index++;
                JsonElement json = parseOrNull(block.group(1));
                if (json != null && json.isJsonObject() && json.getAsJsonObject().has("schema")) {
                    documented++;
                    String name = page + " json block " + index;
                    checks.add(() -> assertAccepted(name, json));
                }
            }
        }

        assertTrue(files.size() >= 4, "the seed and the GameTest fixtures are found: " + files);
        assertTrue(documented >= 2, "the AGENTS.md example and the content guide's worked example are found");
        assertAll(checks.stream());
    }

    /** Changes to the base bundle, each with whether the format allows it. */
    static Stream<Arguments> shapeCases() {
        return Stream.of(
                valid("the base bundle", bundle -> {}),
                valid("a router as the start node", BundleSchemaTest::routerStart),
                valid("text as an array of components", bundle ->
                        node(bundle, "bye").add("text", element("[\"\", {\"text\": \"Go on.\", \"color\": \"gold\"}]"))),
                valid("a grayed-out choice with a message", bundle -> {
                    choice(bundle).addProperty("when_unavailable", "grayed_out");
                    choice(bundle).addProperty("unavailable_message", "Not yet.");
                }),
                valid("a registered extension section", bundle ->
                        bundle.add("extensions", element("{\"haruhi_test:ext\": {\"ids\": [\"a\"]}}"))),
                valid("scale 4", bundle -> npc(bundle).addProperty("scale", 4)),
                valid("scale 0.01", bundle -> npc(bundle).addProperty("scale", 0.01)),
                valid("a provider choice", bundle ->
                        choices(bundle).add(element("{\"provider\": {\"type\": \"haruhi_test:provider\"}}"))),

                invalid("a $schema key", bundle -> bundle.addProperty("$schema", "./bundle.schema.json")),
                invalid("schema 2", bundle -> bundle.addProperty("schema", 2)),
                invalid("no schema", bundle -> bundle.remove("schema")),
                invalid("an unknown top-level key", bundle -> bundle.add("npc", new JsonObject())),
                invalid("flags as an array", bundle -> bundle.add("flags", new JsonArray())),
                invalid("extensions as an array", bundle -> bundle.add("extensions", new JsonArray())),
                invalid("an empty flag name", bundle -> flagNames(bundle).add("")),
                invalid("a flag name with a NUL", bundle -> flagNames(bundle).add("a\u0000b")),
                invalid("a flag namespace key that is not an id", bundle ->
                        bundle.getAsJsonObject("flags").add("Bad Namespace", new JsonArray())),
                invalid("an NPC without name", bundle -> npc(bundle).remove("name")),
                invalid("an NPC without texture", bundle -> npc(bundle).remove("texture")),
                invalid("an unknown NPC key", bundle -> npc(bundle).addProperty("skin", "x")),
                invalid("scale 0", bundle -> npc(bundle).addProperty("scale", 0)),
                invalid("scale 5", bundle -> npc(bundle).addProperty("scale", 5)),
                invalid("scale as a string", bundle -> npc(bundle).addProperty("scale", "big")),
                invalid("a texture that is not an id", bundle -> npc(bundle).addProperty("texture", "Steve.png")),
                invalid("an NPC key that is not an id", bundle ->
                        bundle.getAsJsonObject("npcs").add("Bad Key", npc(bundle).deepCopy())),
                invalid("a graph without start", bundle -> graph(bundle).remove("start")),
                invalid("a graph without nodes", bundle -> graph(bundle).remove("nodes")),
                invalid("an unknown graph key", bundle -> graph(bundle).addProperty("first", "hello")),
                invalid("a node without text", bundle -> node(bundle, "bye").remove("text")),
                invalid("an unknown node key", bundle -> node(bundle, "bye").add("onEnter", new JsonArray())),
                invalid("next as a number", bundle -> node(bundle, "hello").addProperty("next", 5)),
                invalid("speaker that is not an id", bundle -> node(bundle, "hello").addProperty("speaker", "Guard")),
                invalid("on_enter as an object", bundle -> node(bundle, "hello").add("on_enter", new JsonObject())),
                invalid("text as an empty array", bundle -> node(bundle, "bye").add("text", new JsonArray())),
                invalid("a router with text", bundle -> {
                    routerStart(bundle);
                    node(bundle, "route").addProperty("text", "Hello.");
                }),
                invalid("a router with choices", bundle -> {
                    routerStart(bundle);
                    node(bundle, "route").add("choices", new JsonArray());
                }),
                invalid("a router with no branches", bundle -> {
                    routerStart(bundle);
                    node(bundle, "route").add("random", new JsonArray());
                }),
                invalid("a branch weight of 0", bundle -> {
                    routerStart(bundle);
                    branch(bundle).addProperty("weight", 0);
                }),
                invalid("a fractional branch weight", bundle -> {
                    routerStart(bundle);
                    branch(bundle).addProperty("weight", 1.5);
                }),
                invalid("a branch without weight", bundle -> {
                    routerStart(bundle);
                    branch(bundle).remove("weight");
                }),
                invalid("an unknown branch key", bundle -> {
                    routerStart(bundle);
                    branch(bundle).addProperty("chance", 1);
                }),
                invalid("a choice with neither text nor provider", bundle -> choice(bundle).remove("text")),
                invalid("an unknown choice key", bundle -> choice(bundle).add("onlyIf", new JsonArray())),
                invalid("when_unavailable greyed", bundle -> choice(bundle).addProperty("when_unavailable", "greyed")),
                invalid("only_if as an object", bundle -> choice(bundle).add("only_if", new JsonObject())),
                invalid("an action without type", bundle -> actions(bundle).set(0, element("{\"node\": \"bye\"}"))),
                invalid("a type that is not an id", bundle ->
                        actions(bundle).set(0, element("{\"type\": \"Not An Id\"}"))),
                invalid("an action that is not an object", bundle -> actions(bundle).set(0, element("\"noop\""))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapeCases")
    void schemaAndValidatorAgreeOnShape(String name, Consumer<JsonObject> change, boolean allowed) {
        JsonObject bundle = baseBundle();
        change.accept(bundle);

        List<String> validatorProblems = problems(validate(bundle));
        List<String> schemaErrors = schema.errors(bundle);
        if (allowed) {
            assertAll(
                    () -> assertTrue(validatorProblems.isEmpty(), () -> "validator: " + validatorProblems),
                    () -> assertTrue(schemaErrors.isEmpty(), () -> "schema: " + schemaErrors));
        } else {
            assertAll(
                    () -> assertFalse(validatorProblems.isEmpty(), "the validator accepts it"),
                    () -> assertFalse(schemaErrors.isEmpty(), "the schema accepts it"));
        }
    }

    private static Arguments valid(String name, Consumer<JsonObject> change) {
        return Arguments.of(name, change, true);
    }

    private static Arguments invalid(String name, Consumer<JsonObject> change) {
        return Arguments.of(name, change, false);
    }

    /** Puts a router in front of the base graph's start node. */
    private static void routerStart(JsonObject bundle) {
        addNode(bundle, "route", "{\"random\": [{\"node\": \"hello\", \"weight\": 1}]}");
        graph(bundle).addProperty("start", "route");
    }

    private static JsonObject branch(JsonObject bundle) {
        return node(bundle, "route").getAsJsonArray("random").get(0).getAsJsonObject();
    }

    private static JsonArray choices(JsonObject bundle) {
        return node(bundle, "hello").getAsJsonArray("choices");
    }

    private static JsonObject choice(JsonObject bundle) {
        return choices(bundle).get(0).getAsJsonObject();
    }

    private static JsonArray actions(JsonObject bundle) {
        return choice(bundle).getAsJsonArray("actions");
    }

    private static JsonArray flagNames(JsonObject bundle) {
        return bundle.getAsJsonObject("flags").getAsJsonArray(ContentTestSupport.GRAPH);
    }

    private void assertAccepted(String name, JsonElement bundle) {
        List<String> errors = schema.errors(bundle);
        assertTrue(errors.isEmpty(), () -> name + ":\n" + String.join("\n", errors));
    }

    private Set<String> properties(String definition) {
        return BundleSchema.propertyNames(schema.definition(definition));
    }

    private static Set<String> sorted(Set<String> keys) {
        return new TreeSet<>(keys);
    }

    private static JsonElement parseOrNull(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
