package town.lampas.haruhi.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.DataResult;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.DialogueConfig;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.DialogueSessions;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueAnchor;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.dialogue.builtin.Builtins;
import town.lampas.haruhi.dialogue.flags.CharacterStore;
import town.lampas.haruhi.dialogue.presenter.VanillaDialogs;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;
import town.lampas.haruhi.network.DialogueChoicePayload;
import town.lampas.haruhi.storage.FlagChange;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.HaruhiStorage;

/**
 * Server GameTests for the dialogue runtime. Every test that activates content first waits for the startup activation,
 * then activates the one fixture bundle in the same step as each open, for a player whose flags are already loaded, or
 * whose load the test holds and releases in that step. The open then reads the fixture before any other activation can
 * run, and a running conversation keeps the bundle it started with, so neither the startup activation nor another test
 * in the batch can replace the fixture under a test.
 *
 * <p>A mock player opens graphs of that bundle and picks entries through {@link DialogueSessions#choose}, the same call
 * the payload receiver makes, or as vanilla custom-click packets, which the dialogue mixin routes to the vanilla
 * presenter. The mock has no Haruhi channel, so the vanilla presenter runs. Tests that need to see what the server sends,
 * or to hold a storage call, use a mock built here with its own channel, or a second {@link DialogueSessions} over a
 * storage that holds the results of one method.
 *
 * <p>{@code makeMockServerPlayerInLevel} is deprecated for removal, but it is the only 26.2 helper whose player joins
 * (fires JOIN) and has a connection, which the flag loads and the vanilla presenter need.
 */
@SuppressWarnings("removal")
public final class DialogueGameTests {
    private static final Identifier CLERK = Identifier.fromNamespaceAndPath("haruhi", "fixture/clerk");
    private static final Identifier GATE = Identifier.fromNamespaceAndPath("haruhi", "fixture/gate");
    private static final Identifier REWARD = Identifier.fromNamespaceAndPath("haruhi", "fixture/reward");
    private static final Identifier WORLD = Identifier.fromNamespaceAndPath("haruhi", "fixture/world");
    private static final Identifier CHAIN_FIRST = Identifier.fromNamespaceAndPath("haruhi", "fixture/chain0");
    private static final Identifier PROBE = Identifier.fromNamespaceAndPath("haruhi", "fixture/probe");
    private static final Identifier GREETER = Identifier.fromNamespaceAndPath("haruhi", "fixture/greeter");
    private static final FlagKey GREETER_MET = new FlagKey("haruhi:fixture/greeter", "met");
    private static final String NICE_TO_MEET_YOU = "Nice to meet you.";
    /** Where the built-ins that a test parses by hand are parsed: no bundle, so they may not use flags. */
    private static final ParseContext PROBE_PARSE = new ParseContext(ContentBundle.EMPTY, PROBE, "probe");
    private static final String FIXTURE = "/haruhi/fixtures/dialogue/bundle.json";
    private static final FlagKey MET = new FlagKey("haruhi:fixture/clerk", "met");
    private static final FlagKey ONCE = new FlagKey("haruhi:fixture/reward", "got_reward");
    private static final String REMEMBER = "Remember me?";
    private static final String TELL_ME_MORE = "Tell me more.";
    private static final String LEAVE = "Leave.";
    private static final String LOCKED_DOOR = "Locked door";
    private static final String GATE_TEXT = "The door is locked.";
    private static final int TICKS_PER_DAY = 24000;

