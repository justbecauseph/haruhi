package town.lampas.haruhi.gametest;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.DialogueSessions;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.flags.CharacterStore;
import town.lampas.haruhi.dialogue.presenter.VanillaDialogs;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;
import town.lampas.haruhi.network.DialogueChoicePayload;
import town.lampas.haruhi.storage.FlagKey;
import town.lampas.haruhi.storage.HaruhiStorage;

/**
 * Server GameTests for the dialogue runtime. Every test activates the one fixture bundle, so the tests that run in the same
 * batch never replace each other's content. A mock player opens graphs of that bundle and picks entries through
 * {@link DialogueSessions#choose}, the same call the payload receiver makes, or as vanilla custom-click packets, which the
 * dialogue mixin routes to the vanilla presenter. The mock has no Haruhi channel, so the vanilla presenter runs. Opening waits
 * for the flags to load, so each test steps through a sequence whose waiting steps retry each tick.
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
    private static final String FIXTURE = "/haruhi/fixtures/dialogue/bundle.json";
    private static final FlagKey MET = new FlagKey("haruhi:fixture/clerk", "met");
    private static final FlagKey ONCE = new FlagKey("haruhi:fixture/reward", "got_reward");
    private static final String REMEMBER = "Remember me?";
    private static final String TELL_ME_MORE = "Tell me more.";
    private static final String LEAVE = "Leave.";

    @GameTest(maxTicks = 200)
    public void openWaitsForTheFlagsAndThenShowsTheStartNode(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "greet");
                    helper.assertTrue(visibleIndex(session, TELL_ME_MORE) >= 0, "the first conversation offers Tell me more.");
                    helper.assertTrue(visibleIndex(session, REMEMBER) < 0, "Remember me? is hidden before the flag is set");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void choosingAnEntryMovesAlongNextAndContinueEndsTheConversation(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                })
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
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "greet");
                    firstSession[0] = session.id();
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
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the second conversation should open");
                })
                .thenWaitUntil(() -> {
                    DialogueSession second = presentedAt(helper, subject.player, "greet");
                    helper.assertTrue(second.id() != firstSession[0], "a new conversation has a new session id");
                    helper.assertTrue(visibleIndex(second, REMEMBER) >= 0,
                            "the flag set in the first conversation shows Remember me? in the next");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void flagsSurviveDroppingTheCacheAndReloadingFromStorage(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "greet");
                    sessions().choose(subject.player, session.id(), "greet", indexOf(helper, session, TELL_ME_MORE));
                })
                .thenWaitUntil(() -> helper.assertValueEqual(1, flagsOf(helper, subject.player).get(MET),
                        "the entry set met in the cache"))
                .thenExecute(() -> {
                    // Quitting drops the cache and flushes the pending batch. Reopening must read storage again.
                    sessions().onLeave(subject.player);
                    helper.assertTrue(sessions().loadedFlags(subject.player).isEmpty(), "the cache is dropped on leave");
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should reopen");
                })
                .thenWaitUntil(() -> {
                    DialogueSession reopened = presentedAt(helper, subject.player, "greet");
                    helper.assertTrue(visibleIndex(reopened, REMEMBER) >= 0,
                            "met was read back from storage after the flush");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aForgedChoiceIndexOrStaleSessionDoesNothing(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "greet"))
                .thenExecute(() -> {
                    DialogueSession session = sessions().session(subject.player).orElseThrow();
                    int shown = session.presented().size();
                    sessions().choose(subject.player, session.id(), "greet", 99);
                    sessions().choose(subject.player, session.id(), "greet", -7);
                    sessions().choose(subject.player, session.id() + 1, "greet", indexOf(helper, session, TELL_ME_MORE));
                    sessions().choose(subject.player, session.id(), "info", indexOf(helper, session, TELL_ME_MORE));

                    helper.assertTrue(sessions().session(subject.player).orElseThrow() == session,
                            "the same conversation is still open");
                    helper.assertValueEqual(shown, session.presented().size(), "the shown entries did not change");
                    helper.assertValueEqual(0, flagsOf(helper, subject.player).get(MET),
                            "no entry ran, so met is still unset");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void anOpenWaitingForTheFlagsIsInDialogueAndCloseCancelsIt(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    // The mock joined this tick, so its flags are normally still loading and the open is queued until they
                    // arrive. When the load has already finished, the open starts a session instead; both must count.
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                    helper.assertTrue(sessions().isInDialogue(subject.player),
                            "an opened conversation counts before its flags load");
                    sessions().close(subject.player);
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "close ends the conversation, queued or not");
                })
                .thenWaitUntil(() -> flagsOf(helper, subject.player))
                .thenIdle(2)
                .thenExecute(() -> helper.assertTrue(sessions().session(subject.player).isEmpty(),
                        "the closed conversation did not start once the flags loaded"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void openingAGraphOutsideTheActiveContentReturnsFalse(GameTestHelper helper) {
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    ServerPlayer player = joinWithFixture(helper);
                    helper.assertFalse(sessions().open(player,
                                    Identifier.fromNamespaceAndPath("haruhi", "fixture/missing"), null),
                            "a graph outside the active content does not open");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aRespawnEndsTheConversationAndTheNextOneRunsOnTheNewEntity(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "greet"))
                .thenExecute(() -> {
                    subject.player = respawn(helper, subject.player);
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "the respawn ends the conversation, whose entity is gone");
                    helper.assertTrue(sessions().loadedFlags(subject.player).isPresent(),
                            "the flags carry over to the new entity");
                    helper.assertTrue(sessions().open(subject.player, CLERK, null),
                            "the new entity opens a conversation");
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "greet");
                    helper.assertTrue(session.player() == subject.player, "the conversation runs on the new entity");
                })
                .thenIdle(5)
                .thenExecute(() -> presentedAt(helper, subject.player, "greet"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 300)
    public void aRewardGivenAfterARespawnReachesTheLivePlayerAndIsGivenOnce(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    activateFixture(helper);
                    subject.player = helper.makeMockServerPlayerInLevel();
                    // The flags are still loading, so the open is queued. The entity is replaced before the queue runs.
                    helper.assertTrue(sessions().open(subject.player, REWARD, null), "the reward graph should open");
                    subject.player = respawn(helper, subject.player);
                })
                .thenWaitUntil(() -> {
                    presentedAt(helper, subject.player, "hand_over");
                    helper.assertValueEqual(1, countOf(subject.player, Items.BOOK),
                            "the book is in the live player's inventory");
                })
                .thenExecute(() -> helper.assertValueEqual(1, flagsOf(helper, subject.player).get(ONCE),
                        "the once flag is set"))
                .thenExecute(() -> {
                    helper.assertTrue(sessions().open(subject.player, REWARD, null), "the reward graph opens again");
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "hand_over"))
                .thenIdle(20)
                .thenExecute(() -> helper.assertValueEqual(1, countOf(subject.player, Items.BOOK),
                        "the once flag refuses a second book"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 400)
    public void aFailedFlagLoadIsRetriedWhileThePlayerIsOnline(GameTestHelper helper) {
        AtomicInteger failures = new AtomicInteger(1);
        HaruhiStorage flaky = failingFlagLoads(HaruhiServices.storage(), failures);
        DialogueSessions retrying = new DialogueSessions(new CharacterStore(() -> flaky));
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
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
                    helper.assertValueEqual(0, failures.get(), "the injected load failure was the one that ran");
                    retrying.onLeave(subject.player);
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aVanillaDialogClickIsHandledLikeAPick(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, CLERK, null), "the fixture graph should open");
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "greet"))
                .thenExecute(() -> {
                    DialogueSession session = sessions().session(subject.player).orElseThrow();
                    clickVanilla(subject.player, VanillaDialogs.CHOICE_CLICK,
                            choicePayload(session.id(), "greet", indexOf(helper, session, TELL_ME_MORE)));
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "info"))
                .thenExecute(() -> {
                    DialogueSession session = sessions().session(subject.player).orElseThrow();
                    clickVanilla(subject.player, VanillaDialogs.CHOICE_CLICK, choicePayload(session.id(), "greet", 0));
                    helper.assertValueEqual("info", session.presentedNode(),
                            "a click for a node not shown changes nothing");

                    clickVanilla(subject.player, VanillaDialogs.DISMISS_CLICK, dismissPayload(session.id()));
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "the exit button ends the conversation");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aGrayedOutVanillaChoiceAnswersWithTheNodeAgain(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    helper.assertTrue(sessions().open(subject.player, GATE, null), "the gate graph should open");
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "menu"))
                .thenExecute(() -> {
                    DialogueSession session = sessions().session(subject.player).orElseThrow();
                    int locked = indexOf(helper, session, "Locked door");
                    helper.assertFalse(session.presented().get(locked).enabled(), "the door is shown grayed out");
                    clickVanilla(subject.player, VanillaDialogs.CHOICE_CLICK,
                            choicePayload(session.id(), "menu", locked));
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "menu"))
                .thenExecute(() -> {
                    DialogueSession session = sessions().session(subject.player).orElseThrow();
                    helper.assertTrue(sessions().isInDialogue(subject.player),
                            "the grayed-out click leaves the conversation open");
                    clickVanilla(subject.player, VanillaDialogs.CHOICE_CLICK,
                            choicePayload(session.id(), "menu", indexOf(helper, session, LEAVE)));
                })
                .thenWaitUntil(() -> presentedAt(helper, subject.player, "bye"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void worldConditionsReadTheItemsTagsAndPermissionOfThePlayer(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                    subject.player.getInventory().placeItemBackInInventory(new ItemStack(Items.APPLE, 3));
                    subject.player.addTag("guild_member");
                    helper.assertTrue(sessions().open(subject.player, WORLD, null), "the world graph should open");
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, subject.player, "check");
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

    @GameTest(maxTicks = 200)
    public void aChainOfOpensPastTheLimitStillReportsTheFirstGraphAsOpened(GameTestHelper helper) {
        Subject subject = new Subject();
        helper.startSequence()
                .thenWaitUntil(() -> requireStartupComplete(helper))
                .thenExecute(() -> {
                    subject.player = joinWithFixture(helper);
                })
                .thenWaitUntil(() -> helper.assertTrue(sessions().loadedFlags(subject.player).isPresent(),
                        "the flags are loaded, so the chain runs in one step"))
                .thenExecute(() -> {
                    // Ten graphs chained through haruhi:open pass the limit of eight, so the last link does not open.
                    helper.assertTrue(sessions().open(subject.player, CHAIN_FIRST, null),
                            "the first graph exists, so open reports it opened");
                    helper.assertFalse(sessions().isInDialogue(subject.player),
                            "the chain stopped at its limit and no conversation is left open");
                })
                .thenSucceed();
    }

    /** Parses the fixture with the content service and makes it the active content, unstored. */
    private static void activateFixture(GameTestHelper helper) {
        DataResult<ContentBundle> bundle = HaruhiServices.content().parseAndValidate(readFixture(helper));
        helper.assertTrue(bundle.result().isPresent(),
                "the fixture must validate: " + bundle.error().map(error -> error.message()).orElse("?"));
        HaruhiServices.content().activateUnstored(bundle.result().orElseThrow());
    }

    /**
     * Activates the fixture and joins a mock player, in one tick. The open that follows waits for the player's flags, and
     * activating again in the same tick keeps a late startup activation from replacing the fixture before that open.
     */
    private static ServerPlayer joinWithFixture(GameTestHelper helper) {
        activateFixture(helper);
        return helper.makeMockServerPlayerInLevel();
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

    /** Storage whose first {@code failures} flag loads fail, and whose other calls go to {@code real}. */
    private static HaruhiStorage failingFlagLoads(HaruhiStorage real, AtomicInteger failures) {
        return (HaruhiStorage) Proxy.newProxyInstance(DialogueGameTests.class.getClassLoader(),
                new Class<?>[] {HaruhiStorage.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("loadFlags")
                            && failures.getAndUpdate(count -> Math.max(0, count - 1)) > 0) {
                        return CompletableFuture.failedFuture(new IllegalStateException("injected: database is down"));
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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

    private static DialogueSessions sessions() {
        return DialogueModule.sessions();
    }

    /** The player's open session, once it shows the expected node. Fails the current check until then. */
    private static DialogueSession presentedAt(GameTestHelper helper, ServerPlayer player, String node) {
        Optional<DialogueSession> session = sessions().session(player);
        helper.assertTrue(session.isPresent(), "no conversation is open yet");
        helper.assertTrue(node.equals(session.get().presentedNode()),
                "expected node " + node + " but the conversation shows " + session.get().presentedNode());
        return session.get();
    }

    private static DialogueFlags flagsOf(GameTestHelper helper, ServerPlayer player) {
        Optional<DialogueFlags> flags = sessions().loadedFlags(player);
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

    /** A mock player that the steps of one test share. */
    private static final class Subject {
        private ServerPlayer player;
    }
}
