package town.lampas.haruhi.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The saved form of block bindings, the tolerant load, and which block states a binding applies to. */
class BlockBindingsTest {
    private static final Identifier GRAPH = Identifier.fromNamespaceAndPath("haruhi", "test/lectern");
    private static final Identifier OTHER_GRAPH = Identifier.fromNamespaceAndPath("haruhi", "test/other");
    private static final Identifier SPEAKER = Identifier.fromNamespaceAndPath("haruhi", "test/clerk");
    private static final Identifier CHEST = Identifier.withDefaultNamespace("chest");
    private static final BlockPos POS = new BlockPos(10, 64, -3);

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void bindingsSurviveASaveAndLoad() {
        BlockBindings original = new BlockBindings();
        original.put(POS, new BlockBinding(GRAPH, Optional.of(SPEAKER), CHEST));
        original.put(POS.above(), new BlockBinding(OTHER_GRAPH, Optional.empty(), CHEST));

        JsonElement saved = BlockBindings.CODEC.encodeStart(JsonOps.INSTANCE, original).getOrThrow();
        BlockBindings loaded = BlockBindings.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();

        assertEquals(original.all(), loaded.all());
    }

    @Test
    void anEntryThatDoesNotDecodeIsSkippedAndTheRestLoad() {
        String json = """
                {"bindings": [
                  {"pos": [1, 2, 3], "binding": {"graph": "haruhi:test/lectern", "block": "minecraft:chest"}},
                  {"pos": [4, 5, 6], "binding": {"graph": "not an id!", "block": "minecraft:chest"}},
                  {"pos": "nowhere", "binding": {"graph": "haruhi:test/other", "block": "minecraft:chest"}}
                ]}
                """;

        BlockBindings loaded = BlockBindings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();

        assertEquals(Map.of(new BlockPos(1, 2, 3), new BlockBinding(GRAPH, Optional.empty(), CHEST)), loaded.all());
    }

    @Test
    void aFileWithoutBindingsLoadsEmpty() {
        BlockBindings loaded = BlockBindings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow();

        assertTrue(loaded.all().isEmpty());
    }

    @Test
    void putReplacesAndReturnsThePreviousBindingAndMarksTheDataDirty() {
        BlockBindings bindings = new BlockBindings();
        BlockBinding first = new BlockBinding(GRAPH, Optional.empty(), CHEST);
        BlockBinding second = new BlockBinding(OTHER_GRAPH, Optional.empty(), CHEST);

        assertEquals(Optional.empty(), bindings.put(POS, first));
        assertTrue(bindings.isDirty(), "a new binding must be saved");
        assertEquals(Optional.of(first), bindings.put(POS.mutable(), second), "a mutable position finds the same key");
        assertEquals(Optional.of(second), bindings.get(POS));
    }

    @Test
    void removeReturnsTheBindingAndDirtiesOnlyWhenOneWasThere() {
        BlockBindings bindings = new BlockBindings();
        assertEquals(Optional.empty(), bindings.remove(POS));
        assertFalse(bindings.isDirty(), "removing nothing changes nothing to save");

        BlockBinding binding = new BlockBinding(GRAPH, Optional.empty(), CHEST);
        bindings.put(POS, binding);
        bindings.setDirty(false);
        assertEquals(Optional.of(binding), bindings.remove(POS));
        assertTrue(bindings.isDirty());
        assertEquals(Optional.empty(), bindings.get(POS));
    }

    @Test
    void aBindingAppliesToEveryStateOfItsBlockAndToNoOtherBlock() {
        BlockBinding binding = new BlockBinding(GRAPH, Optional.empty(), CHEST);

        assertTrue(binding.matches(Blocks.CHEST.defaultBlockState()));
        assertTrue(binding.matches(Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT)),
                "a chest that became half of a double chest is still the bound chest");
        assertFalse(binding.matches(Blocks.TRAPPED_CHEST.defaultBlockState()));
        assertFalse(binding.matches(Blocks.AIR.defaultBlockState()), "a broken block no longer matches");
    }
}
