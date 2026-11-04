package town.lampas.haruhi.dialogue.builtin;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import town.lampas.haruhi.BundleSchema;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.FlagNamespace;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * Keeps the built-in conditions and actions in {@code docs/bundle.schema.json} in step with their parsers: the schema
 * lists exactly the registered {@code haruhi:} ids, gives each a definition, and accepts or refuses each argument case
 * below as the parser does. The expected outcomes come from the built-ins table in AGENTS.md. The cases leave out what
 * only the validator can check: flag declarations, the command allowlist and graph and node references.
 */
class BuiltinSchemaTest {
    private static final String NAMESPACE = "haruhi:test/npc";
    private static final Identifier OTHER = Identifier.fromNamespaceAndPath("haruhi", "test/other");
    private static final ParseContext CONTEXT = new ParseContext(
            new ContentBundle(ContentBundle.SCHEMA,
                    Map.of(NAMESPACE, new FlagNamespace(NAMESPACE, Set.of("met", "count"))),
                    Map.of(), Map.of(OTHER, new DialogueGraph(OTHER, "start", Map.of()))),
            Identifier.fromNamespaceAndPath("haruhi", "test/graph"),
            "start");

    private static BundleSchema schema;

    @BeforeAll
    static void boot() {
        TestBuiltins.ensureBootstrapped();
        TestBuiltins.ensureRegistered();
        schema = BundleSchema.load();
    }

    @Test
    void schemaListsExactlyTheRegisteredConditions() throws ReflectiveOperationException {
        assertEquals(registeredHaruhiIds("CONDITIONS"), schemaIds("condition"));
    }

    @Test
    void schemaListsExactlyTheRegisteredActions() throws ReflectiveOperationException {
        assertEquals(registeredHaruhiIds("ACTIONS"), schemaIds("action"));
    }

    @Test
    void everyListedBuiltinHasADefinition() {
        assertAll(Stream.of("condition", "action").map(kind -> () ->
                assertEquals(schemaIds(kind), definedIds(kind), kind + " ids with an if/then definition")));
    }

