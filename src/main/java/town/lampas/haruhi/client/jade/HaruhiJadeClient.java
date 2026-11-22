package town.lampas.haruhi.client.jade;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.JadeIds;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.theme.IThemeHelper;
import town.lampas.haruhi.binding.BoundBlockHints;
import town.lampas.haruhi.binding.EntityBindings;
import town.lampas.haruhi.client.indicator.IndicatorMath;
import town.lampas.haruhi.compat.jade.HaruhiJadePlugin;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * Client-side Jade tooltip providers: replaces bound target names with {@code speaker_name} overrides, removes health
 * and armor bars from invulnerable Haruhi NPCs, and appends interact hints for talkable NPCs and active bindings.
 */
public final class HaruhiJadeClient {
    private static final int HINT_GOLD = 0xFFD27F;

    private HaruhiJadeClient() {}

    public static void register(IWailaClientRegistration registration) {
        registration.registerBlockComponent(BlockNameProvider.INSTANCE, Block.class);
        registration.registerEntityComponent(EntityNameProvider.INSTANCE, Entity.class);
        registration.registerEntityComponent(NpcHintProvider.INSTANCE, NpcEntity.class);
        registration.registerEntityComponent(EntityHintProvider.INSTANCE, Entity.class);
        registration.registerBlockComponent(BlockHintProvider.INSTANCE, Block.class);
    }

    private interface OverrideNameProvider<T extends Accessor<?>> extends IComponentProvider<T> {
        @Override
        default Identifier getUid() {
            return HaruhiJadePlugin.SPEAKER_NAME_UID;
        }

        @Override
        default int getDefaultPriority() {
            return HaruhiJadePlugin.SPEAKER_NAME_PRIORITY;
        }

        @Override
        default boolean isRequired() {
            return true;
        }
    }

    private interface InteractHintProvider<T extends Accessor<?>> extends IComponentProvider<T> {
        @Override
        default Identifier getUid() {
            return HaruhiJadePlugin.INTERACT_HINT_UID;
        }

        @Override
        default int getDefaultPriority() {
            return HaruhiJadePlugin.INTERACT_HINT_PRIORITY;
        }

        @Override
        default boolean isRequired() {
            return true;
        }
    }

    private enum BlockNameProvider implements OverrideNameProvider<BlockAccessor>, IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!BoundBlockHints.hasOverride(accessor.getLevel(), accessor.getPosition(), accessor.getBlockState())) {
                return;
            }
            HaruhiJadePlugin.BlockDataProvider.INSTANCE.decodeFromData(accessor)
                    .ifPresent(name -> applyTitleOverride(tooltip, name));
        }
    }

    private enum EntityNameProvider implements OverrideNameProvider<EntityAccessor>, IEntityComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            if (accessor.getEntity() == null || EntityBindings.get(accessor.getEntity()).isEmpty()) {
                return;
            }
            HaruhiJadePlugin.EntityDataProvider.INSTANCE.decodeFromData(accessor)
                    .ifPresent(name -> applyTitleOverride(tooltip, name));
        }
    }

    private enum NpcHintProvider implements InteractHintProvider<EntityAccessor>, IEntityComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            tooltip.remove(JadeIds.MC_ENTITY_HEALTH);
            tooltip.remove(JadeIds.MC_ENTITY_ARMOR);
            if (accessor.getEntity() instanceof NpcEntity npc && npc.hasDialogue()) {
                tooltip.add(hintComponent("jade.haruhi.talk"), HaruhiJadePlugin.INTERACT_HINT_UID);
            }
        }
    }

    private enum EntityHintProvider implements InteractHintProvider<EntityAccessor>, IEntityComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
            Entity entity = accessor.getEntity();
            if (entity == null || entity instanceof NpcEntity || entity instanceof Player) {
                return;
            }
            if (EntityBindings.get(entity).filter(b -> BoundBlockHints.isGraphActive(b.graph())).isPresent()) {
                tooltip.add(hintComponent("jade.haruhi.talk"), HaruhiJadePlugin.INTERACT_HINT_UID);
            }
        }
    }

    private enum BlockHintProvider implements InteractHintProvider<BlockAccessor>, IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (BoundBlockHints.isActiveBound(accessor.getLevel(), accessor.getPosition(), accessor.getBlockState())) {
                tooltip.add(hintComponent("jade.haruhi.inspect"), HaruhiJadePlugin.INTERACT_HINT_UID);
            }
        }
    }

    private static Component hintComponent(String langKey) {
        KeyMapping useKey = Minecraft.getInstance().options.keyUse;
        String keyLabel = IndicatorMath.jadeKeyLabel(
                KeyMappingHelper.getBoundKeyOf(useKey).getName(),
                useKey.getTranslatedKeyMessage().getString());
        return Component.translatable(langKey, keyLabel).withStyle(style -> style.withColor(HINT_GOLD));
    }

    private static void applyTitleOverride(ITooltip tooltip, Component name) {
        // Apply Jade's theme title style on an outer wrapper so explicitly colored parts of name keep their color.
        Component styled = Component.empty()
                .setStyle(IThemeHelper.get().title(Component.empty()).getStyle())
                .append(name);
        tooltip.replace(JadeIds.CORE_OBJECT_NAME, styled);
    }
}
