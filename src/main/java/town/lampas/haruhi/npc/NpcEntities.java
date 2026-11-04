package town.lampas.haruhi.npc;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import town.lampas.haruhi.Haruhi;

/** The {@code haruhi:npc} entity type and its default attributes. Every NPC, whatever its profile, is this type. */
public final class NpcEntities {
    public static final ResourceKey<EntityType<?>> NPC_KEY = ResourceKey.create(Registries.ENTITY_TYPE, Haruhi.id("npc"));

    /** Not summonable: {@code /summon} would make an NPC with no profile, so use {@code /haruhi npc spawn}. */
    public static final EntityType<NpcEntity> NPC = Registry.register(
            BuiltInRegistries.ENTITY_TYPE,
            NPC_KEY,
            EntityType.Builder.of(NpcEntity::new, MobCategory.MISC)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(10)
                    .updateInterval(3)
                    .noSummon()
                    .build(NPC_KEY));

    private NpcEntities() {}

    /** Registers the default attributes. Loads the class, which registers the entity type, so call it during mod init. */
    public static void register() {
        FabricDefaultAttributeRegistry.register(NPC, LivingEntity.createLivingAttributes());
    }
}
