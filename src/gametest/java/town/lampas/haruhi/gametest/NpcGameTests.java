package town.lampas.haruhi.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.DialogueSessions;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.npc.NpcEntities;
import town.lampas.haruhi.npc.NpcEntity;
import town.lampas.haruhi.npc.NpcLook;

/**
 * Server GameTests owned by the npc component. They need every module, so they run after integration.
 *
 * <p>The active content is one per server, and the content and dialogue GameTests activate their own bundles. These
 * tests therefore run in their own test environment, {@value #ENVIRONMENT} (an empty one, in
 * {@code data/haruhi-gametest/test_environment/npc.json}). The GameTest runner puts each environment in its own batch
 * and runs batches one after another, so no other component's test changes the active content while these run, and
 * the fixture stays active across the ticks a dialogue open waits for a player's flags.
 *
 * <p>Every test that activates content first waits for
 * {@link town.lampas.haruhi.content.ContentService#isStartupComplete}, so the startup activation cannot replace the
 * fixture.
 */
public final class NpcGameTests {
    private static final String ENVIRONMENT = "haruhi-gametest:npc";
    private static final String FIXTURE = "/haruhi/fixtures/npc/npc_bundle.json";
    /** The fixture with the styled profile changed: the Alex skin, and no model or animations. */
    private static final String RESTYLED_FIXTURE = "/haruhi/fixtures/npc/npc_bundle_restyled.json";
    private static final Identifier GREETER = Identifier.fromNamespaceAndPath("haruhi", "fixture/greeter");
    private static final Identifier STYLED = Identifier.fromNamespaceAndPath("haruhi", "fixture/styled");
    /** The look the fixture's styled profile gives its NPC: its model and animations are the styled id. */
    private static final NpcLook STYLED_LOOK = new NpcLook(
            Optional.of(STYLED),
            Identifier.fromNamespaceAndPath("haruhi", "textures/npc/fixture/styled.png"),
            Optional.of(STYLED));

