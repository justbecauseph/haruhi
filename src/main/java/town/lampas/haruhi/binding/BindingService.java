package town.lampas.haruhi.binding;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * What other modules may read of the bindings, as {@code /haruhi bindings all} lists them. Read only. Server thread
 * only.
 */
public interface BindingService {

    /**
     * Every binding of {@code server}, from one scan, dimension by dimension in the server's order:
     * <ul>
     *   <li>every block binding, each dimension's sorted by position, with the problems {@code /haruhi bindings}
     *       marks: {@code missing graph}, {@code missing speaker profile} and {@code block is now <id>}. A binding in
     *       an unloaded chunk is listed without loading it, so its block is not checked;</li>
     *   <li>every loaded entity that has a binding, each dimension's sorted by UUID, with its problems.</li>
     * </ul>
     */
    Listing list(MinecraftServer server);

    /** The block bindings and the bound entities of one scan. */
    record Listing(List<BoundBlock> blocks, List<BoundEntity> entities) {

        public Listing {
            blocks = List.copyOf(blocks);
            entities = List.copyOf(entities);
        }
    }

    /**
     * A block binding.
     *
     * @param block the block the binding was made on
     * @param loaded whether the position's chunk is loaded; when it is not, {@code problems} does not check the block
     */
    record BoundBlock(
            Identifier dimension,
            BlockPos pos,
            Identifier block,
            Identifier graph,
            Optional<Identifier> speaker,
            boolean loaded,
            List<String> problems) {

        public BoundBlock {
            problems = List.copyOf(problems);
        }
    }

    /**
     * A loaded entity with a binding.
     *
     * @param type the entity type's id
     * @param name the entity's name as players see it
     */
    record BoundEntity(
            Identifier dimension,
            UUID uuid,
            Identifier type,
            String name,
            Vec3 position,
            Identifier graph,
            Optional<Identifier> speaker,
            List<String> problems) {

        public BoundEntity {
            problems = List.copyOf(problems);
        }
    }
}
