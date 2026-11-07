package town.lampas.haruhi.binding;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A conversation attached to a block position: the graph, the optional speaker profile, and the block that was there
 * when it was bound. The binding applies only while that block is still at the position, in any state.
 *
 * @param block the block's registry id at bind time
 */
public record BlockBinding(Identifier graph, Optional<Identifier> speaker, Identifier block) {
    public static final Codec<BlockBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("graph").forGetter(BlockBinding::graph),
            Identifier.CODEC.optionalFieldOf("speaker").forGetter(BlockBinding::speaker),
            Identifier.CODEC.fieldOf("block").forGetter(BlockBinding::block)
    ).apply(instance, BlockBinding::new));

    public BlockBinding {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(block, "block");
    }

    /** The graph and speaker, as an entity binding would hold them. */
    public Binding binding() {
        return new Binding(graph, speaker);
    }

    /** True while {@code state} is the block that was bound, in any of its states. */
    public boolean matches(BlockState state) {
        return block.equals(blockId(state));
    }

    static Identifier blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }
}