    @GameTest(environment = ENVIRONMENT, maxTicks = 130)
    public void npcDoesNotMoveOverOneHundredTicks(GameTestHelper helper) {
        Scene scene = new Scene();
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.start = scene.npc.position();
                })
                .thenIdle(100)
                .thenExecute(() -> helper.assertTrue(
                        scene.npc.position().distanceToSqr(scene.start) < 1.0E-9, "the NPC moved on its own"))
                .thenSucceed();
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void npcIgnoresPlayerDamageButNotKill(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    ServerPlayer player = mockPlayer(helper);
                    NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    ServerLevel level = helper.getLevel();
                    DamageSource attack = level.damageSources().playerAttack(player);

                    float health = npc.getHealth();
                    helper.assertFalse(npc.hurtServer(level, attack, 10.0F), "the NPC accepted player damage");
                    helper.assertValueEqual(npc.getHealth(), health, "the NPC lost health to player damage");

                    npc.kill(level);
                    helper.assertFalse(npc.isAlive(), "/kill did not remove the NPC");
                })
                .thenSucceed();
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void profileSurvivesSaveAndLoad(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    NpcEntity loaded = saveAndLoad(helper, npc);
                    helper.assertValueEqual(loaded.profileId().orElse(null), GREETER, "the profile was lost on load");
                })
                .thenSucceed();
    }

    /**
     * A loaded NPC shows its saved look before it has ticked. The chunk of an NPC that is tracked but not ticking
     * never ticks it, so the look must come from the saved entity rather than from the first tick.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void lookSurvivesSaveAndLoadBeforeTheNpcTicks(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    NpcEntity npc = spawn(helper, STYLED, new Vec3(2.5, 2.0, 2.5));
                    NpcEntity loaded = saveAndLoad(helper, npc);
                    helper.assertValueEqual(loaded.look(), STYLED_LOOK, "the look of a loaded NPC before its first tick");
                })
                .thenSucceed();
    }

    /**
     * A player whose dialogue flags have loaded right-clicks the NPC from within reach, and the profile's dialogue starts
     * in the same tick with the NPC as the interlocutor. Joining starts the flag load off the server thread, so the test
     * waits for it before the right-click.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void rightClickOpensTheDialogue(GameTestHelper helper) {
        Scene scene = new Scene();
        DialogueSessions sessions = DialogueModule.sessions();

        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.player = joinNextTo(helper, scene.npc);
                })
                .thenWaitUntil(() -> helper.assertTrue(sessions.loadedFlags(scene.player).isPresent(),
                        "the joined player's dialogue flags have not loaded"))
                .thenExecute(() -> {
                    scene.npc.interact(scene.player, InteractionHand.MAIN_HAND, scene.npc.position());
                    helper.assertTrue(HaruhiServices.dialogue().isInDialogue(scene.player),
                            "right-click did not open the dialogue");
                    assertTalkingTo(helper, scene.player, scene.npc);
                    HaruhiServices.dialogue().close(scene.player);
                })
                .thenSucceed();
    }

    /**
     * A player whose dialogue flags are not cached right-clicks the NPC from within reach. The open is queued while the
     * flags load off the server thread; once they have loaded, the dialogue checks that the player is still within 8
     * blocks of the NPC and starts the profile's dialogue with the NPC as the interlocutor.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void rightClickBeforeTheFlagsLoadOpensTheDialogueOnceTheyLoad(GameTestHelper helper) {
        Scene scene = new Scene();
        DialogueSessions sessions = DialogueModule.sessions();

        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.player = joinNextTo(helper, scene.npc);
                })
                .thenWaitUntil(() -> helper.assertTrue(sessions.loadedFlags(scene.player).isPresent(),
                        "the joined player's dialogue flags have not loaded"))
                .thenExecute(() -> {
                    // Drop the player's cached flags, as quitting does, so the right-click below finds them unloaded
                    // and queues its open behind a fresh load.
                    sessions.onLeave(scene.player);
                    helper.assertTrue(sessions.loadedFlags(scene.player).isEmpty(), "the player's flags are still cached");
                    scene.npc.interact(scene.player, InteractionHand.MAIN_HAND, scene.npc.position());
                })
                .thenWaitUntil(() -> assertTalkingTo(helper, scene.player, scene.npc))
                .thenExecute(() -> HaruhiServices.dialogue().close(scene.player))
                .thenSucceed();
    }

    /**
     * The styled look reaches a tracking client through the vanilla entity data packet, and activating a revision that
     * changes the profile sends the new look. Everything runs in one tick and the previous content is active again at
     * the end, so the other tests in the batch never see the swap.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void lookSyncsToTrackingClientsAndFollowsContentActivation(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    ContentBundle previous = HaruhiServices.content().active();
                    try {
                        activateFixtures();
                        NpcEntity npc = spawn(helper, STYLED, new Vec3(2.5, 2.0, 2.5));
                        helper.assertValueEqual(npc.look(), STYLED_LOOK, "the server look of the styled profile");

                        NpcEntity tracking = NpcEntities.NPC.create(helper.getLevel(), EntitySpawnReason.LOAD);
                        helper.assertTrue(tracking != null, "the NPC type could not create an entity");
                        // What the server sends when a client starts tracking the entity.
                        receive(helper, npc, tracking, npc.getEntityData().getNonDefaultValues());
                        helper.assertValueEqual(tracking.look(), STYLED_LOOK, "the look a tracking client receives");

                        npc.getEntityData().packDirty();
                        activate(RESTYLED_FIXTURE);
                        NpcLook restyled = new NpcLook(
                                Optional.empty(),
                                Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/slim/alex.png"),
                                Optional.empty());
                        helper.assertValueEqual(npc.look(), restyled, "the server look after the restyled revision activated");
                        // What the server sends to tracking clients when the entity data changes.
                        receive(helper, npc, tracking, npc.getEntityData().packDirty());
                        helper.assertValueEqual(tracking.look(), restyled, "the look change a tracking client receives");
                    } finally {
                        HaruhiServices.content().activateUnstored(previous);
                    }
                })
                .thenSucceed();
    }

    /** An explosion next to the NPC pushes it when vanilla knockback reaches it; the NPC must stay on its post. */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void npcIsNotMovedByExplosions(GameTestHelper helper) {
        Scene scene = new Scene();
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.start = scene.npc.position();
                    Vec3 blast = helper.absoluteVec(new Vec3(2.5, 2.0, 0.5));
                    helper.getLevel().explode(null, blast.x, blast.y, blast.z, 4.0F, Level.ExplosionInteraction.NONE);
                })
                .thenIdle(40)
                .thenExecute(() -> helper.assertTrue(
                        scene.npc.position().distanceToSqr(scene.start) < 1.0E-9, "an explosion moved the NPC"))
                .thenSucceed();
    }

    /**
     * A push from outside does not move the NPC. Explosions, wind charges and fishing rods all reach an entity as a push
     * like this one, a velocity added to it, which vanilla then moves it by on the following ticks.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void npcIsNotMovedByAPush(GameTestHelper helper) {
        Scene scene = new Scene();
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.start = scene.npc.position();
                    scene.npc.push(0.3, 1.0, 0.3);
                })
                .thenIdle(40)
                .thenExecute(() -> assertStillAtItsPost(helper, scene, "a push"))
                .thenSucceed();
    }

    /** A piston that extends into the NPC leaves it where it stands. */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void npcIsNotMovedByAPiston(GameTestHelper helper) {
        Scene scene = new Scene();
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    helper.setBlock(new BlockPos(2, 2, 0),
                            Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.SOUTH));
                    // The piston's head extends into the block at z = 1, where the NPC stands.
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 1.5));
                    scene.start = scene.npc.position();
                    helper.setBlock(new BlockPos(1, 2, 0), Blocks.REDSTONE_BLOCK);
                })
                .thenWaitUntil(() -> helper.assertBlockProperty(
                        new BlockPos(2, 2, 0), PistonBaseBlock.EXTENDED, true))
                .thenIdle(5)
                .thenExecute(() -> assertStillAtItsPost(helper, scene, "a piston"))
                .thenSucceed();
    }

    /** An NPC standing in a lit nether portal stays in its own dimension, at its post. */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void npcDoesNotTravelThroughANetherPortal(GameTestHelper helper) {
        Scene scene = new Scene();
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.start = scene.npc.position();
                    helper.setBlock(new BlockPos(2, 2, 2), Blocks.NETHER_PORTAL);
                    helper.setBlock(new BlockPos(2, 3, 2), Blocks.NETHER_PORTAL);
                })
                .thenIdle(40)
                .thenExecute(() -> assertStillAtItsPost(helper, scene, "a nether portal"))
                .thenSucceed();
    }

    /** A lingering cloud of levitation, as a lingering potion leaves, gives the NPC no effect and does not lift it. */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void npcIsNotLiftedByALevitationCloud(GameTestHelper helper) {
        Scene scene = new Scene();
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.start = scene.npc.position();
                    Vec3 at = scene.npc.position();
                    AreaEffectCloud cloud = new AreaEffectCloud(helper.getLevel(), at.x, at.y, at.z);
                    cloud.setRadius(2.0F);
                    cloud.setWaitTime(0);
                    cloud.setDuration(60);
                    cloud.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 200));
                    helper.getLevel().addFreshEntity(cloud);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    helper.assertFalse(scene.npc.hasEffect(MobEffects.LEVITATION), "the cloud gave the NPC levitation");
                    assertStillAtItsPost(helper, scene, "a levitation cloud");
                })
                .thenSucceed();
    }

    /**
     * The NPC's body follows its yaw on the server. The body is not synced, so a client that lags the body by up to 50
     * degrees would show the NPC turned away from the player.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void bodyFollowsTheYaw(GameTestHelper helper) {
        Scene scene = new Scene();
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    scene.npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    scene.npc.setYRot(180.0F);
                    scene.npc.yBodyRot = 0.0F;
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    float body = scene.npc.yBodyRot;
                    float yaw = scene.npc.getYRot();
                    helper.assertTrue(Math.abs(Mth.wrapDegrees(body - yaw)) < 1.0E-3F,
                            "the body (" + body + ") did not follow the yaw (" + yaw + ")");
                })
                .thenSucceed();
    }

    /** A spawned NPC faces the way the command source faces, with its head and body as well as its yaw. */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void spawnFacesTheCommandSource(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    ServerLevel level = helper.getLevel();
                    Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.0, 2.5));
                    List<NpcEntity> before = npcsIn(level);
                    Optional<CommandSyntaxException> failure = runCommand(level, at, 180.0F,
                            String.format(Locale.ROOT, "haruhi npc spawn %s %.3f %.3f %.3f", GREETER, at.x, at.y, at.z));
                    helper.assertTrue(failure.isEmpty(),
                            "the spawn command failed: " + failure.map(e -> e.getMessage()).orElse(""));

                    List<NpcEntity> added = npcsIn(level);
                    added.removeAll(before);
                    helper.assertValueEqual(added.size(), 1, "the spawn command added this many NPCs");
                    NpcEntity npc = added.get(0);
                    helper.assertValueEqual(npc.getYRot(), 180.0F, "the spawned NPC's yaw");
                    helper.assertValueEqual(npc.getYHeadRot(), 180.0F, "the spawned NPC's head yaw");
                    helper.assertValueEqual(npc.yBodyRot, 180.0F, "the spawned NPC's body yaw");
                })
                .thenSucceed();
    }

    /** A spawn above the world is refused, as {@code /summon} refuses it, and adds no NPC. */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void spawnRefusesAPositionOutsideTheWorld(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    ServerLevel level = helper.getLevel();
                    Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.0, 2.5));
                    int before = npcsIn(level).size();
                    Optional<CommandSyntaxException> failure = runCommand(level, at, 0.0F,
                            "haruhi npc spawn " + GREETER + " 0 64000000 0");
                    helper.assertTrue(failure.isPresent(), "a spawn at y=64000000 was accepted");
                    helper.assertValueEqual(npcsIn(level).size(), before, "a refused spawn still added an NPC");
                })
                .thenSucceed();
    }

    /**
     * Every /haruhi npc command is for gamemasters. A source with no permissions is refused each one, and nothing they
     * name changes: no NPC is added, the profile stays, and the NPC is not removed.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void npcCommandsRefuseASourceWithNoPermissions(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    ServerLevel level = helper.getLevel();
                    NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    int before = npcsIn(level).size();
                    // The source runs as the NPC, so @s selects it and no other NPC left in the test area.
                    CommandSourceStack player = consoleSourceAt(level, npc.position(), 0.0F)
                            .withEntity(npc)
                            .withPermission(PermissionSet.NO_PERMISSIONS);

                    for (String command : new String[] {
                            "haruhi npc spawn " + STYLED, "haruhi npc set @s " + STYLED, "haruhi npc remove @s",
                            "haruhi npc list"}) {
                        helper.assertTrue(runCommand(player, command).isPresent(),
                                "a source with no permissions ran: " + command);
                    }
                    helper.assertValueEqual(npcsIn(level).size(), before,
                            "a refused npc command changed the NPCs in the level");
                    helper.assertValueEqual(npc.profileId().orElse(null), GREETER,
                            "a refused npc set changed the NPC's profile");
                    helper.assertFalse(npc.isRemoved(), "a refused npc remove removed the NPC");
                })
                .thenSucceed();
    }

    /**
     * A gamemaster runs each /haruhi npc command and sees its effect: spawn adds an NPC with the profile, set changes
     * that profile, list runs, and remove takes the NPC out of the level.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void gamemasterRunsEveryNpcCommand(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    ServerLevel level = helper.getLevel();
                    Vec3 at = helper.absoluteVec(new Vec3(2.5, 2.0, 2.5));
                    List<NpcEntity> before = npcsIn(level);
                    CommandSourceStack gamemaster = consoleSourceAt(level, at, 0.0F)
                            .withPermission(LevelBasedPermissionSet.GAMEMASTER);

                    Optional<CommandSyntaxException> spawned = runCommand(gamemaster, "haruhi npc spawn " + GREETER);
                    helper.assertTrue(spawned.isEmpty(),
                            "the spawn command failed: " + spawned.map(e -> e.getMessage()).orElse(""));
                    List<NpcEntity> added = npcsIn(level);
                    added.removeAll(before);
                    helper.assertValueEqual(added.size(), 1, "the spawn command added this many NPCs");
                    NpcEntity npc = added.get(0);
                    helper.assertValueEqual(npc.profileId().orElse(null), GREETER, "the spawned NPC's profile");

                    // The source runs as the new NPC, so @s selects it.
                    CommandSourceStack asNpc = consoleSourceAt(level, npc.position(), 0.0F)
                            .withEntity(npc)
                            .withPermission(LevelBasedPermissionSet.GAMEMASTER);
                    Optional<CommandSyntaxException> set = runCommand(asNpc, "haruhi npc set @s " + STYLED);
                    helper.assertTrue(set.isEmpty(),
                            "the set command failed: " + set.map(e -> e.getMessage()).orElse(""));
                    helper.assertValueEqual(npc.profileId().orElse(null), STYLED, "the profile after the set command");

                    Optional<CommandSyntaxException> listed = runCommand(asNpc, "haruhi npc list");
                    helper.assertTrue(listed.isEmpty(),
                            "the list command failed: " + listed.map(e -> e.getMessage()).orElse(""));

                    Optional<CommandSyntaxException> removed = runCommand(asNpc, "haruhi npc remove @s");
                    helper.assertTrue(removed.isEmpty(),
                            "the remove command failed: " + removed.map(e -> e.getMessage()).orElse(""));
                    helper.assertTrue(npc.isRemoved(), "the remove command did not remove the NPC");
                    helper.assertValueEqual(npcsIn(level).size(), before.size(),
                            "the remove command changed the number of NPCs in the level");
                })
                .thenSucceed();
    }

    /**
     * A name tag in the off hand does not rename the NPC. A vanilla client never sends an off-hand interaction, so the
     * server must not let a forged one reach the item.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 20)
    public void offHandCannotRenameAnNpc(GameTestHelper helper) {
        afterStartupContent(helper)
                .thenExecute(() -> {
                    activateFixtures();
                    ServerPlayer player = mockPlayer(helper);
                    NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
                    player.setPos(npc.getX() + 1.0, npc.getY(), npc.getZ());
                    ItemStack nameTag = new ItemStack(Items.NAME_TAG);
                    nameTag.set(DataComponents.CUSTOM_NAME, Component.literal("Renamed"));
                    player.setItemInHand(InteractionHand.OFF_HAND, nameTag);

                    player.interactOn(npc, InteractionHand.OFF_HAND, npc.position());
                    helper.assertValueEqual(npc.getCustomName().getString(), "Greeter",
                            "an off-hand name tag renamed the NPC");
                })
                .thenSucceed();
    }

    /** Fails unless the scene's NPC is still in the test level, where it was spawned. */
    private static void assertStillAtItsPost(GameTestHelper helper, Scene scene, String cause) {
        helper.assertFalse(scene.npc.isRemoved(), cause + " removed the NPC from the level: " + scene.npc.getRemovalReason());
        helper.assertTrue(scene.npc.level() == helper.getLevel(), cause + " took the NPC to " + scene.npc.level().dimension());
        helper.assertTrue(scene.npc.position().distanceToSqr(scene.start) < 1.0E-9,
                cause + " moved the NPC from " + scene.start + " to " + scene.npc.position());
    }

    /** Waits for the startup content activation before a test activates its own content. */
    private static GameTestSequence afterStartupContent(GameTestHelper helper) {
        return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                HaruhiServices.content().isStartupComplete(), "the startup content activation has not finished"));
    }

    /** Validates and activates the fixture bundle, so the NPC profiles and their dialogue exist for the tests. */
    private static void activateFixtures() {
        activate(FIXTURE);
    }

    private static void activate(String fixture) {
        ContentBundle bundle = HaruhiServices.content().parseAndValidate(readFixture(fixture)).getOrThrow();
        HaruhiServices.content().activateUnstored(bundle);
    }

    /**
     * Writes {@code values} as the server's {@link ClientboundSetEntityDataPacket} for {@code sender}, reads the packet
     * back, and applies it to {@code receiver} the way a client applies it to its copy of the entity.
     */
    private static void receive(GameTestHelper helper, NpcEntity sender, NpcEntity receiver,
            List<SynchedEntityData.DataValue<?>> values) {
        helper.assertTrue(values != null && !values.isEmpty(), "the server has no entity data to send");
        RegistryFriendlyByteBuf buffer =
                new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer, new ClientboundSetEntityDataPacket(sender.getId(), values));
        ClientboundSetEntityDataPacket received = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer);
        receiver.getEntityData().assignValues(received.packedItems());
    }

    /** Saves {@code npc} and loads the saved data into a new NPC entity, which has not been added to the level. */
    private static NpcEntity saveAndLoad(GameTestHelper helper, NpcEntity npc) {
        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        npc.saveWithoutId(output);
        CompoundTag saved = output.buildResult();
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved);

        NpcEntity loaded = NpcEntities.NPC.create(helper.getLevel(), EntitySpawnReason.LOAD);
        helper.assertTrue(loaded != null, "the NPC type could not create an entity");
        loaded.load(input);
        return loaded;
    }

    /** A survival player in the test level. The helper's own factory is deprecated for removal. */
    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        return (ServerPlayer) helper.makeMockServerPlayer(GameType.SURVIVAL);
    }

    /** A player who joins the server, which starts loading their dialogue flags, and stands 1 block from {@code npc}. */
    // makeMockServerPlayerInLevel is the only 26.2 helper whose player joins (fires JOIN) and has a connection.
    @SuppressWarnings("removal")
    private static ServerPlayer joinNextTo(GameTestHelper helper, NpcEntity npc) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(npc.getX() + 1.0, npc.getY(), npc.getZ());
        return player;
    }

    /** Fails the current check unless {@code player} is in the greeter's dialogue, at its start node, with {@code npc}. */
    private static void assertTalkingTo(GameTestHelper helper, ServerPlayer player, NpcEntity npc) {
        Optional<DialogueSession> session = DialogueModule.sessions().session(player);
        helper.assertTrue(session.isPresent(), "no conversation has started");
        helper.assertValueEqual(session.get().graphId(), GREETER, "the graph of the conversation");
        helper.assertTrue(session.get().interlocutor() == npc, "the right-clicked NPC is not the interlocutor");
        helper.assertValueEqual(session.get().presentedNode(), "hello", "the node the conversation shows");
    }

    private static NpcEntity spawn(GameTestHelper helper, Identifier profile, Vec3 relative) {
        ServerLevel level = helper.getLevel();
        NpcEntity npc = NpcEntities.NPC.create(level, EntitySpawnReason.COMMAND);
        helper.assertTrue(npc != null, "the NPC type could not create an entity");
        Vec3 world = helper.absoluteVec(relative);
        npc.setPos(world.x, world.y, world.z);
        npc.setProfile(profile);
        level.addFreshEntity(npc);
        return npc;
    }

    private static List<NpcEntity> npcsIn(ServerLevel level) {
        List<NpcEntity> npcs = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof NpcEntity npc) {
                npcs.add(npc);
            }
        }
        return npcs;
    }

    /**
     * The server console's source, which has every permission, standing at {@code at} and facing {@code yaw} in
     * {@code level}. A test narrows it with {@code withPermission} or {@code withEntity} before running a command.
     */
    private static CommandSourceStack consoleSourceAt(ServerLevel level, Vec3 at, float yaw) {
        return level.getServer().createCommandSourceStack()
                .withLevel(level)
                .withPosition(at)
                .withRotation(new Vec2(0.0F, yaw));
    }

    /**
     * Runs {@code command} as the server console standing at {@code at} and facing {@code yaw}, through the server's
     * command dispatcher. Returns the failure the command throws, or empty when it succeeds.
     */
    private static Optional<CommandSyntaxException> runCommand(ServerLevel level, Vec3 at, float yaw, String command) {
        return runCommand(consoleSourceAt(level, at, yaw), command);
    }

    /**
     * Runs {@code command} from {@code source} through the server's command dispatcher. Returns the failure the command
     * throws, or empty when it succeeds.
     */
    private static Optional<CommandSyntaxException> runCommand(CommandSourceStack source, String command) {
        try {
            source.getServer().getCommands().getDispatcher().execute(command, source);
            return Optional.empty();
        } catch (CommandSyntaxException failure) {
            return Optional.of(failure);
        }
    }

    private static String readFixture(String fixture) {
        try (InputStream in = NpcGameTests.class.getResourceAsStream(fixture)) {
            if (in == null) {
                throw new IllegalStateException("missing GameTest fixture " + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** What a test's first step sets up and its later steps read. */
    private static final class Scene {
        NpcEntity npc;
        ServerPlayer player;
        Vec3 start;
    }
}
