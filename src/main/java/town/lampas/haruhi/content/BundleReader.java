package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.FlagNamespace;
import town.lampas.haruhi.content.model.NpcProfile;

/**
 * Reads a whole bundle object: the schema, the flag namespaces, the NPC profiles and the dialogue graphs. A broken
 * entry is left out of the result and its problems are recorded, so the caller can still check the rest.
 */
final class BundleReader {
    /** The keys a bundle object may have. Any other key is an error. */
    static final Set<String> BUNDLE_KEYS = Set.of("schema", "flags", "npcs", "dialogues");
    /** The keys an NPC profile may have. Any other key is an error. */
    static final Set<String> NPC_KEYS =
            Set.of("name", "model", "texture", "animations", "portrait", "dialogue", "scale");

    private BundleReader() {}

    /**
     * The bundle as far as it parsed, the ids of the graphs that parsed completely, and every condition, action and
     * provider that parsed, including those of nodes and graphs that failed. Graphs outside {@code completeGraphs} may
     * be missing nodes, so reachability cannot be decided for them.
     */
    record Read(ContentBundle bundle, Set<Identifier> completeGraphs, List<SpecSite> specs) {}

    static Read read(JsonObject root, DynamicOps<JsonElement> ops, Problems problems) {
        Fields bundle = new Fields(root, "bundle", problems);
        bundle.allowOnly(BUNDLE_KEYS);
        bundle.required("schema", JsonValues::integer)
                .filter(schema -> schema != ContentBundle.SCHEMA)
                .ifPresent(schema -> bundle.add("schema " + schema + " is not supported, this build reads schema "
                        + ContentBundle.SCHEMA));

        Map<String, FlagNamespace> flags = readFlags(entries(root, "flags", problems), problems);

        Map<String, JsonElement> npcEntries = entries(root, "npcs", problems);
        Map<String, JsonElement> graphEntries = entries(root, "dialogues", problems);
        Set<Identifier> npcIds = ids(npcEntries.keySet());
        Set<Identifier> graphIds = ids(graphEntries.keySet());

        Map<Identifier, NpcProfile> npcs = new LinkedHashMap<>();
        Map<Identifier, String> npcKeys = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : npcEntries.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null) {
                problems.add("bundle", "npcs key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            boolean first = claim(npcKeys, id, entry.getKey(), "npcs", problems);
            Optional<NpcProfile> npc = readNpc(id, entry.getValue(), graphIds, ops, problems);
            if (first) {
                npc.ifPresent(profile -> npcs.put(id, profile));
            }
        }

        List<SpecSite> specs = new ArrayList<>();
        GraphReader graphReader = new GraphReader(ops, problems, npcIds, specs);
        Map<Identifier, DialogueGraph> graphs = new LinkedHashMap<>();
        Map<Identifier, String> graphKeys = new HashMap<>();
        Set<Identifier> complete = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : graphEntries.entrySet()) {
            Identifier id = Identifier.tryParse(entry.getKey());
            if (id == null) {
                problems.add("bundle", "dialogues key '" + entry.getKey() + "' is not a valid id");
                continue;
            }
            boolean first = claim(graphKeys, id, entry.getKey(), "dialogues", problems);
            GraphReader.Read read = graphReader.read(id, entry.getValue());
            if (first) {
                read.graph().ifPresent(graph -> graphs.put(id, graph));
                if (read.complete()) {
                    complete.add(id);
                }
            }
        }

