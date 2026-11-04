package town.lampas.haruhi.npc;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.NpcProfile;

/**
 * A character that stays where it is. It is a living entity only so that it can carry the vanilla scale attribute and the
 * default attributes; it has no goals, never falls, is not pushed, never despawns, and takes no damage except {@code /kill}
 * and the void. Right-clicking it with the main hand faces the player and opens the profile's dialogue.
 *
 * <p>Only a teleport or a direct position change moves it. {@link #move} and {@link #setDeltaMovement} do nothing, so
 * explosions, fishing rods, pistons, levitation and portals cannot relocate it, and splash potions do not affect it.
 *
 * <p>The profile id and the look are saved, so a loaded NPC shows its last look before it ticks. The look is recomputed
 * from the active content when the profile is set, when the entity is first ticked after loading, and when a content
 * revision activates. It is synced as three vanilla string entries, so the entity uses only vanilla entity data
 * serializers, whose ids are the same on every server and client.
 */
public class NpcEntity extends LivingEntity {
    static final EntityDataAccessor<String> LOOK_MODEL =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    static final EntityDataAccessor<String> LOOK_TEXTURE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    static final EntityDataAccessor<String> LOOK_ANIMATIONS =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);

    private static final String PROFILE_KEY = "profile";
    private static final String LOOK_MODEL_KEY = "look_model";
    private static final String LOOK_TEXTURE_KEY = "look_texture";
    private static final String LOOK_ANIMATIONS_KEY = "look_animations";

    private @Nullable Identifier profileId;
    /** True until the first tick after a load or a profile change refreshes the look from the active content. */
    private boolean lookStale = true;
    /**
     * {@link #look()} rebuilt from the synced strings, or null until it is next read. Cleared whenever one of them
     * changes, which {@link #onSyncedDataUpdated} reports on both sides: for {@code set} on the server and for every
     * received value on the client.
     */
    private @Nullable NpcLook cachedLook;

    public NpcEntity(EntityType<? extends NpcEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    /** The profile id this NPC was given; empty before one is set. */
    public Optional<Identifier> profileId() {
        return Optional.ofNullable(profileId);
    }

    /** The profile in the active content, or empty when there is none or it is missing from the active revision. */
    public Optional<NpcProfile> profile() {
        if (profileId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(HaruhiServices.content().active().npcs().get(profileId));
    }

    /** The look the server last synced to this entity. Safe on both sides. */
    public NpcLook look() {
        NpcLook look = cachedLook;
        if (look == null) {
            look = NpcLook.fromSynced(
                    entityData.get(LOOK_MODEL), entityData.get(LOOK_TEXTURE), entityData.get(LOOK_ANIMATIONS));
            cachedLook = look;
        }
        return look;
    }

    /** Gives this NPC a profile and refreshes its look from the active content. Server thread only. */
    public void setProfile(Identifier id) {
        this.profileId = id;
        this.lookStale = false;
        refreshLook(HaruhiServices.content().active());
    }

    /**
     * Recomputes the synced look, name and scale from the profile in {@code bundle}. A profile missing from the bundle gives
     * the default look, and its id as the name plate. Server thread only.
     */
    public void refreshLook(ContentBundle bundle) {
        Optional<NpcProfile> profile = profileId == null
                ? Optional.empty()
                : Optional.ofNullable(bundle.npcs().get(profileId));
        NpcLook look = profile.map(NpcLook::of).orElse(NpcLook.DEFAULT);
        entityData.set(LOOK_MODEL, look.syncedModel());
        entityData.set(LOOK_TEXTURE, look.syncedTexture());
        entityData.set(LOOK_ANIMATIONS, look.syncedAnimations());

        AttributeInstance scale = getAttribute(Attributes.SCALE);
        double wanted = profile.map(p -> (double) p.scale()).orElse(1.0);
        if (scale != null && scale.getBaseValue() != wanted) {
            scale.setBaseValue(wanted);
            refreshDimensions();
        }

        Component name = profile.map(NpcProfile::name)
                .orElseGet(() -> profileId == null ? null : Component.literal(profileId.toString()));
        setCustomName(name);
        setCustomNameVisible(profile.isPresent());
    }

    @Override
    public void tick() {
        super.tick();
        if (lookStale && !level().isClientSide()) {
            lookStale = false;
            refreshLook(HaruhiServices.content().active());
        }
    }

    /**
     * Invulnerable to everything except the damage types that bypass invulnerability, which are {@code /kill} and the
     * void. Creative players are not exempt.
     */
    @Override
    public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
        return !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    /** Ignored. Vanilla moves an entity through here for its own travel, pistons and shulkers; the NPC never moves. */
    @Override
    public void move(MoverType type, Vec3 movement) {}

    /**
     * Ignored, so no explosion, knockback, fishing-rod pull or levitation gives the NPC a velocity. Vanilla adds velocity
     * through this method, and {@link #push} and {@link #knockback} go through it too.
     */
    @Override
    public void setDeltaMovement(Vec3 movement) {}

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    /** A piston passes through the NPC, as it does through an armor stand marker. */
    @Override
    public PushReaction getPistonPushReaction() {
        return PushReaction.IGNORE;
    }

    /** Splash potions and potion clouds do not affect the NPC; {@code /effect} still does. */
    @Override
    public boolean isAffectedByPotions() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** An NPC does not shove the players and mobs that stand against it. */
    @Override
    protected void pushEntities() {}

    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean isAffectedByFluids() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith(Entity other) {
        return false;
    }

    /**
     * Keeps the body on the yaw. Vanilla lets the body lag the yaw by up to 50 degrees, and the body is not synced, so a
     * client would draw the NPC turned away from the way it faces. ArmorStand does the same.
     */
    @Override
    protected void tickHeadTurn(float bodyTarget) {
        yBodyRotO = yRotO;
        yBodyRot = getYRot();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        // Every hand consumes the interaction, so an item in the other hand (a name tag) never acts on the NPC.
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.CONSUME;
        }
        if (level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            profile().flatMap(NpcProfile::dialogue).ifPresent(graph -> {
                faceTowards(player);
                HaruhiServices.dialogue().open(serverPlayer, graph, this);
            });
        }
        return InteractionResult.SUCCESS;
    }

    /** Turns the NPC to face {@code target}, keeping it level. */
    private void faceTowards(Entity target) {
        double dx = target.getX() - getX();
        double dz = target.getZ() - getZ();
        turnTo((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
    }

    /**
     * Turns the NPC to {@code yaw}, with its body and head. Setting only the yaw would leave the head at its random
     * constructor value, and the spawn packet carries the head. Server thread only.
     */
    void turnTo(float yaw) {
        setRot(yaw, 0.0F);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(LOOK_MODEL, NpcLook.DEFAULT.syncedModel());
        builder.define(LOOK_TEXTURE, NpcLook.DEFAULT.syncedTexture());
        builder.define(LOOK_ANIMATIONS, NpcLook.DEFAULT.syncedAnimations());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (LOOK_MODEL.equals(accessor) || LOOK_TEXTURE.equals(accessor) || LOOK_ANIMATIONS.equals(accessor)) {
            cachedLook = null;
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.profileId = input.read(PROFILE_KEY, Identifier.CODEC).orElse(null);
        entityData.set(LOOK_MODEL, input.getStringOr(LOOK_MODEL_KEY, NpcLook.DEFAULT.syncedModel()));
        entityData.set(LOOK_TEXTURE, input.getStringOr(LOOK_TEXTURE_KEY, NpcLook.DEFAULT.syncedTexture()));
        entityData.set(LOOK_ANIMATIONS, input.getStringOr(LOOK_ANIMATIONS_KEY, NpcLook.DEFAULT.syncedAnimations()));
        this.lookStale = true;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (profileId != null) {
            output.store(PROFILE_KEY, Identifier.CODEC, profileId);
        }
        output.putString(LOOK_MODEL_KEY, entityData.get(LOOK_MODEL));
        output.putString(LOOK_TEXTURE_KEY, entityData.get(LOOK_TEXTURE));
        output.putString(LOOK_ANIMATIONS_KEY, entityData.get(LOOK_ANIMATIONS));
    }
}
