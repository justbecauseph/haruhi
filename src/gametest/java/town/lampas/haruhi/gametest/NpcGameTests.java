package town.lampas.haruhi.gametest;

import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
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
 */
public final class NpcGameTests {
    private static final String ENVIRONMENT = "haruhi-gametest:npc";
    private static final String FIXTURE = "/haruhi/fixtures/npc/npc_bundle.json";
    /** The fixture with the styled profile changed: the Alex skin, and no model or animations. */
    private static final String RESTYLED_FIXTURE = "/haruhi/fixtures/npc/npc_bundle_restyled.json";
    private static final Identifier GREETER = Identifier.fromNamespaceAndPath("haruhi", "fixture/greeter");
    private static final Identifier STYLED = Identifier.fromNamespaceAndPath("haruhi", "fixture/styled");

    @GameTest(environment = ENVIRONMENT, maxTicks = 120)
    public void npcDoesNotMoveOverOneHundredTicks(GameTestHelper helper) {
        activateFixtures();
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
        Vec3 start = npc.position();
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(npc.position().distanceToSqr(start) < 1.0E-9, "the NPC moved on its own");
            helper.succeed();
        });
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = 1)
    public void npcIgnoresPlayerDamageButNotKill(GameTestHelper helper) {
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
        helper.succeed();
    }

    @GameTest(environment = ENVIRONMENT, maxTicks = 1)
    public void profileSurvivesSaveAndLoad(GameTestHelper helper) {
        activateFixtures();
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));

        TagValueOutput output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        npc.saveWithoutId(output);
        CompoundTag saved = output.buildResult();
        ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved);

        NpcEntity loaded = NpcEntities.NPC.create(helper.getLevel(), EntitySpawnReason.LOAD);
        helper.assertTrue(loaded != null, "the NPC type could not create an entity");
        loaded.load(input);
        helper.assertValueEqual(loaded.profileId().orElse(null), GREETER, "the profile was lost on load");
        helper.succeed();
    }

    /**
     * A player whose dialogue flags have loaded right-clicks the NPC from within reach, and the profile's dialogue starts
     * in the same tick with the NPC as the interlocutor. Joining starts the flag load off the server thread, so the test
     * waits for it before the right-click.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 100)
    public void rightClickOpensTheDialogue(GameTestHelper helper) {
        activateFixtures();
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
        ServerPlayer player = joinNextTo(helper, npc);
        DialogueSessions sessions = DialogueModule.sessions();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(sessions.loadedFlags(player).isPresent(),
                        "the joined player's dialogue flags have not loaded"))
                .thenExecute(() -> {
                    npc.interact(player, InteractionHand.MAIN_HAND, npc.position());
                    helper.assertTrue(HaruhiServices.dialogue().isInDialogue(player),
                            "right-click did not open the dialogue");
                    assertTalkingTo(helper, player, npc);
                    HaruhiServices.dialogue().close(player);
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
        activateFixtures();
        NpcEntity npc = spawn(helper, GREETER, new Vec3(2.5, 2.0, 2.5));
        ServerPlayer player = joinNextTo(helper, npc);
        DialogueSessions sessions = DialogueModule.sessions();

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(sessions.loadedFlags(player).isPresent(),
                        "the joined player's dialogue flags have not loaded"))
                .thenExecute(() -> {
                    // Drop the player's cached flags, as quitting does, so the right-click below finds them unloaded
                    // and queues its open behind a fresh load.
                    sessions.onLeave(player);
                    helper.assertTrue(sessions.loadedFlags(player).isEmpty(), "the player's flags are still cached");
                    npc.interact(player, InteractionHand.MAIN_HAND, npc.position());
                })
                .thenWaitUntil(() -> assertTalkingTo(helper, player, npc))
                .thenExecute(() -> HaruhiServices.dialogue().close(player))
                .thenSucceed();
    }

    /**
     * The styled look reaches a tracking client through the vanilla entity data packet, and activating a revision that
     * changes the profile sends the new look. Everything runs in one tick and the previous content is active again at
     * the end, so the other tests in the batch never see the swap.
     */
    @GameTest(environment = ENVIRONMENT, maxTicks = 1)
    public void lookSyncsToTrackingClientsAndFollowsContentActivation(GameTestHelper helper) {
        ContentBundle previous = HaruhiServices.content().active();
        try {
            activateFixtures();
            NpcEntity npc = spawn(helper, STYLED, new Vec3(2.5, 2.0, 2.5));
            NpcLook styled = new NpcLook(
                    Optional.of(Identifier.fromNamespaceAndPath("haruhi", "fixture/styled")),
                    Identifier.fromNamespaceAndPath("haruhi", "textures/npc/fixture/styled.png"),
                    Optional.of(Identifier.fromNamespaceAndPath("haruhi", "fixture/styled")));
            helper.assertValueEqual(npc.look(), styled, "the server look of the styled profile");

            NpcEntity tracking = NpcEntities.NPC.create(helper.getLevel(), EntitySpawnReason.LOAD);
            helper.assertTrue(tracking != null, "the NPC type could not create an entity");
            // What the server sends when a client starts tracking the entity.
            receive(helper, npc, tracking, npc.getEntityData().getNonDefaultValues());
            helper.assertValueEqual(tracking.look(), styled, "the look a tracking client receives");

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
        helper.succeed();
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
}