        ContentBundle partial = new ContentBundle(ContentBundle.SCHEMA, flags, npcs, graphs);
        return new Read(partial, Set.copyOf(complete), List.copyOf(specs));
    }

    /**
     * Records that {@code key} names {@code id}. When an earlier key of the same section already names it, records a
     * problem and returns false, so the later entry does not replace the earlier one.
     */
    private static boolean claim(
            Map<Identifier, String> claimed, Identifier id, String key, String section, Problems problems) {
        String earlier = claimed.putIfAbsent(id, key);
        if (earlier == null) {
            return true;
        }
        problems.add("bundle", section + " keys '" + earlier + "' and '" + key + "' both name " + id);
        return false;
    }

    /** The members of an object-valued section, or none when the section is absent or not an object. */
    private static Map<String, JsonElement> entries(JsonObject root, String key, Problems problems) {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return Map.of();
        }
        if (!value.isJsonObject()) {
            problems.add("bundle", key + " must be an object");
            return Map.of();
        }
        return value.getAsJsonObject().asMap();
    }

    private static Set<Identifier> ids(Set<String> keys) {
        return keys.stream().map(Identifier::tryParse).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static Map<String, FlagNamespace> readFlags(Map<String, JsonElement> entries, Problems problems) {
        Map<String, FlagNamespace> flags = new LinkedHashMap<>();
        Map<Identifier, String> claimed = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            String namespace = entry.getKey();
            Identifier id = Identifier.tryParse(namespace);
            if (id == null) {
                problems.add("bundle", "flags key '" + namespace + "' is not a valid id");
                continue;
            }
            boolean first = claim(claimed, id, namespace, "flags", problems);
            Optional<Set<String>> names = readFlagNames(namespace, entry.getValue(), problems);
            if (first) {
                names.ifPresent(set -> flags.put(namespace, new FlagNamespace(namespace, set)));
            }
        }
        return flags;
    }

    /**
     * The valid flag names of a namespace. Empty when the value is not an array at all. Each bad name is a problem, and
     * the good names are kept, so one bad name does not make every use of the others look undeclared.
     */
    private static Optional<Set<String>> readFlagNames(String namespace, JsonElement value, Problems problems) {
        if (!value.isJsonArray()) {
            problems.add("bundle", "flags '" + namespace + "' must be an array of flag names");
            return Optional.empty();
        }
        Set<String> names = new HashSet<>();
        for (JsonElement name : value.getAsJsonArray()) {
            if (!JsonValues.isString(name) || name.getAsString().isEmpty()) {
                problems.add("bundle", "flags '" + namespace + "' must list only non-empty flag names");
            } else if (name.getAsString().indexOf('\0') >= 0) {
                problems.add("bundle", "flags '" + namespace + "' must list only flag names without a NUL character");
            } else {
                names.add(name.getAsString());
            }
        }
        return Optional.of(names);
    }

    private static Optional<NpcProfile> readNpc(
            Identifier id,
            JsonElement value,
            Set<Identifier> graphIds,
            DynamicOps<JsonElement> ops,
            Problems problems) {
        String where = "npc " + id;
        if (!value.isJsonObject()) {
            problems.add(where, "must be an object");
            return Optional.empty();
        }
        int mark = problems.count();
        Fields fields = new Fields(value.getAsJsonObject(), where, problems);
        fields.allowOnly(NPC_KEYS);

        Optional<Component> name = fields.required("name", JsonValues.text(ops));
        Optional<Identifier> texture = fields.required("texture", JsonValues::identifier);
        Optional<Identifier> model = fields.optional("model", JsonValues::identifier);
        Optional<Identifier> animations = fields.optional("animations", JsonValues::identifier);
        Optional<Identifier> portrait = fields.optional("portrait", JsonValues::identifier);
        Optional<Identifier> dialogue = fields.optional("dialogue", JsonValues::identifier);
        dialogue.filter(graph -> !graphIds.contains(graph))
                .ifPresent(graph -> fields.add("dialogue '" + graph + "' is not a dialogue in this bundle"));
        Optional<Float> scale = fields.optional("scale", JsonValues::number);
        scale.filter(size -> size <= 0 || size > 4)
                .ifPresent(size -> fields.add("scale must be above 0 and at most 4"));

        if (problems.count() > mark) {
            return Optional.empty();
        }
        return Optional.of(new NpcProfile(
                id, name.orElseThrow(), model, texture.orElseThrow(), animations, portrait, dialogue,
                scale.orElse(1.0f)));
    }
}
