package town.lampas.haruhi.binding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * The read-only {@link BindingService}, and the scan behind both it and {@code /haruhi bindings}, so the command and the
 * service list the same bindings with the same problems.
 */
final class BindingServiceImpl implements BindingService {

    @Override
    public Listing list(MinecraftServer server) {
        return scan(server, null, null, 0);
    }

    /**
     * Every binding of {@code server}, checked against the active content, dimension by dimension in the server's
     * order.
     *
     * @param nearLevel with {@code near}, only bindings within {@code radius} blocks of it in this level; null for
     *         every dimension
     */
    static Listing scan(MinecraftServer server, @Nullable ServerLevel nearLevel, @Nullable Vec3 near, double radius) {
        ContentBundle content = HaruhiServices.content().active();
        List<BoundBlock> blocks = new ArrayList<>();
        List<BoundEntity> entities = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (nearLevel != null && level != nearLevel) {
                continue;
            }
            Identifier dimension = level.dimension().identifier();
            for (BlockBindings.Entry entry : BlockBindings.of(level).entries()) {
                if (near != null && !Vec3.atCenterOf(entry.pos()).closerThan(near, radius)) {
                    continue;
                }
                BlockBinding binding = entry.binding();
                // A binding in an unloaded chunk is listed without loading it, so its block cannot be checked.
                Optional<Identifier> blockNow = level.isLoaded(entry.pos())
                        ? Optional.of(BlockBinding.blockId(level.getBlockState(entry.pos())))
                        : Optional.empty();
                List<String> found = blockNow
                        .map(id -> BindingProblems.of(binding, content, id))
                        .orElseGet(() -> BindingProblems.of(binding.binding(), content));
                blocks.add(new BoundBlock(dimension, entry.pos(), binding.block(), binding.graph(), binding.speaker(),
                        blockNow.isPresent(), found));
            }
            List<Entity> bound = new ArrayList<>();
            Iterable<Entity> candidates = near == null
                    ? level.getAllEntities()
                    : level.getEntities((Entity) null, AABB.ofSize(near, 2 * radius, 2 * radius, 2 * radius),
                            entity -> entity.position().closerThan(near, radius));
            for (Entity entity : candidates) {
                if (EntityBindings.get(entity).isPresent()) {
                    bound.add(entity);
                }
            }
            bound.sort(Comparator.comparing(Entity::getStringUUID));
            for (Entity entity : bound) {
                Binding binding = EntityBindings.get(entity).orElseThrow();
                entities.add(new BoundEntity(dimension, entity.getUUID(),
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), entity.getName().getString(),
                        entity.position(), binding.graph(), binding.speaker(), BindingProblems.of(binding, content)));
            }
        }
        return new Listing(blocks, entities);
    }
}