    @GameTest(maxTicks = 200)
    public void openWaitsForTheFlagsAndThenShowsTheStartNode(GameTestHelper helper) {
        HeldStorage storage = new HeldStorage(HaruhiServices.storage(), "loadVisits");
        DialogueSessions held = new DialogueSessions(new CharacterStore(storage::storage));
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = helper.makeMockServerPlayerInLevel();
                    activateFixture(helper);
                    helper.assertTrue(held.open(subject.player, CLERK, null), "the fixture graph should open");
                    helper.assertTrue(held.session(subject.player).isEmpty(),
                            "no conversation starts before the flags load");
                    helper.assertTrue(held.isInDialogue(subject.player), "the queued open counts as in dialogue");
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.answered(), "storage answered the held load"))
                .thenExecute(() -> {
                    helper.assertTrue(held.session(subject.player).isEmpty(), "the open waits while the load is held");
                    activateFixture(helper);
                    storage.release();
                    DialogueSession session = presentedAt(helper, held, subject.player, "greet");
                    helper.assertTrue(visibleIndex(session, TELL_ME_MORE) >= 0,
                            "the first conversation offers Tell me more.");
                    helper.assertTrue(visibleIndex(session, REMEMBER) < 0,
                            "Remember me? is hidden before the flag is set");
                    held.onLeave(subject.player);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void choosingAnEntryMovesAlongNextAndContinueEndsTheConversation(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> openNow(helper, sessions(), subject.player, CLERK))
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "greet");
                    sessions().choose(subject.player, session.id(), "greet", indexOf(helper, session, LEAVE));
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "bye");
                    sessions().choose(subject.player, session.id(), "bye", DialogueChoicePayload.CONTINUE);
                })
                .thenExecute(() -> helper.assertFalse(sessions().isInDialogue(subject.player),
                        "continue on the last node ends the conversation"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aFlagSetInOneConversationChangesTheBranchOfTheNext(GameTestHelper helper) {
        Subject subject = new Subject();
        int[] firstSession = {0};
        joinLoaded(helper, subject)
                .thenExecute(() -> firstSession[0] = openNow(helper, sessions(), subject.player, CLERK).id())
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "greet");
                    sessions().choose(subject.player, session.id(), "greet", indexOf(helper, session, TELL_ME_MORE));
                })
                .thenWaitUntil(() -> {
                    // info has no choices and next "bye", so its continue moves to bye (DialogueNode).
                    DialogueSession session = presentedAt(helper, subject.player, "info");
                    sessions().choose(subject.player, session.id(), "info", DialogueChoicePayload.CONTINUE);
                })
                .thenWaitUntil(() -> {
                    // bye has no choices and no next, so its continue ends the conversation.
                    DialogueSession session = presentedAt(helper, subject.player, "bye");
                    helper.assertTrue(session.id() == firstSession[0], "info's continue stays in the first conversation");
                    sessions().choose(subject.player, session.id(), "bye", DialogueChoicePayload.CONTINUE);
                })
                .thenExecute(() -> {
                    helper.assertFalse(sessions().isInDialogue(subject.player), "the first conversation ended");
                    DialogueSession second = openNow(helper, sessions(), subject.player, CLERK);
                    helper.assertValueEqual(second.presentedNode(), "greet", "the node the second conversation shows");
                    helper.assertTrue(second.id() != firstSession[0], "a new conversation has a new session id");
                    helper.assertTrue(visibleIndex(second, REMEMBER) >= 0,
                            "the flag set in the first conversation shows Remember me? in the next");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aRouterWhoseBranchConditionFailsMovesToItsNext(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    DialogueSession session = openNow(helper, sessions(), subject.player, GREETER);
                    helper.assertValueEqual(session.presentedNode(), "first_meeting",
                            "a player who has not met the greeter goes to the router's next");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aRouterTakesTheBranchWhoseConditionHolds(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    flagsOf(helper, subject.player).set(GREETER_MET, 1);
                    DialogueSession session = openNow(helper, sessions(), subject.player, GREETER);
                    helper.assertValueEqual(session.presentedNode(), "welcome_back",
                            "a player who has met the greeter takes the branch gated on met");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aRouterReadsAFlagThatThePickLeadingToItSetInTheSameStep(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    DialogueSession first = openNow(helper, sessions(), subject.player, GREETER);
                    helper.assertValueEqual(first.presentedNode(), "first_meeting", "the node a new player sees");
                    // The choice sets met and moves to the router, which tests met before anything is sent.
                    sessions().choose(subject.player, first.id(), "first_meeting",
                            indexOf(helper, first, NICE_TO_MEET_YOU));
                    DialogueSession after = presentedAt(helper, subject.player, "welcome_back");
                    helper.assertTrue(after.id() == first.id(), "the pick stays in the same conversation");
                    helper.assertTrue(flagsOf(helper, subject.player).isSet(GREETER_MET), "the pick set met");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void flagsSurviveDroppingTheCacheAndReloadingFromStorage(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> openNow(helper, sessions(), subject.player, CLERK))
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "greet");
                    sessions().choose(subject.player, session.id(), "greet", indexOf(helper, session, TELL_ME_MORE));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(flagsOf(helper, subject.player).get(MET), 1,
                        "the entry set met in the cache"))
                .thenExecute(() -> {
                    // Quitting drops the cache and flushes the pending batch; joining again reads storage.
                    sessions().onLeave(subject.player);
                    helper.assertTrue(sessions().loadedFlags(subject.player).isEmpty(), "the cache is dropped on leave");
                    sessions().onJoin(subject.player);
                })
                .thenWaitUntil(() -> assertStored(helper, subject, MET, 1))
                .thenWaitUntil(() -> flagsOf(helper, subject.player))
                .thenExecute(() -> {
                    DialogueSession reopened = openNow(helper, sessions(), subject.player, CLERK);
                    helper.assertTrue(visibleIndex(reopened, REMEMBER) >= 0, "met was read back after the flush");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aForgedChoiceIndexOrStaleSessionDoesNothing(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    DialogueSession session = openNow(helper, sessions(), subject.player, CLERK);
                    int shown = session.presented().size();
                    sessions().choose(subject.player, session.id(), "greet", 99);
                    sessions().choose(subject.player, session.id(), "greet", -7);
                    sessions().choose(subject.player, session.id() + 1, "greet", indexOf(helper, session, TELL_ME_MORE));
                    sessions().choose(subject.player, session.id(), "info", indexOf(helper, session, TELL_ME_MORE));

                    helper.assertTrue(sessions().session(subject.player).orElseThrow() == session,
                            "the same conversation is still open");
                    helper.assertValueEqual(session.presentedNode(), "greet", "the node the conversation shows");
                    helper.assertValueEqual(session.presented().size(), shown, "the shown entries did not change");
                    helper.assertValueEqual(flagsOf(helper, subject.player).get(MET), 0,
                            "no entry ran, so met is still unset");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void anOpenWaitingForTheFlagsIsInDialogueAndCloseCancelsIt(GameTestHelper helper) {
        HeldStorage storage = new HeldStorage(HaruhiServices.storage(), "loadVisits");
        DialogueSessions held = new DialogueSessions(new CharacterStore(storage::storage));
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = helper.makeMockServerPlayerInLevel();
                    activateFixture(helper);
                    helper.assertTrue(held.open(subject.player, CLERK, null), "the fixture graph should open");
                    helper.assertTrue(held.session(subject.player).isEmpty(), "the load is held, so the open is queued");
                    helper.assertTrue(held.isInDialogue(subject.player),
                            "an opened conversation counts before its flags load");
                    held.close(subject.player);
                    helper.assertFalse(held.isInDialogue(subject.player), "close ends the conversation, queued or not");
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.answered(), "storage answered the held load"))
                .thenExecute(() -> {
                    // The fixture is active when the load finishes, so only the close keeps the open from starting.
                    activateFixture(helper);
                    storage.release();
                })
                .thenWaitUntil(() -> helper.assertTrue(held.loadedFlags(subject.player).isPresent(), "the flags loaded"))
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(held.session(subject.player).isEmpty(),
                            "the closed conversation did not start once the flags loaded");
                    held.onLeave(subject.player);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void openingAGraphOutsideTheActiveContentReturnsFalse(GameTestHelper helper) {
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    activateFixture(helper);
                    helper.assertFalse(sessions().open(player,
                                    Identifier.fromNamespaceAndPath("haruhi", "fixture/missing"), null),
                            "a graph outside the active content does not open");
                    helper.assertFalse(sessions().isInDialogue(player), "nothing was queued for it either");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aRespawnEndsTheConversationAndTheNextOneRunsOnTheNewEntity(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> openNow(helper, sessions(), subject.player, CLERK))
                .thenExecute(() -> {
                    subject.player = respawn(helper, subject.player);
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "the respawn ends the conversation, whose entity is gone");
                    helper.assertTrue(sessions().loadedFlags(subject.player).isPresent(),
                            "the flags carry over to the new entity");
                    DialogueSession session = openNow(helper, sessions(), subject.player, CLERK);
                    helper.assertTrue(session.player() == subject.player, "the conversation runs on the new entity");
                })
                .thenIdle(5)
                .thenExecute(() -> presentedAt(helper, subject.player, "greet"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aQueuedOpenSurvivesARespawnAndItsRewardReachesTheNewEntity(GameTestHelper helper) {
        HeldStorage storage = new HeldStorage(HaruhiServices.storage(), "loadVisits");
        DialogueSessions held = new DialogueSessions(new CharacterStore(storage::storage));
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = helper.makeMockServerPlayerInLevel();
                    activateFixture(helper);
                    helper.assertTrue(held.open(subject.player, REWARD, null), "the reward graph should open");
                    helper.assertTrue(held.session(subject.player).isEmpty(), "the load is held, so the open is queued");
                    // The entity is replaced before the queued open runs. The module's AFTER_RESPAWN handler makes this
                    // call for the module's service; this service belongs to the test, so the test makes it.
                    subject.player = respawn(helper, subject.player);
                    held.onRespawn(subject.player);
                    helper.assertTrue(held.isInDialogue(subject.player), "the queued open is kept for the new entity");
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.answered(), "storage answered the held load"))
                .thenExecute(() -> {
                    activateFixture(helper);
                    storage.release();
                    DialogueSession session = presentedAt(helper, held, subject.player, "hand_over");
                    helper.assertTrue(session.player() == subject.player, "the queued conversation runs on the new entity");
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countOf(subject.player, Items.BOOK), 1,
                        "books in the live player's inventory"))
                .thenExecute(() -> {
                    helper.assertValueEqual(flagsOf(helper, held, subject.player).get(ONCE), 1, "the once flag");
                    openNow(helper, held, subject.player, REWARD);
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    helper.assertValueEqual(countOf(subject.player, Items.BOOK), 1,
                            "books after a second visit, which the once flag refuses");
                    held.onLeave(subject.player);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aRewardIsGivenOnlyOnceItsFlagIsStoredAndReachesTheEntityThatRespawnedMeanwhile(GameTestHelper helper) {
        HeldStorage storage = new HeldStorage(HaruhiServices.storage(), "writeFlags");
        DialogueSessions held = new DialogueSessions(new CharacterStore(storage::storage));
        Subject subject = new Subject();
        startWithLoadedFlags(helper, held, subject)
                .thenExecute(() -> openNow(helper, held, subject.player, REWARD))
                .thenWaitUntil(() -> assertWrittenThroughAndNotGiven(helper, storage, subject))
                .thenExecute(() -> {
                    subject.first = subject.player;
                    subject.player = respawn(helper, subject.player);
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.answered(), "storage has the once flag"))
                .thenExecute(() -> {
                    helper.assertValueEqual(countOf(subject.player, Items.BOOK), 0,
                            "books given while the write has not reported done");
                    storage.release();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countOf(subject.player, Items.BOOK), 1,
                        "books in the inventory of the entity that respawned"))
                .thenExecute(() -> helper.assertValueEqual(countOf(subject.first, Items.BOOK), 0,
                        "books in the removed entity's inventory"))
                .thenWaitUntil(() -> assertStored(helper, subject, ONCE, 1))
                .thenExecute(() -> {
                    int writes = storage.calls().size();
                    openNow(helper, held, subject.player, REWARD);
                    helper.assertValueEqual(storage.calls().size(), writes,
                            "flag writes after a second visit, which the set once flag refuses before writing");
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    helper.assertValueEqual(countOf(subject.player, Items.BOOK), 1,
                            "books after a second visit, which the once flag refuses");
                    held.onLeave(subject.player);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aRewardWhoseWriteCompletesWhileThePlayerIsDeadIsGivenAfterTheRespawn(GameTestHelper helper) {
        HeldStorage storage = new HeldStorage(HaruhiServices.storage(), "writeFlags");
        DialogueSessions held = new DialogueSessions(new CharacterStore(storage::storage));
        Subject subject = new Subject();
        startWithLoadedFlags(helper, held, subject)
                .thenExecute(() -> openNow(helper, held, subject.player, REWARD))
                .thenWaitUntil(() -> assertWrittenThroughAndNotGiven(helper, storage, subject))
                .thenExecute(() -> {
                    // A joining player cannot be hurt until its client reports the world loaded, as a real client does.
                    subject.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                    subject.player.kill(subject.player.level());
                    helper.assertFalse(subject.player.isAlive(), "the player is dead and has not respawned");
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.answered(), "storage has the once flag"))
                .thenExecute(() -> {
                    // The test world is kept between runs, so books already lying there are counted, not assumed absent.
                    subject.booksAround = booksOnTheGround(subject.player);
                    storage.release();
                })
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(countOf(subject.player, Items.BOOK), 0,
                            "books in the dead entity's inventory, which is gone");
                    helper.assertValueEqual(booksOnTheGround(subject.player), subject.booksAround,
                            "books on the ground where the player died, where lava or the void could destroy them");
                    subject.player = respawn(helper, subject.player);
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countOf(subject.player, Items.BOOK), 1,
                        "books in the inventory of the entity that respawned"))
                .thenWaitUntil(() -> assertStored(helper, subject, ONCE, 1))
                .thenExecute(() -> held.onLeave(subject.player))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aRewardWhoseWriteCompletesWhileThePlayerIsOfflineIsGivenWhenTheyRejoin(GameTestHelper helper) {
        HeldStorage storage = new HeldStorage(HaruhiServices.storage(), "writeFlags");
        DialogueSessions held = new DialogueSessions(new CharacterStore(storage::storage));
        Subject subject = new Subject();
        startWithLoadedFlags(helper, held, subject)
                .thenExecute(() -> openNow(helper, held, subject.player, REWARD))
                .thenWaitUntil(() -> assertWrittenThroughAndNotGiven(helper, storage, subject))
                .thenExecute(() -> {
                    subject.first = subject.player;
                    subject.player.connection.onDisconnect(
                            new DisconnectionDetails(Component.literal("left while the reward was being written")));
                    helper.assertTrue(
                            helper.getLevel().getServer().getPlayerList().getPlayer(subject.first.getUUID()) == null,
                            "the player is offline");
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.answered(), "storage has the once flag"))
                .thenExecute(storage::release)
                .thenIdle(5)
                .thenExecute(() -> {
                    helper.assertValueEqual(countOf(subject.first, Items.BOOK), 0,
                            "books in the inventory of the entity that left");
                    subject.player = joinClient(helper, subject.first.getUUID()).player();
                })
                .thenWaitUntil(() -> helper.assertValueEqual(countOf(subject.player, Items.BOOK), 1,
                        "books in the inventory of the player who rejoined"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aFailedFlagLoadIsRetriedWhileThePlayerIsOnline(GameTestHelper helper) {
        AtomicInteger failures = new AtomicInteger(1);
        HaruhiStorage flaky = failingFlagLoads(HaruhiServices.storage(), failures);
        DialogueSessions retrying = new DialogueSessions(new CharacterStore(() -> flaky));
        Subject subject = new Subject();
        helper.startSequence()
                .thenExecute(() -> {
                    subject.player = helper.makeMockServerPlayerInLevel();
                    retrying.onJoin(subject.player);
                })
                .thenWaitUntil(() -> {
                    // The first load fails. Each tick the service runs its retries, and the load must come back by itself.
                    retrying.onTick(helper.getLevel().getServer());
                    helper.assertTrue(retrying.loadedFlags(subject.player).isPresent(),
                            "the flags load again without a new open");
                })
                .thenExecute(() -> {
                    helper.assertValueEqual(failures.get(), 0, "the injected load failures left");
                    retrying.onLeave(subject.player);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aVanillaDialogClickIsHandledLikeAPick(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    DialogueSession session = openNow(helper, sessions(), subject.player, CLERK);
                    clickVanilla(subject.player, VanillaDialogs.CHOICE_CLICK,
                            choicePayload(session.id(), "greet", indexOf(helper, session, TELL_ME_MORE)));
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "info"))
                .thenExecute(() -> {
                    DialogueSession session = sessions().session(subject.player).orElseThrow();
                    clickVanilla(subject.player, VanillaDialogs.CHOICE_CLICK, choicePayload(session.id(), "greet", 0));
                    helper.assertValueEqual(session.presentedNode(), "info",
                            "the node after a click for a node not shown");

                    clickVanilla(subject.player, VanillaDialogs.DISMISS_CLICK, dismissPayload(session.id()));
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "the exit button ends the conversation");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aGrayedOutVanillaChoiceSendsItsClickAndTheServerAnswersWithTheNodeAgain(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.client = joinClient(helper, UUID.randomUUID()))
                .thenWaitUntil(() -> flagsOf(helper, subject.client.player()))
                .thenExecute(() -> {
                    ServerPlayer player = subject.client.player();
                    subject.client.dialogsSent();
                    DialogueSession session = openNow(helper, sessions(), player, GATE);
                    helper.assertFalse(session.presented().get(indexOf(helper, session, LOCKED_DOOR)).enabled(),
                            "the door is shown grayed out");

                    // A vanilla client sends the custom click of the button pressed, and nothing for a button without one.
                    MultiActionDialog menu = onlyChoiceDialog(helper, subject.client.dialogsSent(), GATE_TEXT);
                    ClickEvent.Custom locked = clickOf(helper, menu, LOCKED_DOOR);
                    clickVanilla(player, locked.id(), locked.payload());

                    MultiActionDialog answer = onlyChoiceDialog(helper, subject.client.dialogsSent(), GATE_TEXT);
                    helper.assertTrue(sessions().isInDialogue(player), "the grayed-out click leaves the conversation open");
                    presentedAt(helper, player, "menu");
                    ClickEvent.Custom leave = clickOf(helper, answer, LEAVE);
                    clickVanilla(player, leave.id(), leave.payload());
                    presentedAt(helper, player, "bye");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void worldConditionsReadTheItemsTagsAndPermissionOfThePlayer(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    subject.player.getInventory().placeItemBackInInventory(new ItemStack(Items.APPLE, 3));
                    subject.player.addTag("guild_member");
                    DialogueSession session = openNow(helper, sessions(), subject.player, WORLD);
                    helper.assertValueEqual(session.presentedNode(), "check", "the node the world graph starts at");
                    helper.assertTrue(visibleIndex(session, "Three apples.") >= 0,
                            "three apples meet has_item count 3");
                    helper.assertTrue(visibleIndex(session, "Four apples.") < 0,
                            "three apples do not meet has_item count 4");
                    helper.assertTrue(visibleIndex(session, "Tagged.") >= 0,
                            "the guild_member tag is on the player");
                    helper.assertTrue(visibleIndex(session, "Anyone.") >= 0,
                            "permission level 0 is always met");
                    helper.assertTrue(visibleIndex(session, "Admins only.") < 0,
                            "a player who is not an operator lacks permission level 4");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 20)
    public void weatherTimeAdvancementAndPredicateConditionsReadTheWorld(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    PlayerContext context = new PlayerContext(player);
                    // Each check changes the world and puts it back in this one step, so no other test sees the change.
                    checkWeather(helper, player.level(), context);
                    checkTime(helper, player.level(), context);
                    checkAdvancements(helper, player, context);
                    helper.assertFalse(condition(helper, "haruhi:predicate",
                                    "{\"predicate\":\"haruhi:fixture/no_such_predicate\"}").test(context),
                            "a predicate the server does not have tests false");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 20)
    public void aCommandActionRunsAtThePermissionLevelOfTheDialogueConfig(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    ServerPlayer player = helper.makeMockServerPlayerInLevel();
                    PlayerContext context = new PlayerContext(player);
                    // tag needs permission level 2 (Commands.LEVEL_GAMEMASTERS).
                    commandAt(helper, 1, "tag @s add haruhi_ran_at_level_1").run(context);
                    commandAt(helper, 2, "/tag @s add haruhi_ran_at_level_2").run(context);
                    helper.assertFalse(player.entityTags().contains("haruhi_ran_at_level_1"),
                            "a config of level 1 does not let tag run, whatever the player's own level");
                    helper.assertTrue(player.entityTags().contains("haruhi_ran_at_level_2"),
                            "a config of level 2 runs tag as the player");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 20)
    public void messageAndSoundActionsReachThePlayer(GameTestHelper helper) {
        helper.startSequence()
                .thenExecute(() -> {
                    Client client = joinClient(helper, UUID.randomUUID());
                    PlayerContext context = new PlayerContext(client.player());
                    client.sent();
                    action(helper, "haruhi:message", "{\"text\":\"Welcome to Maraha.\"}").run(context);
                    action(helper, "haruhi:sound", "{\"sound\":\"minecraft:block.note_block.bell\",\"volume\":0.5}")
                            .run(context);
                    List<Object> sent = client.sent();

                    List<ClientboundSystemChatPacket> chat = sent.stream()
                            .filter(ClientboundSystemChatPacket.class::isInstance)
                            .map(ClientboundSystemChatPacket.class::cast).toList();
                    helper.assertValueEqual(chat.size(), 1, "chat messages sent to the player");
                    helper.assertValueEqual(chat.get(0).content().getString(), "Welcome to Maraha.", "the chat message");
                    helper.assertFalse(chat.get(0).overlay(), "the message goes to chat, not the action bar");

                    List<ClientboundSoundPacket> sounds = sent.stream()
                            .filter(ClientboundSoundPacket.class::isInstance)
                            .map(ClientboundSoundPacket.class::cast).toList();
                    helper.assertValueEqual(sounds.size(), 1, "sounds played to the player");
                    helper.assertValueEqual(sounds.get(0).getSound().value().location(),
                            Identifier.fromNamespaceAndPath("minecraft", "block.note_block.bell"), "the sound played");
                    helper.assertValueEqual(sounds.get(0).getVolume(), 0.5F, "the volume given");
                    helper.assertValueEqual(sounds.get(0).getPitch(), 1.0F, "the pitch, which defaults to 1");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aChainOfOpensPastTheLimitStillReportsTheFirstGraphAsOpened(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    // Ten graphs chained through haruhi:open pass the limit of eight, so the last link does not open.
                    activateFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CHAIN_FIRST, null),
                            "the first graph exists, so open reports it opened");
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "the chain stopped at its limit and no conversation is left open");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void anAnchoredConversationEndsWhenThePlayerWalksAway(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    DialogueAnchor anchor = anchorAtPlayer(subject.player, 4.0);
                    DialogueSession session = openAtNow(helper, subject.player, CLERK, anchor);
                    helper.assertValueEqual(session.anchor(), anchor, "the session keeps the anchor it was opened at");
                    helper.assertTrue(session.interlocutor() == null, "an anchored conversation has no interlocutor");
                    Vec3 start = subject.player.position();
                    subject.player.setPos(start.x + 3.0, start.y, start.z);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(sessions().isInDialogue(subject.player),
                            "three blocks away is within the four-block anchor");
                    Vec3 here = subject.player.position();
                    subject.player.setPos(here.x + 3.0, here.y, here.z);
                })
                .thenWaitUntil(() -> helper.assertFalse(sessions().isInDialogue(subject.player),
                        "six blocks away is past the anchor, so the tick ends the conversation"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aPickFromOutsideTheAnchorEndsTheConversationInsteadOfRunning(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    DialogueSession session = openAtNow(helper, subject.player, CLERK, anchorAtPlayer(subject.player, 4.0));
                    int tellMeMore = indexOf(helper, session, TELL_ME_MORE);
                    Vec3 start = subject.player.position();
                    // Moved and picked in the same step, before any tick could end the conversation.
                    subject.player.setPos(start.x + 10.0, start.y, start.z);
                    sessions().choose(subject.player, session.id(), "greet", tellMeMore);
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "a pick from outside the anchor ends the conversation");
                    helper.assertValueEqual(flagsOf(helper, subject.player).get(MET), 0,
                            "the picked entry did not run, so met is still unset");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void nodeAndChoiceTextIsResolvedForThePlayerWhenSent(GameTestHelper helper) {
        Subject subject = new Subject();
        Identifier scoreGraph = Identifier.fromNamespaceAndPath("haruhi", "fixture/score");
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.client = joinClient(helper, UUID.randomUUID()))
                .thenWaitUntil(() -> flagsOf(helper, subject.client.player()))
                .thenExecute(() -> {
                    ServerPlayer player = subject.client.player();
                    Scoreboard scoreboard = helper.getLevel().getServer().getScoreboard();
                    Objective objective = scoreboard.getObjective("haruhi_fixture_score");
                    if (objective == null) {
                        objective = scoreboard.addObjective("haruhi_fixture_score", ObjectiveCriteria.DUMMY,
                                Component.literal("Fixture score"), ObjectiveCriteria.RenderType.INTEGER, false, null);
                    }
                    scoreboard.getOrCreatePlayerScore(player, objective).set(7);
                    // Two entities match the second score's name, so that part cannot resolve; the rest still must.
                    List<Entity> pair = List.of(
                            helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(1, 2, 1)),
                            helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(2, 2, 1)));
                    pair.forEach(stand -> stand.addTag("haruhi_fixture_pair"));
                    subject.client.dialogsSent();

                    DialogueSession session = openNow(helper, sessions(), player, scoreGraph);
                    MultiActionDialog menu = onlyChoiceDialog(helper, subject.client.dialogsSent(), "Score: 7 / ");
                    helper.assertValueEqual(menu.actions().get(0).button().label().getString(), "Spend 7",
                            "the choice label shows the player's score");
                    helper.assertValueEqual(menu.actions().get(1).button().tooltip().map(Component::getString),
                            Optional.of("Need more than 7"), "the grayed-out choice's unavailable message");
                    helper.assertValueEqual(session.presented().get(0).text().getString(), "Spend ",
                            "the session keeps the text as written, so a changed score cannot break the pick");

                    scoreboard.getOrCreatePlayerScore(player, objective).set(9);
                    sessions().choose(player, session.id(), "show", 0);
                    presentedAt(helper, player, "bye");
                    sessions().close(player);
                    pair.forEach(stand -> stand.discard());
                    scoreboard.removeObjective(objective);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void openAtRefusesAPlayerOutsideTheAnchor(GameTestHelper helper) {
        Subject subject = new Subject();
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    Vec3 here = subject.player.position();
                    DialogueAnchor elsewhere = new DialogueAnchor(subject.player.level().dimension(),
                            new Vec3(here.x + 10.0, here.y, here.z), 4.0);
                    activateFixture(helper);
                    helper.assertFalse(sessions().openAt(subject.player, CLERK, elsewhere),
                            "a player ten blocks from a four-block anchor gets no conversation");
                    helper.assertFalse(sessions().isInDialogue(subject.player), "nothing was started or queued");
                    helper.assertValueEqual(flagsOf(helper, subject.player).get(MET), 0, "no on-enter action ran");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aGraphChainedWithOpenKeepsTheAnchor(GameTestHelper helper) {
        Subject subject = new Subject();
        Identifier chainEight = Identifier.fromNamespaceAndPath("haruhi", "fixture/chain8");
        joinLoaded(helper, subject)
                .thenExecute(() -> {
                    DialogueAnchor anchor = anchorAtPlayer(subject.player, 4.0);
                    activateFixture(helper);
                    helper.assertTrue(sessions().openAt(subject.player, chainEight, anchor), "chain8 should open");
                    DialogueSession session = sessions().session(subject.player).orElseThrow();
                    helper.assertValueEqual(session.graphId(),
                            Identifier.fromNamespaceAndPath("haruhi", "fixture/chain9"), "chain8 opened chain9 as it entered");
                    helper.assertValueEqual(session.anchor(), anchor, "the chained conversation keeps the anchor");
                    sessions().close(subject.player);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aQueuedOpenAtIsDroppedWhenThePlayerLeftTheAnchorBeforeTheFlagsLoaded(GameTestHelper helper) {
        HeldStorage storage = new HeldStorage(HaruhiServices.storage(), "loadVisits");
        DialogueSessions held = new DialogueSessions(new CharacterStore(storage::storage));
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = helper.makeMockServerPlayerInLevel();
                    activateFixture(helper);
                    helper.assertTrue(held.openAt(subject.player, CLERK, anchorAtPlayer(subject.player, 4.0)),
                            "the open is accepted and queued while the flags load");
                    helper.assertTrue(held.session(subject.player).isEmpty(), "the load is held, so nothing started");
                    Vec3 here = subject.player.position();
                    subject.player.setPos(here.x + 10.0, here.y, here.z);
                })
                .thenWaitUntil(() -> helper.assertTrue(storage.answered(), "storage answered the held load"))
                .thenExecute(() -> {
                    activateFixture(helper);
                    storage.release();
                })
                .thenWaitUntil(() -> helper.assertTrue(held.loadedFlags(subject.player).isPresent(), "the flags loaded"))
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(held.session(subject.player).isEmpty(),
                            "the queued open was dropped: the player had left the anchor");
                    held.onLeave(subject.player);
                })
                .thenSucceed();
    }

    private static DialogueAnchor anchorAtPlayer(ServerPlayer player, double maxDistance) {
        return new DialogueAnchor(player.level().dimension(), player.position(), maxDistance);
    }

    /** {@link #openNow} for an anchored conversation. */
    private static DialogueSession openAtNow(GameTestHelper helper, ServerPlayer player, Identifier graph,
            DialogueAnchor anchor) {
        activateFixture(helper);
        helper.assertTrue(sessions().openAt(player, graph, anchor), "the fixture graph " + graph + " should open");
        Optional<DialogueSession> session = sessions().session(player);
        helper.assertTrue(session.isPresent(), "the flags were loaded, so " + graph + " started in this step");
        return session.get();
    }

    /** Waits for the startup activation, joins a mock player, and waits until the module has loaded its flags. */
    private static GameTestSequence joinLoaded(GameTestHelper helper, Subject subject) {
        return helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> subject.player = helper.makeMockServerPlayerInLevel())
                .thenWaitUntil(() -> flagsOf(helper, subject.player));
    }

    /** Waits for the startup activation, joins a mock player to the test's own service, and waits for its flags. */
    private static GameTestSequence startWithLoadedFlags(GameTestHelper helper, DialogueSessions service,
            Subject subject) {
        return helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = helper.makeMockServerPlayerInLevel();
                    service.onJoin(subject.player);
                })
                .thenWaitUntil(() -> flagsOf(helper, service, subject.player));
    }

    /**
     * Activates the fixture and opens one of its graphs in the same step, for a player whose flags are loaded, so the
     * conversation starts at once against the fixture and keeps it. Fails when the open was queued instead.
     */
    private static DialogueSession openNow(GameTestHelper helper, DialogueSessions service, ServerPlayer player,
            Identifier graph) {
        activateFixture(helper);
        helper.assertTrue(service.open(player, graph, null), "the fixture graph " + graph + " should open");
        Optional<DialogueSession> session = service.session(player);
        helper.assertTrue(session.isPresent(), "the flags were loaded, so " + graph + " started in this step");
        return session.get();
    }

    /** Parses the fixture with the content service and makes it the active content, unstored. */
    private static void activateFixture(GameTestHelper helper) {
        DataResult<ContentBundle> bundle = HaruhiServices.content().parseAndValidate(readFixture(helper));
        helper.assertTrue(bundle.result().isPresent(),
                "the fixture must validate: " + bundle.error().map(error -> error.message()).orElse("?"));
        HaruhiServices.content().activateUnstored(bundle.result().orElseThrow());
    }

    private static void requireStartupComplete(GameTestHelper helper) {
        helper.assertTrue(HaruhiServices.content().isStartupComplete(),
                "the startup content activation has not finished");
    }

    private static String readFixture(GameTestHelper helper) {
        try (InputStream stream = DialogueGameTests.class.getResourceAsStream(FIXTURE)) {
            helper.assertTrue(stream != null, "the fixture " + FIXTURE + " is missing from the test resources");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            helper.fail("could not read " + FIXTURE + ": " + e.getMessage());
            return "";
        }
    }

    /** Replaces the player's entity as a death does, and returns the new entity. */
    private static ServerPlayer respawn(GameTestHelper helper, ServerPlayer player) {
        return helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
    }

    /**
     * Joins a mock player with the given id the way {@code makeMockServerPlayerInLevel} does, but keeps the channel, so
     * the test can read what the server sends. Joining with the id of a player who left is a rejoin.
     */
    private static Client joinClient(GameTestHelper helper, UUID id) {
        ServerLevel level = helper.getLevel();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(id, "test-mock-player"), false);
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return new Client(player, channel);
    }

    /** The one dialog sent, which must be a choice dialog showing {@code text}. */
    private static MultiActionDialog onlyChoiceDialog(GameTestHelper helper, List<Dialog> sent, String text) {
        helper.assertValueEqual(sent.size(), 1, "the number of dialogs the server sent");
        helper.assertTrue(sent.get(0) instanceof MultiActionDialog, "a node with choices is sent as a choice dialog");
        MultiActionDialog dialog = (MultiActionDialog) sent.get(0);
        helper.assertTrue(dialog.common().body().get(0) instanceof PlainMessage message
                        && message.contents().getString().equals(text),
                "the dialog shows " + text);
        return dialog;
    }

    /** What a vanilla client sends when the labelled button is pressed: the button's custom click. */
    private static ClickEvent.Custom clickOf(GameTestHelper helper, MultiActionDialog dialog, String label) {
        for (ActionButton button : dialog.actions()) {
            if (button.button().label().getString().equals(label)) {
                helper.assertTrue(button.action().isPresent(), "the " + label + " button has an action; a vanilla"
                        + " client that presses a button without one waits for an answer that never comes");
                helper.assertTrue(button.action().get() instanceof StaticAction action
                                && action.value() instanceof ClickEvent.Custom,
                        "the " + label + " button sends a custom click");
                return (ClickEvent.Custom) ((StaticAction) button.action().get()).value();
            }
        }
        helper.fail("the dialog has no " + label + " button");
        throw new IllegalStateException("unreachable");
    }

    /** Sets the weather the conditions see, runs the three weather conditions, then puts the weather back. */
    private static void checkWeather(GameTestHelper helper, ServerLevel level, PlayerContext context) {
        DialogueCondition clear = condition(helper, "haruhi:weather", "{\"weather\":\"clear\"}");
        DialogueCondition rain = condition(helper, "haruhi:weather", "{\"weather\":\"rain\"}");
        DialogueCondition thunder = condition(helper, "haruhi:weather", "{\"weather\":\"thunder\"}");
        float rainBefore = level.getRainLevel(1.0F);
        float thunderBefore = rainBefore == 0.0F ? 0.0F : level.getThunderLevel(1.0F) / rainBefore;
        try {
            setWeather(level, 0.0F, 0.0F);
            helper.assertTrue(clear.test(context) && !rain.test(context) && !thunder.test(context),
                    "with no rain only clear holds");
            setWeather(level, 1.0F, 0.0F);
            helper.assertTrue(level.isRaining(), "the test level has weather");
            helper.assertTrue(!clear.test(context) && rain.test(context) && !thunder.test(context),
                    "in rain without thunder only rain holds");
            setWeather(level, 1.0F, 1.0F);
            helper.assertTrue(!clear.test(context) && !rain.test(context) && thunder.test(context),
                    "in a thunderstorm only thunder holds");
        } finally {
            setWeather(level, rainBefore, thunderBefore);
        }
    }

    private static void setWeather(ServerLevel level, float rain, float thunder) {
        level.setRainLevel(rain);
        level.setThunderLevel(thunder);
    }

    /** Moves the level's default clock, runs time ranges against it, then puts the clock back. */
    private static void checkTime(GameTestHelper helper, ServerLevel level, PlayerContext context) {
        Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
        helper.assertTrue(clock.isPresent(), "the test level has a default clock");
        ServerClockManager clocks = level.clockManager();
        long before = clocks.getTotalTicks(clock.get());
        long dayStart = before - Math.floorMod(before, (long) TICKS_PER_DAY);
        DialogueCondition morning = condition(helper, "haruhi:time", "{\"from\":5000,\"to\":7000}");
        DialogueCondition evening = condition(helper, "haruhi:time", "{\"from\":12000,\"to\":13000}");
        DialogueCondition night = condition(helper, "haruhi:time", "{\"from\":23000,\"to\":1000}");
        try {
            clocks.setTotalTicks(clock.get(), dayStart + 6000);
            helper.assertTrue(morning.test(context), "6000 is within 5000 to 7000");
            helper.assertFalse(evening.test(context), "6000 is not within 12000 to 13000");
            helper.assertFalse(night.test(context), "6000 is not within 23000 to 1000, which wraps past midnight");
            clocks.setTotalTicks(clock.get(), dayStart + 23500);
            helper.assertTrue(night.test(context), "23500 is within 23000 to 1000");
            clocks.setTotalTicks(clock.get(), dayStart + TICKS_PER_DAY + 500);
            helper.assertTrue(night.test(context), "500 of the next day is within 23000 to 1000");
            helper.assertFalse(morning.test(context), "500 is not within 5000 to 7000");
        } finally {
            clocks.setTotalTicks(clock.get(), before);
        }
    }

    /** Grants criteria to a fresh mock player and checks the advancement condition with and without a criterion. */
    private static void checkAdvancements(GameTestHelper helper, ServerPlayer player, PlayerContext context) {
        AdvancementHolder diet = player.level().getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath("minecraft", "husbandry/balanced_diet"));
        AdvancementHolder stone = player.level().getServer().getAdvancements()
                .get(Identifier.fromNamespaceAndPath("minecraft", "story/mine_stone"));
        helper.assertTrue(diet != null && stone != null, "the vanilla advancements are loaded");
        DialogueCondition ateApple = condition(helper, "haruhi:advancement",
                "{\"advancement\":\"minecraft:husbandry/balanced_diet\",\"criterion\":\"apple\"}");
        DialogueCondition ateBread = condition(helper, "haruhi:advancement",
                "{\"advancement\":\"minecraft:husbandry/balanced_diet\",\"criterion\":\"bread\"}");
        DialogueCondition dietDone = condition(helper, "haruhi:advancement",
                "{\"advancement\":\"minecraft:husbandry/balanced_diet\"}");
        DialogueCondition stoneDone = condition(helper, "haruhi:advancement",
                "{\"advancement\":\"minecraft:story/mine_stone\"}");
        DialogueCondition missing = condition(helper, "haruhi:advancement",
                "{\"advancement\":\"haruhi:fixture/no_such_advancement\"}");
        helper.assertFalse(ateApple.test(context) || stoneDone.test(context), "a fresh player has neither");

        player.getAdvancements().award(diet, "apple");
        player.getAdvancements().award(stone, "get_stone");
        helper.assertTrue(ateApple.test(context), "the granted criterion is done");
        helper.assertFalse(ateBread.test(context), "a criterion that was not granted is not done");
        helper.assertFalse(dietDone.test(context), "one criterion of many does not complete the advancement");
        helper.assertTrue(stoneDone.test(context), "granting the only criterion completes the advancement");
        helper.assertFalse(missing.test(context), "an advancement the server does not have is not done");
    }

    private static DialogueCondition condition(GameTestHelper helper, String type, String args) {
        DataResult<DialogueCondition> parsed = DialogueRegistries.condition(Identifier.parse(type)).orElseThrow()
                .parse(JsonParser.parseString(args).getAsJsonObject(), PROBE_PARSE);
        helper.assertTrue(parsed.result().isPresent(), type + " " + args + " should parse: "
                + parsed.error().map(error -> error.message()).orElse("?"));
        return parsed.result().orElseThrow();
    }

    private static DialogueAction action(GameTestHelper helper, String type, String args) {
        DataResult<DialogueAction> parsed = DialogueRegistries.action(Identifier.parse(type)).orElseThrow()
                .parse(JsonParser.parseString(args).getAsJsonObject(), PROBE_PARSE);
        helper.assertTrue(parsed.result().isPresent(), type + " " + args + " should parse: "
                + parsed.error().map(error -> error.message()).orElse("?"));
        return parsed.result().orElseThrow();
    }

    /** {@code haruhi:command} as parsed under a config with this permission level and only {@code tag} allowed. */
    private static DialogueAction commandAt(GameTestHelper helper, int level, String command) {
        JsonObject args = new JsonObject();
        args.addProperty("command", command);
        DataResult<DialogueAction> parsed = Builtins.commandParser(new DialogueConfig(level, List.of("tag")))
                .parse(args, PROBE_PARSE);
        helper.assertTrue(parsed.result().isPresent(), "the command should parse: "
                + parsed.error().map(error -> error.message()).orElse("?"));
        return parsed.result().orElseThrow();
    }

    /** The give action wrote its once flag through at once and has given nothing yet: the write is held. */
    private static void assertWrittenThroughAndNotGiven(GameTestHelper helper, HeldStorage storage, Subject subject) {
        helper.assertValueEqual(countOf(subject.player, Items.BOOK), 0, "books given before the write completes");
        // The test's service is never ticked, so it never flushes a batch: only a durable write reaches storage.
        helper.assertTrue(storage.wrote(FlagChange.set(ONCE, 1)), "the once flag is written through, not batched");
    }

    /**
     * Reads the player's stored flags, one read at a time, and fails until a finished read holds the value. Storage is
     * read directly, so neither the cache nor the changes still waiting to be written can answer for it.
     */
    private static void assertStored(GameTestHelper helper, Subject subject, FlagKey key, int expected) {
        CompletableFuture<Map<FlagKey, Integer>> read = subject.stored;
        if (read == null || (read.isDone() && !Integer.valueOf(expected).equals(read.getNow(Map.of()).get(key)))) {
            read = HaruhiServices.storage().loadFlags(subject.player.getUUID());
            subject.stored = read;
        }
        helper.assertTrue(read.isDone(), "the stored flags have been read");
        Integer stored = read.join().get(key);
        helper.assertTrue(Integer.valueOf(expected).equals(stored),
                "storage holds " + key.flag() + " = " + stored + ", not " + expected);
    }

    /** Storage whose first {@code failures} flag loads fail, and whose other calls go to {@code real}. */
    private static HaruhiStorage failingFlagLoads(HaruhiStorage real, AtomicInteger failures) {
        return (HaruhiStorage) Proxy.newProxyInstance(DialogueGameTests.class.getClassLoader(),
                new Class<?>[] {HaruhiStorage.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("loadFlags")
                            && failures.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                        return CompletableFuture.failedFuture(new IllegalStateException("injected: database is down"));
                    }
                    return invoke(real, method, args);
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** The payload a vanilla choice button sends: the session, node and index the presenter documents. */
    private static Optional<Tag> choicePayload(int sessionId, String node, int index) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("session", sessionId);
        tag.putString("node", node);
        tag.putInt("choice", index);
        return Optional.of(tag);
    }

    /** The payload of the exit button: the session only. */
    private static Optional<Tag> dismissPayload(int sessionId) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("session", sessionId);
        return Optional.of(tag);
    }

    /**
     * Sends a custom click the way a vanilla client does: the packet goes to the connection's handler, which the dialogue
     * mixin intercepts.
     */
    private static void clickVanilla(ServerPlayer player, Identifier id, Optional<Tag> payload) {
        player.connection.handleCustomClickAction(new ServerboundCustomClickActionPacket(id, payload));
    }

    private static int countOf(ServerPlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int booksOnTheGround(ServerPlayer player) {
        return player.level().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(16.0),
                item -> item.getItem().is(Items.BOOK)).size();
    }

    private static DialogueSessions sessions() {
        return DialogueModule.sessions();
    }

    private static DialogueSession presentedAt(GameTestHelper helper, ServerPlayer player, String node) {
        return presentedAt(helper, sessions(), player, node);
    }

    /** The player's open session, once it shows the expected node. Fails the current check until then. */
    private static DialogueSession presentedAt(GameTestHelper helper, DialogueSessions service, ServerPlayer player,
            String node) {
        Optional<DialogueSession> session = service.session(player);
        helper.assertTrue(session.isPresent(), "no conversation is open yet");
        helper.assertTrue(node.equals(session.get().presentedNode()),
                "expected node " + node + " but the conversation shows " + session.get().presentedNode());
        return session.get();
    }

    private static DialogueFlags flagsOf(GameTestHelper helper, ServerPlayer player) {
        return flagsOf(helper, sessions(), player);
    }

    private static DialogueFlags flagsOf(GameTestHelper helper, DialogueSessions service, ServerPlayer player) {
        Optional<DialogueFlags> flags = service.loadedFlags(player);
        helper.assertTrue(flags.isPresent(), "the player's flags are loaded");
        return flags.get();
    }

    private static int visibleIndex(DialogueSession session, String text) {
        for (int index = 0; index < session.presented().size(); index++) {
            VisibleChoice choice = session.presented().get(index);
            if (choice.text().getString().equals(text)) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOf(GameTestHelper helper, DialogueSession session, String text) {
        int index = visibleIndex(session, text);
        helper.assertTrue(index >= 0, "the entry " + text + " is shown");
        return index;
    }

    /** The mock players and reads that the steps of one test share. */
    private static final class Subject {
        private ServerPlayer player;
        /** The entity from before a respawn or a logout replaced it. */
        private ServerPlayer first;
        private Client client;
        private CompletableFuture<Map<FlagKey, Integer>> stored;
        private int booksAround;
    }

    /** A mock player and the channel that receives what the server sends it. */
    private record Client(ServerPlayer player, EmbeddedChannel channel) {
        /**
         * Everything the server sent since the last read, oldest first. The server thread writes without flushing, so the
         * channel is flushed first.
         */
        List<Object> sent() {
            channel.flushOutbound();
            List<Object> messages = new ArrayList<>();
            for (Object message = channel.readOutbound(); message != null; message = channel.readOutbound()) {
                messages.add(message);
            }
            return messages;
        }

        /** Every dialog the server sent since the last read, oldest first. */
        List<Dialog> dialogsSent() {
            return sent().stream()
                    .filter(ClientboundShowDialogPacket.class::isInstance)
                    .map(message -> ((ClientboundShowDialogPacket) message).dialog().value())
                    .toList();
        }
    }

    /**
     * The server's storage, except that the results of one method are held back until the test releases them. Each held
     * call runs against the real storage at once, so storage has a write before the write reports done. Released on the
     * server thread, the callbacks that wait on the results run in the releasing step.
     */
    private static final class HeldStorage {
        private final HaruhiStorage proxy;
        private final List<Object[]> calls = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<?>> answers = new CopyOnWriteArrayList<>();
        private final CompletableFuture<Void> released = new CompletableFuture<>();

        HeldStorage(HaruhiStorage real, String heldMethod) {
            this.proxy = (HaruhiStorage) Proxy.newProxyInstance(DialogueGameTests.class.getClassLoader(),
                    new Class<?>[] {HaruhiStorage.class},
                    (self, method, args) -> {
                        Object result = invoke(real, method, args);
                        if (!method.getName().equals(heldMethod)) {
                            return result;
                        }
                        CompletableFuture<?> answer = (CompletableFuture<?>) result;
                        calls.add(args);
                        answers.add(answer);
                        return answer.thenCombine(released, (value, ignored) -> value);
                    });
        }

        HaruhiStorage storage() {
            return proxy;
        }

        List<Object[]> calls() {
            return calls;
        }

        /** True once a held call has reached storage and storage has answered every held call. */
        boolean answered() {
            return !answers.isEmpty() && answers.stream().allMatch(CompletableFuture::isDone);
        }

        /** True when a held {@code writeFlags} call carried the change. */
        boolean wrote(FlagChange change) {
            return calls.stream().anyMatch(args -> args.length == 2 && args[1] instanceof List<?> changes
                    && changes.contains(change));
        }

        void release() {
            released.complete(null);
        }
    }

    /** What a built-in sees when a test runs it for a player outside any conversation. */
    private record PlayerContext(ServerPlayer player) implements DialogueContext {
        @Override
        public Optional<Entity> interlocutor() {
            return Optional.empty();
        }

        @Override
        public Identifier graphId() {
            return PROBE;
        }

        @Override
        public String nodeId() {
            return "probe";
        }

        @Override
        public ContentBundle content() {
            return ContentBundle.EMPTY;
        }

        @Override
        public DialogueFlags flags() {
            throw new UnsupportedOperationException("the world built-ins under test read no flags");
        }

        @Override
        public int visits() {
            return 1;
        }

        @Override
        public void goTo(String nodeId) {}

        @Override
        public void end() {}

        @Override
        public void openGraph(Identifier graphId) {}
    }
}
