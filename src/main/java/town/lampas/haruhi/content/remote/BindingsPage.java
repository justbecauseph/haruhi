package town.lampas.haruhi.content.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import town.lampas.haruhi.binding.BindingService.BoundBlock;
import town.lampas.haruhi.binding.BindingService.BoundEntity;
import town.lampas.haruhi.npc.NpcControl.LoadedNpc;

/**
 * One page of {@code haruhi:content/bindings}: block bindings, then bound entities, then Haruhi NPCs, in one order that
 * a cursor walks. Blocks sort by dimension, then position (y, then z, then x); entities and NPCs by dimension, then
 * UUID. The cursor is the key of the last item of a page, as {@code block <dimension> <x> <y> <z>},
 * {@code entity <dimension> <uuid>} or {@code npc <dimension> <uuid>}, so a page starts after it even when items
 * before it have come or gone.
 */
final class BindingsPage {
    static final String NO_PROFILE = "no profile";
    static final String MISSING_PROFILE = "missing profile";

    private static final int BLOCK = 0;
    private static final int ENTITY = 1;
    private static final int NPC = 2;
    private static final String[] KINDS = {"block", "entity", "npc"};

    /** Where an item sorts. {@code pos} is set for a block, {@code uuid} for an entity or an NPC. */
    record Key(int kind, String dimension, Optional<BlockPos> pos, Optional<String> uuid) {
        static final Comparator<Key> ORDER = Comparator.comparingInt(Key::kind)
                .thenComparing(Key::dimension)
                .thenComparing(key -> key.pos().orElse(BlockPos.ZERO), BlockPos::compareTo)
                .thenComparing(key -> key.uuid().orElse(""));

        String cursor() {
            return KINDS[kind] + " " + dimension + " " + pos
                    .map(at -> at.getX() + " " + at.getY() + " " + at.getZ())
                    .orElseGet(() -> uuid.orElseThrow());
        }

        /**
         * The key a cursor names.
         *
         * @throws IllegalArgumentException when {@code cursor} is not one
         */
        static Key parse(String cursor) {
            String[] parts = cursor.split(" ", -1);
            int kind = List.of(KINDS).indexOf(parts[0]);
            if (kind < 0 || parts.length < 3 || Identifier.tryParse(parts[1]) == null) {
                throw new IllegalArgumentException("not a bindings cursor");
            }
            if (kind == BLOCK) {
                if (parts.length != 5) {
                    throw new IllegalArgumentException("not a bindings cursor");
                }
                return block(parts[1], new BlockPos(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                        Integer.parseInt(parts[4])));
            }
            if (parts.length != 3) {
                throw new IllegalArgumentException("not a bindings cursor");
            }
            return new Key(kind, parts[1], Optional.empty(), Optional.of(parts[2]));
        }

        static Key block(String dimension, BlockPos pos) {
            return new Key(BLOCK, dimension, Optional.of(pos.immutable()), Optional.empty());
        }
    }

    /** An item and how to write it; only the page's items are written. */
    private record Item(Key key, String array, Supplier<JsonObject> json) {}

    private BindingsPage() {}

    /**
     * The page after {@code start}, at most {@code limit} items, with {@code next} when more follow.
     *
     * @param profiles the NPC profile ids of the active content, for the NPCs' problems
     * @param start the key the {@code before} cursor names, from {@link Key#parse}
     */
    static JsonObject page(
            List<BoundBlock> blocks,
            List<BoundEntity> entities,
            List<LoadedNpc> npcs,
            Set<Identifier> profiles,
            Optional<Key> start,
            int limit) {
        List<Item> items = new ArrayList<>();
        for (BoundBlock block : blocks) {
            items.add(new Item(Key.block(block.dimension().toString(), block.pos()), "blocks", () -> json(block)));
        }
        for (BoundEntity entity : entities) {
            items.add(new Item(new Key(ENTITY, entity.dimension().toString(), Optional.empty(),
                    Optional.of(entity.uuid().toString())), "entities", () -> json(entity)));
        }
        for (LoadedNpc npc : npcs) {
            items.add(new Item(new Key(NPC, npc.dimension().toString(), Optional.empty(),
                    Optional.of(npc.uuid().toString())), "npcs", () -> json(npc, profiles)));
        }
        items.sort(Comparator.comparing(Item::key, Key.ORDER));

        JsonObject page = new JsonObject();
        page.addProperty("status", "ok");
        page.add("blocks", new JsonArray());
        page.add("entities", new JsonArray());
        page.add("npcs", new JsonArray());
        int taken = 0;
        Key last = null;
        for (Item item : items) {
            if (start.isPresent() && Key.ORDER.compare(item.key(), start.get()) <= 0) {
                continue;
            }
            if (taken == limit) {
                page.addProperty("next", last.cursor());
                break;
            }
            page.getAsJsonArray(item.array()).add(item.json().get());
            last = item.key();
            taken++;
        }
        return page;
    }

    private static JsonObject json(BoundBlock block) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", block.dimension().toString());
        json.addProperty("x", block.pos().getX());
        json.addProperty("y", block.pos().getY());
        json.addProperty("z", block.pos().getZ());
        json.addProperty("block", block.block().toString());
        json.addProperty("graph", block.graph().toString());
        block.speaker().ifPresent(speaker -> json.addProperty("speaker", speaker.toString()));
        json.addProperty("loaded", block.loaded());
        json.add("problems", strings(block.problems()));
        return json;
    }

    private static JsonObject json(BoundEntity entity) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", entity.dimension().toString());
        json.addProperty("uuid", entity.uuid().toString());
        json.addProperty("type", entity.type().toString());
        json.addProperty("name", entity.name());
        position(json, entity.position());
        json.addProperty("graph", entity.graph().toString());
        entity.speaker().ifPresent(speaker -> json.addProperty("speaker", speaker.toString()));
        json.add("problems", strings(entity.problems()));
        return json;
    }

    private static JsonObject json(LoadedNpc npc, Set<Identifier> profiles) {
        JsonObject json = new JsonObject();
        json.addProperty("dimension", npc.dimension().toString());
        json.addProperty("uuid", npc.uuid().toString());
        json.addProperty("entity_id", npc.entityId());
        npc.profile().ifPresent(profile -> json.addProperty("profile", profile.toString()));
        position(json, npc.position());
        List<String> problems = npc.profile().isEmpty() ? List.of(NO_PROFILE)
                : profiles.contains(npc.profile().get()) ? List.of() : List.of(MISSING_PROFILE);
        json.add("problems", strings(problems));
        return json;
    }

    private static void position(JsonObject json, Vec3 position) {
        json.addProperty("x", position.x);
        json.addProperty("y", position.y);
        json.addProperty("z", position.z);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
