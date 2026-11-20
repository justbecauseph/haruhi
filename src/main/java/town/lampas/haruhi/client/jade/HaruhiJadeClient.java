package town.lampas.haruhi.client.jade;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
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
import town.lampas.haruhi.compat.jade.HaruhiJadePlugin;

/** Client-side Jade tooltip providers replacing the object name with Haruhi's {@code speaker_name} override. */
public final class HaruhiJadeClient {
    private HaruhiJadeClient() {}

    public static void register(IWailaClientRegistration registration) {
        registration.registerBlockComponent(BlockNameProvider.INSTANCE, Block.class);
        registration.registerEntityComponent(EntityNameProvider.INSTANCE, Entity.class);
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

    private static void applyTitleOverride(ITooltip tooltip, Component name) {
        // Apply Jade's theme title style on an outer wrapper so explicitly colored parts of name keep their color.
        Component styled = Component.empty()
                .setStyle(IThemeHelper.get().title(Component.empty()).getStyle())
                .append(name);
        tooltip.replace(JadeIds.CORE_OBJECT_NAME, styled);
    }
}