    static Stream<Arguments> argumentCases() {
        return Stream.of(
                condition(true, "{\"type\": \"haruhi:all_of\", \"conditions\": [{\"type\": \"haruhi:weather\", \"weather\": \"clear\"}]}"),
                condition(false, "{\"type\": \"haruhi:all_of\", \"conditions\": []}"),
                condition(false, "{\"type\": \"haruhi:all_of\"}"),
                condition(false, "{\"type\": \"haruhi:all_of\", \"conditions\": [{\"type\": \"haruhi:weather\", \"weather\": \"snow\"}]}"),
                condition(false, "{\"type\": \"haruhi:all_of\", \"conditions\": [{\"type\": \"haruhi:tag\", \"tag\": \"a\"}], \"mode\": \"all\"}"),
                condition(true, "{\"type\": \"haruhi:any_of\", \"conditions\": [{\"type\": \"haruhi:tag\", \"tag\": \"a\"}, {\"type\": \"haruhi:tag\", \"tag\": \"b\"}]}"),
                condition(false, "{\"type\": \"haruhi:any_of\", \"conditions\": {\"type\": \"haruhi:tag\", \"tag\": \"a\"}}"),
                condition(true, "{\"type\": \"haruhi:not\", \"condition\": {\"type\": \"haruhi:tag\", \"tag\": \"banned\"}}"),
                condition(false, "{\"type\": \"haruhi:not\", \"condition\": [{\"type\": \"haruhi:tag\", \"tag\": \"banned\"}]}"),
                condition(false, "{\"type\": \"haruhi:not\", \"condition\": {\"type\": \"haruhi:permission\", \"level\": 9}}"),

                condition(true, flag("\"op\": \"set\"")),
                condition(true, flag("\"op\": \"unset\"")),
                condition(false, flag("\"op\": \"set\", \"value\": 1")),
                condition(true, flag("\"op\": \"gte\", \"value\": 5")),
                condition(true, flag("\"op\": \"lt\", \"value\": -3")),
                condition(false, flag("\"op\": \"gte\"")),
                condition(false, flag("\"op\": \"is_set\"")),
                condition(false, flag("\"op\": \"eq\", \"value\": 1.5")),
                condition(false, flag("\"op\": \"eq\", \"value\": \"1\"")),
                condition(false, "{\"type\": \"haruhi:flag\", \"namespace\": \"haruhi:test/npc\", \"op\": \"set\"}"),
                condition(true, "{\"type\": \"haruhi:visits\", \"op\": \"eq\", \"value\": 1}"),
                condition(false, "{\"type\": \"haruhi:visits\", \"op\": \"set\", \"value\": 1}"),
                condition(false, "{\"type\": \"haruhi:visits\", \"op\": \"gt\"}"),

                condition(true, "{\"type\": \"haruhi:predicate\", \"predicate\": \"shop:in_market\"}"),
                condition(false, "{\"type\": \"haruhi:predicate\", \"predicate\": \"In Market\"}"),
                condition(false, "{\"type\": \"haruhi:predicate\"}"),
                condition(true, "{\"type\": \"haruhi:has_item\", \"item\": \"minecraft:emerald\"}"),
                condition(true, "{\"type\": \"haruhi:has_item\", \"item\": \"#minecraft:logs\", \"count\": 3}"),
                condition(false, "{\"type\": \"haruhi:has_item\", \"item\": \"minecraft:emerald\", \"count\": 0}"),
                condition(false, "{\"type\": \"haruhi:has_item\", \"item\": \"Emerald\"}"),
                condition(true, "{\"type\": \"haruhi:advancement\", \"advancement\": \"minecraft:story/mine_diamond\"}"),
                condition(true, "{\"type\": \"haruhi:advancement\", \"advancement\": \"minecraft:story/mine_diamond\", \"criterion\": \"diamond\"}"),
                condition(false, "{\"type\": \"haruhi:advancement\", \"advancement\": \"minecraft:story/mine_diamond\", \"criterion\": \"\"}"),
                condition(true, "{\"type\": \"haruhi:tag\", \"tag\": \"guild_member\"}"),
                condition(false, "{\"type\": \"haruhi:tag\", \"tag\": \"guild member\"}"),
                condition(false, "{\"type\": \"haruhi:tag\", \"tag\": \"\"}"),
                condition(true, "{\"type\": \"haruhi:permission\", \"level\": 0}"),
                condition(true, "{\"type\": \"haruhi:permission\", \"level\": 4}"),
                condition(false, "{\"type\": \"haruhi:permission\", \"level\": 5}"),
                condition(false, "{\"type\": \"haruhi:permission\", \"level\": -1}"),
                condition(true, "{\"type\": \"haruhi:time\", \"from\": 13000, \"to\": 23000}"),
                condition(true, "{\"type\": \"haruhi:time\", \"from\": 22000, \"to\": 2000}"),
                condition(false, "{\"type\": \"haruhi:time\", \"from\": 0, \"to\": 24000}"),
                condition(false, "{\"type\": \"haruhi:time\", \"from\": 0}"),
                condition(true, "{\"type\": \"haruhi:weather\", \"weather\": \"thunder\"}"),
                condition(false, "{\"type\": \"haruhi:weather\", \"weather\": \"snow\"}"),
                condition(false, "{\"type\": \"haruhi:weather\", \"weather\": \"rain\", \"dimension\": \"minecraft:overworld\"}"),

                action(true, "{\"type\": \"haruhi:flag/set\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\"}"),
                action(true, "{\"type\": \"haruhi:flag/set\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\", \"value\": 5}"),
                action(false, "{\"type\": \"haruhi:flag/set\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\", \"val\": 5}"),
                action(false, "{\"type\": \"haruhi:flag/set\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\", \"value\": 2.5}"),
                action(false, "{\"type\": \"haruhi:flag/set\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\", \"value\": 2147483648}"),
                action(true, "{\"type\": \"haruhi:flag/add\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"count\", \"delta\": -1}"),
                action(false, "{\"type\": \"haruhi:flag/add\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"count\", \"delta\": \"one\"}"),
                action(true, "{\"type\": \"haruhi:flag/clear\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\"}"),
                action(false, "{\"type\": \"haruhi:flag/clear\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\", \"value\": 0}"),
                action(false, "{\"type\": \"haruhi:flag/clear\", \"namespace\": \"\", \"flag\": \"met\"}"),
                action(true, "{\"type\": \"haruhi:goto\", \"node\": \"menu\"}"),
                action(false, "{\"type\": \"haruhi:goto\", \"node\": \"\"}"),
                action(false, "{\"type\": \"haruhi:goto\"}"),
                action(true, "{\"type\": \"haruhi:end\"}"),
                action(false, "{\"type\": \"haruhi:end\", \"now\": true}"),
                action(true, "{\"type\": \"haruhi:open\", \"graph\": \"haruhi:test/other\"}"),
                action(false, "{\"type\": \"haruhi:open\", \"graph\": \"Other Graph\"}"),
                action(true, "{\"type\": \"haruhi:animation\", \"animation\": \"wave\"}"),
                action(false, "{\"type\": \"haruhi:animation\", \"animation\": \"\"}"),
                action(true, "{\"type\": \"haruhi:sound\", \"sound\": \"minecraft:entity.item.pickup\"}"),
                action(true, "{\"type\": \"haruhi:sound\", \"sound\": \"minecraft:entity.item.pickup\", \"volume\": 0, \"pitch\": 0.5}"),
                action(false, "{\"type\": \"haruhi:sound\", \"sound\": \"minecraft:entity.item.pickup\", \"pitch\": 0}"),
                action(false, "{\"type\": \"haruhi:sound\", \"sound\": \"minecraft:entity.item.pickup\", \"volume\": -1}"),
                action(false, "{\"type\": \"haruhi:sound\", \"sound\": \"Pickup\"}"),
                action(true, "{\"type\": \"haruhi:message\", \"text\": \"Mira hums.\"}"),
                action(true, "{\"type\": \"haruhi:message\", \"text\": {\"text\": \"Mira hums.\", \"italic\": true}}"),
                action(false, "{\"type\": \"haruhi:message\", \"text\": []}"),
                action(false, "{\"type\": \"haruhi:message\"}"),
                action(true, give("\"once\": {\"namespace\": \"haruhi:test/npc\", \"flag\": \"met\"}")),
                action(false, give("")),
                action(false, give("\"once\": {\"namespace\": \"haruhi:test/npc\", \"flag\": \"met\", \"value\": 1}")),
                action(false, give("\"once\": \"haruhi:test/npc\"")),
                action(false, "{\"type\": \"haruhi:give\", \"loot_table\": \"Chest\", \"once\": {\"namespace\": \"haruhi:test/npc\", \"flag\": \"met\"}}"),
                action(true, "{\"type\": \"haruhi:command\", \"command\": \"say hi\"}"),
                action(true, "{\"type\": \"haruhi:command\", \"command\": \"/give @s minecraft:apple\"}"),
                action(false, "{\"type\": \"haruhi:command\", \"command\": \" / \"}"),
                action(false, "{\"type\": \"haruhi:command\"}"));
    }

