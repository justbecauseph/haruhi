package town.lampas.haruhi.content.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.binding.BindingService.BoundBlock;
import town.lampas.haruhi.binding.BindingService.BoundEntity;
import town.lampas.haruhi.npc.NpcControl.LoadedNpc;

class BindingsPageTest {
    private static final Identifier OVERWORLD = Identifier.parse("minecraft:overworld");
    private static final Identifier NETHER = Identifier.parse("minecraft:the_nether");
    private static final Identifier GRAPH = Identifier.parse("lampas:lectern");
    private static final Identifier LECTERN = Identifier.parse("minecraft:lectern");
    private static final Identifier GREETER = Identifier.parse("lampas:greeter");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final List<BoundBlock> blocks = List.of(
            block(NETHER, new BlockPos(0, 64, 0)),
            block(OVERWORLD, new BlockPos(5, 70, 0)),
            block(OVERWORLD, new BlockPos(9, 64, 0)));
    private final List<BoundEntity> entities = List.of(
            new BoundEntity(OVERWORLD, SECOND, Identifier.parse("minecraft:villager"), "Librarian",
                    new Vec3(1.5, 64, 2.5), GRAPH, Optional.empty(), List.of("missing graph")),
            new BoundEntity(OVERWORLD, FIRST, Identifier.parse("minecraft:armor_stand"), "Armor Stand",
                    new Vec3(0.5, 64, 0.5), GRAPH, Optional.of(GREETER), List.of()));
    private final List<LoadedNpc> npcs = List.of(
            new LoadedNpc(OVERWORLD, FIRST, 12, Optional.of(GREETER), new Vec3(3, 64, 3)),
            new LoadedNpc(OVERWORLD, SECOND, 13, Optional.of(Identifier.parse("lampas:gone")), new Vec3(4, 64, 4)));

    @Test
    void oneLargePageListsEverythingInOrder() {
        JsonObject page = BindingsPage.page(blocks, entities, npcs, Set.of(GREETER), Optional.empty(), 200);

        assertEquals("ok", page.get("status").getAsString());
        // Overworld before the Nether; y 64 before y 70.
        assertEquals(List.of("minecraft:overworld 9 64", "minecraft:overworld 5 70", "minecraft:the_nether 0 64"),
                strings(page.getAsJsonArray("blocks"), item -> item.get("dimension").getAsString() + " "
                        + item.get("x").getAsInt() + " " + item.get("y").getAsInt()));
        assertEquals(List.of(FIRST.toString(), SECOND.toString()),
                strings(page.getAsJsonArray("entities"), item -> item.get("uuid").getAsString()));
        assertEquals(List.of("[]", "[\"missing profile\"]"),
                strings(page.getAsJsonArray("npcs"), item -> item.getAsJsonArray("problems").toString()));
        assertFalse(page.has("next"));
    }

    @Test
    void entriesCarryWhatTheCommandShows() {
        JsonObject page = BindingsPage.page(blocks, entities, npcs, Set.of(GREETER), Optional.empty(), 200);

        JsonObject block = page.getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals("minecraft:lectern", block.get("block").getAsString());
        assertEquals("lampas:lectern", block.get("graph").getAsString());
        assertEquals(true, block.get("loaded").getAsBoolean());
        JsonObject entity = page.getAsJsonArray("entities").get(0).getAsJsonObject();
        assertEquals("lampas:greeter", entity.get("speaker").getAsString());
        assertEquals("Armor Stand", entity.get("name").getAsString());
        JsonObject npc = page.getAsJsonArray("npcs").get(0).getAsJsonObject();
        assertEquals(12, npc.get("entity_id").getAsInt());
        assertEquals("lampas:greeter", npc.get("profile").getAsString());
    }

    @Test
    void anNpcWithoutAProfileIsMarked() {
        List<LoadedNpc> bare = List.of(new LoadedNpc(OVERWORLD, FIRST, 1, Optional.empty(), Vec3.ZERO));

        JsonObject page = BindingsPage.page(List.of(), List.of(), bare, Set.of(), Optional.empty(), 10);

        assertEquals("[\"no profile\"]", page.getAsJsonArray("npcs").get(0).getAsJsonObject()
                .getAsJsonArray("problems").toString());
    }

    @Test
    void pagesWalkTheListThroughTheCursor() {
        List<String> seen = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        int pages = 0;
        do {
            JsonObject page =
                    BindingsPage.page(blocks, entities, npcs, Set.of(GREETER), cursor.map(BindingsPage.Key::parse), 2);
            for (String array : List.of("blocks", "entities", "npcs")) {
                page.getAsJsonArray(array).forEach(item -> seen.add(array + " " + item));
            }
            cursor = page.has("next") ? Optional.of(page.get("next").getAsString()) : Optional.empty();
            pages++;
        } while (cursor.isPresent());

        assertEquals(4, pages, "7 items in pages of 2");
        JsonObject whole = BindingsPage.page(blocks, entities, npcs, Set.of(GREETER), Optional.empty(), 200);
        List<String> all = new ArrayList<>();
        for (String array : List.of("blocks", "entities", "npcs")) {
            whole.getAsJsonArray(array).forEach(item -> all.add(array + " " + item));
        }
        assertEquals(all, seen);
    }

    @Test
    void aCursorStillWorksAfterItsItemIsGone() {
        String cursor = BindingsPage.page(blocks, entities, npcs, Set.of(GREETER), Optional.empty(), 1)
                .get("next").getAsString();
        assertEquals("block minecraft:overworld 9 64 0", cursor);

        JsonObject next = BindingsPage.page(blocks.subList(0, 2), entities, npcs, Set.of(GREETER),
                Optional.of(BindingsPage.Key.parse(cursor)), 1);

        assertEquals(70, next.getAsJsonArray("blocks").get(0).getAsJsonObject().get("y").getAsInt());
    }

    @Test
    void aMalformedCursorIsRefused() {
        for (String cursor : List.of("", "block minecraft:overworld 1 2", "chest minecraft:overworld 1 2 3",
                "entity minecraft:overworld", "block minecraft:overworld x 2 3", "npc NOT AN ID " + FIRST)) {
            assertThrows(IllegalArgumentException.class, () -> BindingsPage.Key.parse(cursor), cursor);
        }
    }

    private static BoundBlock block(Identifier dimension, BlockPos pos) {
        return new BoundBlock(dimension, pos, LECTERN, GRAPH, Optional.empty(), true, List.of());
    }

    private static List<String> strings(JsonArray array, java.util.function.Function<JsonObject, String> text) {
        List<String> out = new ArrayList<>();
        for (JsonElement element : array) {
            out.add(text.apply(element.getAsJsonObject()));
        }
        return out;
    }
}
