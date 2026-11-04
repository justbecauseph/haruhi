package town.lampas.haruhi.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.api.ChoiceProvider;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.storage.FlagKey;

/**
 * Shared setup for the content unit tests: Minecraft bootstrapped for component parsing, the test-only types registered
 * under {@code haruhi_test}, and a valid base bundle that each test changes.
 *
 * <p>The built-in {@code haruhi:} types are not registered here. The dialogue component registers them in the real game,
 * and it may share this JVM under test, so registering a built-in id here could collide with it.
 */
final class ContentTestSupport {
    static final String NPC = "haruhi_test:guard";
    static final String GRAPH = "haruhi_test:gate";
    static final String TEXTURE = "minecraft:textures/entity/player/wide/steve.png";

    private static boolean ready;

    private ContentTestSupport() {}

    static synchronized void boot() {
        if (ready) {
            return;
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        registerTypes();
        ready = true;
    }

    /** A valid bundle: one NPC, one graph with a flag check, a set flag action and a provider-free choice. */
    static JsonObject baseBundle() {
        return JsonParser.parseString("""
                {
                  "schema": 1,
                  "flags": { "haruhi_test:gate": ["seen"] },
                  "npcs": {
                    "haruhi_test:guard": {
                      "name": "Guard",
                      "texture": "minecraft:textures/entity/player/wide/steve.png",
                      "dialogue": "haruhi_test:gate",
                      "scale": 1.5
                    }
                  },
                  "dialogues": {
                    "haruhi_test:gate": {
                      "start": "hello",
                      "nodes": {
                        "hello": {
                          "speaker": "haruhi_test:guard",
                          "text": "Halt.",
                          "on_enter": [ { "type": "haruhi_test:set_flag", "namespace": "haruhi_test:gate", "flag": "seen" } ],
                          "choices": [
                            {
                              "text": "Pass",
                              "next": "bye",
                              "only_if": [ { "type": "haruhi_test:flag_check", "namespace": "haruhi_test:gate", "flag": "seen" } ],
                              "actions": [ { "type": "haruhi_test:noop" } ]
                            }
                          ]
                        },
                        "bye": { "text": "Go on." }
                      }
                    }
                  }
                }
                """).getAsJsonObject();
    }

    static JsonObject graph(JsonObject bundle) {
        return bundle.getAsJsonObject("dialogues").getAsJsonObject(GRAPH);
    }

    static JsonObject nodes(JsonObject bundle) {
        return graph(bundle).getAsJsonObject("nodes");
    }

    static JsonObject node(JsonObject bundle, String id) {
        return nodes(bundle).getAsJsonObject(id);
    }

    static JsonObject npc(JsonObject bundle) {
        return bundle.getAsJsonObject("npcs").getAsJsonObject(NPC);
    }

    /** Adds a node to the base graph; {@code json} is the node's object text. */
    static void addNode(JsonObject bundle, String id, String json) {
        nodes(bundle).add(id, JsonParser.parseString(json));
    }

    static DataResult<ValidatedBundle> validate(JsonObject bundle) {
        return BundleValidator.validate(bundle.toString(), JsonOps.INSTANCE);
    }

    static DataResult<ValidatedBundle> validate(String json) {
        return BundleValidator.validate(json, JsonOps.INSTANCE);
    }

    /** The problem lines of a failed result, or none when it succeeded. */
    static List<String> problems(DataResult<?> result) {
        return result.error().map(error -> error.message().lines().toList()).orElse(List.of());
    }

    static JsonArray array(String json) {
        return JsonParser.parseString(json).getAsJsonArray();
    }

    /** The same JSON with every object's keys in reverse order, at every depth. Array order is kept. */
    static JsonObject reverseKeys(JsonObject object) {
        List<String> keys = new ArrayList<>(object.keySet());
        Collections.reverse(keys);
        JsonObject reversed = new JsonObject();
        for (String key : keys) {
            reversed.add(key, reverseKeys(object.get(key)));
        }
        return reversed;
    }

    private static JsonElement reverseKeys(JsonElement element) {
        if (element.isJsonObject()) {
            return reverseKeys(element.getAsJsonObject());
        }
        if (element.isJsonArray()) {
            JsonArray reversed = new JsonArray();
            for (JsonElement entry : element.getAsJsonArray()) {
                reversed.add(reverseKeys(entry));
            }
            return reversed;
        }
        return element;
    }

    static JsonElement element(String json) {
        return JsonParser.parseString(json);
    }

    static ContentBundle bundleOf(DataResult<ValidatedBundle> result) {
        return result.result().orElseThrow().bundle();
    }

    static Identifier id(String text) {
        return Identifier.parse(text);
    }

    private static void registerTypes() {
        if (DialogueRegistries.condition(id("haruhi_test:always")).isEmpty()) {
            DialogueRegistries.registerCondition(id("haruhi_test:always"), (args, context) -> {
                DialogueCondition always = ctx -> true;
                return DataResult.success(always);
            });
        }
        if (DialogueRegistries.condition(id("haruhi_test:flag_check")).isEmpty()) {
            DialogueRegistries.registerCondition(id("haruhi_test:flag_check"), (args, context) ->
                    declaredFlag(args, context).map(key -> (DialogueCondition) ctx -> true));
        }
        if (DialogueRegistries.condition(id("haruhi_test:all_of")).isEmpty()) {
            // Owns nested specs: it checks only that the list exists, and never parses the entries itself.
            DialogueRegistries.registerCondition(id("haruhi_test:all_of"), (args, context) -> {
                JsonElement conditions = args.get("conditions");
                if (conditions == null || !conditions.isJsonArray()) {
                    return DataResult.error(() -> "conditions must be an array");
                }
                DialogueCondition all = ctx -> true;
                return DataResult.success(all);
            });
        }
        if (DialogueRegistries.action(id("haruhi_test:noop")).isEmpty()) {
            DialogueRegistries.registerAction(id("haruhi_test:noop"), (args, context) ->
                    DataResult.success(DialogueAction.NONE));
        }
        if (DialogueRegistries.action(id("haruhi_test:set_flag")).isEmpty()) {
            DialogueRegistries.registerAction(id("haruhi_test:set_flag"), (args, context) ->
                    declaredFlag(args, context).map(key -> DialogueAction.NONE));
        }
        if (DialogueRegistries.provider(id("haruhi_test:provider")).isEmpty()) {
            DialogueRegistries.registerProvider(id("haruhi_test:provider"), (args, context) -> {
                ChoiceProvider provider = ctx -> List.of();
                return DataResult.success(provider);
            });
        }
    }

    /** Parses {@code namespace} and {@code flag} and checks the bundle declares them. */
    private static DataResult<FlagKey> declaredFlag(JsonObject args, ParseContext context) {
        JsonElement namespace = args.get("namespace");
        JsonElement flag = args.get("flag");
        if (namespace == null || flag == null || !namespace.isJsonPrimitive() || !flag.isJsonPrimitive()) {
            return DataResult.error(() -> context.where() + "needs namespace and flag");
        }
        FlagKey key = new FlagKey(namespace.getAsString(), flag.getAsString());
        if (!context.isFlagDeclared(key)) {
            return DataResult.error(() -> context.where()
                    + "flag " + key.namespace() + "/" + key.flag() + " is not declared");
        }
        return DataResult.success(key);
    }
}