    @ParameterizedTest(name = "{0} {2}")
    @MethodSource("argumentCases")
    void schemaAndParserAgree(String kind, boolean allowed, String spec) {
        JsonObject json = JsonParser.parseString(spec).getAsJsonObject();
        Identifier type = Identifier.parse(json.get("type").getAsString());
        JsonObject args = json.deepCopy();
        args.remove("type");

        DataResult<?> parsed = kind.equals("condition")
                ? DialogueRegistries.condition(type).orElseThrow().parse(args, CONTEXT)
                : DialogueRegistries.action(type).orElseThrow().parse(args, CONTEXT);
        List<String> schemaErrors = schema.errors(bundleWith(kind, json));

        assertAll(
                () -> assertEquals(allowed, parsed.error().isEmpty(),
                        () -> "parser: " + parsed.error().map(error -> error.message()).orElse("accepted")),
                () -> assertEquals(allowed, schemaErrors.isEmpty(), () -> "schema: " + schemaErrors));
    }

    private static Arguments condition(boolean allowed, String spec) {
        return Arguments.of("condition", allowed, spec);
    }

    private static Arguments action(boolean allowed, String spec) {
        return Arguments.of("action", allowed, spec);
    }

    private static String flag(String rest) {
        return "{\"type\": \"haruhi:flag\", \"namespace\": \"haruhi:test/npc\", \"flag\": \"met\", " + rest + "}";
    }

