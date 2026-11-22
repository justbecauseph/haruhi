package town.lampas.haruhi.compat.jade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.StreamServerDataProvider;
import snownee.jade.api.TooltipPosition;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.binding.BindingNames;
import town.lampas.haruhi.binding.BoundBlockHints;
import town.lampas.haruhi.binding.EntityBindings;
import town.lampas.haruhi.client.jade.HaruhiJadeClient;

/**
 * Optional Jade integration, loaded only when Jade is installed. Replaces the block or entity title in Jade's HUD
 * tooltip with the active {@code speaker_name} override when the target carries a Haruhi binding without a loaded
 * NPC speaker profile. Kept free of client-only imports so dedicated servers load {@link #register} safely.
 */
public final class HaruhiJadePlugin implements IWailaPlugin {
    public static final Identifier SPEAKER_NAME_UID = Haruhi.id("speaker_name");
    public static final int SPEAKER_NAME_PRIORITY = TooltipPosition.HEAD - 50;
    public static final Identifier INTERACT_HINT_UID = Haruhi.id("interact_hint");
    public static final int INTERACT_HINT_PRIORITY = TooltipPosition.BODY + 50;

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(BlockDataProvider.INSTANCE, Block.class);
        registration.registerEntityDataProvider(EntityDataProvider.INSTANCE, Entity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        HaruhiJadeClient.register(registration);
    }

    /** Server data provider sending the resolved {@code speaker_name} override for a bound block. */
    public enum BlockDataProvider implements StreamServerDataProvider<BlockAccessor, Component> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return SPEAKER_NAME_UID;
        }

        @Override
        public int getDefaultPriority() {
            return SPEAKER_NAME_PRIORITY;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, Component> streamCodec() {
            return ComponentSerialization.STREAM_CODEC;
        }

        @Override
        public boolean shouldRequestData(BlockAccessor accessor) {
            return BoundBlockHints.hasOverride(accessor.getLevel(), accessor.getPosition(), accessor.getBlockState());
        }

        @Override
        public @Nullable Component streamData(BlockAccessor accessor) {
            if (accessor.getLevel() instanceof ServerLevel level
                    && accessor.getPlayer() instanceof ServerPlayer player) {
                return BindingNames.blockNameOverride(level, accessor.getPosition(), accessor.getBlockState(), player)
                        .orElse(null);
            }
            return null;
        }
    }

    /** Server data provider sending the resolved {@code speaker_name} override for a bound entity. */
    public enum EntityDataProvider implements StreamServerDataProvider<EntityAccessor, Component> {
        INSTANCE;

        @Override
        public Identifier getUid() {
            return SPEAKER_NAME_UID;
        }

        @Override
        public int getDefaultPriority() {
            return SPEAKER_NAME_PRIORITY;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, Component> streamCodec() {
            return ComponentSerialization.STREAM_CODEC;
        }

        @Override
        public boolean shouldRequestData(EntityAccessor accessor) {
            Entity entity = accessor.getEntity();
            return entity != null && EntityBindings.get(entity).isPresent();
        }

        @Override
        public @Nullable Component streamData(EntityAccessor accessor) {
            if (accessor.getPlayer() instanceof ServerPlayer player && accessor.getEntity() != null) {
                return BindingNames.entityNameOverride(accessor.getEntity(), player).orElse(null);
            }
            return null;
        }
    }
}