    private static String give(String once) {
        return "{\"type\": \"haruhi:give\", \"loot_table\": \"minecraft:chests/simple_dungeon\""
                + (once.isEmpty() ? "" : ", " + once) + "}";
    }

    /** A bundle that is valid by the schema except for {@code spec}, placed where a spec of its kind goes. */
    private static JsonObject bundleWith(String kind, JsonObject spec) {
        JsonObject choice = new JsonObject();
        choice.addProperty("text", "Go");
        JsonArray specs = new JsonArray();
        specs.add(spec);
        choice.add(kind.equals("condition") ? "only_if" : "actions", specs);
        JsonArray choices = new JsonArray();
        choices.add(choice);

        JsonObject node = new JsonObject();
        node.addProperty("text", "Hello.");
        node.add("choices", choices);
        JsonObject nodes = new JsonObject();
        nodes.add("start", node);
        JsonObject graph = new JsonObject();
        graph.addProperty("start", "start");
        graph.add("nodes", nodes);
        JsonObject dialogues = new JsonObject();
        dialogues.add("haruhi:test/graph", graph);

        JsonObject bundle = new JsonObject();
        bundle.addProperty("schema", 1);
        bundle.add("dialogues", dialogues);
        return bundle;
    }

    /** The ids in the {@code haruhi} namespace of one of {@link DialogueRegistries}' private maps. */
    private static Set<String> registeredHaruhiIds(String field) throws ReflectiveOperationException {
        Field map = DialogueRegistries.class.getDeclaredField(field);
        map.setAccessible(true);
        return ((Map<?, ?>) map.get(null)).keySet().stream()
                .map(id -> (Identifier) id)
                .filter(id -> id.getNamespace().equals("haruhi"))
                .map(Identifier::toString)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The {@code haruhi:} ids the schema allows for a spec kind: the enum in the first entry of its allOf. */
    private static Set<String> schemaIds(String kind) {
        JsonElement ids = allOf(kind).get(0).getAsJsonObject()
                .getAsJsonObject("then").getAsJsonObject("properties").getAsJsonObject("type").get("enum");
        return ids.getAsJsonArray().asList().stream()
                .map(JsonElement::getAsString)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The ids that have an if/then entry whose then names an existing definition. */
    private static Set<String> definedIds(String kind) {
        Set<String> ids = new TreeSet<>();
        for (JsonElement entry : allOf(kind).asList().subList(1, allOf(kind).size())) {
            JsonObject branch = entry.getAsJsonObject();
            String id = branch.getAsJsonObject("if").getAsJsonObject("properties")
                    .getAsJsonObject("type").get("const").getAsString();
            String ref = branch.getAsJsonObject("then").get("$ref").getAsString();
            String definition = ref.substring("#/$defs/".length());
            assertTrue(Optional.ofNullable(schema.json().getAsJsonObject("$defs").get(definition)).isPresent(),
                    () -> id + " refers to a missing definition " + ref);
            ids.add(id);
        }
        return ids;
    }

    private static JsonArray allOf(String kind) {
        return schema.definition(kind).getAsJsonArray("allOf");
    }
}
