package town.lampas.haruhi.gametest;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.DialogueSessions;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.runtime.DialogueSession;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;
import town.lampas.haruhi.network.DialogueChoicePayload;
import town.lampas.haruhi.storage.FlagKey;

/**
 * Server GameTests for the dialogue runtime. A mock player opens the clerk fixture and picks entries through
 * {@link DialogueSessions#choose}, the same call the payload receiver makes. The mock has no Haruhi channel, so the
 * vanilla presenter runs. Opening waits for the flags to load, so each test steps through a sequence whose waiting
 * steps retry each tick.
 */
public final class DialogueGameTests {
    private static final Identifier CLERK = Identifier.fromNamespaceAndPath("haruhi", "fixture/clerk");
    private static final String FIXTURE = "/haruhi/fixtures/dialogue/clerk.json";
    private static final String NAMESPACE = "haruhi:fixture/clerk";
    private static final FlagKey MET = new FlagKey(NAMESPACE, "met");
    private static final String REMEMBER = "Remember me?";
    private static final String TELL_ME_MORE = "Tell me more.";
    private static final String LEAVE = "Leave.";

    @GameTest(maxTicks = 200)
    public void openWaitsForTheFlagsAndThenShowsTheStartNode(GameTestHelper helper) {
        activateFixture(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DialogueSessions sessions = DialogueModule.sessions();
        helper.assertTrue(sessions.open(player, CLERK, null), "the fixture graph should open");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, sessions, player, "greet");
                    helper.assertTrue(visibleIndex(session, TELL_ME_MORE) >= 0, "the first conversation offers Tell me more.");
                    helper.assertTrue(visibleIndex(session, REMEMBER) < 0, "Remember me? is hidden before the flag is set");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void choosingAnEntryMovesAlongNextAndContinueEndsTheConversation(GameTestHelper helper) {
        activateFixture(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DialogueSessions sessions = DialogueModule.sessions();
        helper.assertTrue(sessions.open(player, CLERK, null), "the fixture graph should open");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, sessions, player, "greet");
                    sessions.choose(player, session.id(), "greet", indexOf(helper, session, LEAVE));
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, sessions, player, "bye");
                    sessions.choose(player, session.id(), "bye", DialogueChoicePayload.CONTINUE);
                })
                .thenExecute(() -> helper.assertFalse(sessions.isInDialogue(player),
                        "continue on the last node ends the conversation"))
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aFlagSetInOneConversationChangesTheBranchOfTheNext(GameTestHelper helper) {
        activateFixture(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DialogueSessions sessions = DialogueModule.sessions();
        helper.assertTrue(sessions.open(player, CLERK, null), "the fixture graph should open");
        int[] firstSession = {0};

        helper.startSequence()
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, sessions, player, "greet");
                    firstSession[0] = session.id();
                    sessions.choose(player, session.id(), "greet", indexOf(helper, session, TELL_ME_MORE));
                })
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, sessions, player, "info");
                    sessions.choose(player, session.id(), "info", DialogueChoicePayload.CONTINUE);
                })
                .thenExecute(() -> {
                    helper.assertFalse(sessions.isInDialogue(player), "the first conversation ended");
                    helper.assertTrue(sessions.open(player, CLERK, null), "the second conversation should open");
                })
                .thenWaitUntil(() -> {
                    DialogueSession second = presentedAt(helper, sessions, player, "greet");
                    helper.assertTrue(second.id() != firstSession[0], "a new conversation has a new session id");
                    helper.assertTrue(visibleIndex(second, REMEMBER) >= 0,
                            "the flag set in the first conversation shows Remember me? in the next");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void flagsSurviveDroppingTheCacheAndReloadingFromStorage(GameTestHelper helper) {
        activateFixture(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DialogueSessions sessions = DialogueModule.sessions();
        helper.assertTrue(sessions.open(player, CLERK, null), "the fixture graph should open");

        helper.startSequence()
                .thenWaitUntil(() -> {
                    DialogueSession session = presentedAt(helper, sessions, player, "greet");
                    sessions.choose(player, session.id(), "greet", indexOf(helper, session, TELL_ME_MORE));
                })
                .thenWaitUntil(() -> helper.assertTrue(flagsOf(helper, sessions, player).get(MET) == 1,
                        "the entry set met in the cache"))
                .thenExecute(() -> {
                    // Quitting drops the cache and flushes the pending batch. Reopening must read storage again.
                    sessions.onLeave(player);
                    helper.assertTrue(sessions.loadedFlags(player).isEmpty(), "the cache is dropped on leave");
                    helper.assertTrue(sessions.open(player, CLERK, null), "the fixture graph should reopen");
                })
                .thenWaitUntil(() -> {
                    DialogueSession reopened = presentedAt(helper, sessions, player, "greet");
                    helper.assertTrue(visibleIndex(reopened, REMEMBER) >= 0,
                            "met was read back from storage after the flush");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 200)
    public void aForgedChoiceIndexOrStaleSessionDoesNothing(GameTestHelper helper) {
        activateFixture(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        DialogueSessions sessions = DialogueModule.sessions();
        helper.assertTrue(sessions.open(player, CLERK, null), "the fixture graph should open");

        helper.startSequence()
                .thenWaitUntil(() -> presentedAt(helper, sessions, player, "greet"))
                .thenExecute(() -> {
                    DialogueSession session = sessions.session(player).orElseThrow();
                    int shown = session.presented().size();
                    sessions.choose(player, session.id(), "greet", 99);
                    sessions.choose(player, session.id(), "greet", -7);
                    sessions.choose(player, session.id() + 1, "greet", indexOf(helper, session, TELL_ME_MORE));
                    sessions.choose(player, session.id(), "info", indexOf(helper, session, TELL_ME_MORE));

                    helper.assertTrue(sessions.session(player).orElseThrow() == session,
                            "the same conversation is still open");
                    helper.assertValueEqual(shown, session.presented().size(), "the shown entries did not change");
                    helper.assertValueEqual(0, flagsOf(helper, sessions, player).get(MET),
                            "no entry ran, so met is still unset");
                })
                .thenSucceed();
    }

    @GameTest(maxTicks = 40)
    public void openingAGraphOutsideTheActiveContentReturnsFalse(GameTestHelper helper) {
        activateFixture(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        helper.assertFalse(DialogueModule.sessions().open(player,
                Identifier.fromNamespaceAndPath("haruhi", "fixture/missing"), null),
                "a graph outside the active content does not open");
        helper.succeed();
    }

    /** Parses the fixture with the content service and makes it the active content, unstored. */
    private static void activateFixture(GameTestHelper helper) {
        DataResult<ContentBundle> bundle = HaruhiServices.content().parseAndValidate(readFixture(helper));
        helper.assertTrue(bundle.result().isPresent(),
                "the fixture must validate: " + bundle.error().map(error -> error.message()).orElse("?"));
        HaruhiServices.content().activateUnstored(bundle.result().orElseThrow());
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

    /** The player's open session, once it shows the expected node. Fails the current check until then. */
    private static DialogueSession presentedAt(
            GameTestHelper helper, DialogueSessions sessions, ServerPlayer player, String node) {
        Optional<DialogueSession> session = sessions.session(player);
        helper.assertTrue(session.isPresent(), "no conversation is open yet");
        helper.assertTrue(node.equals(session.get().presentedNode()),
                "expected node " + node + " but the conversation shows " + session.get().presentedNode());
        return session.get();
    }

    private static DialogueFlags flagsOf(GameTestHelper helper, DialogueSessions sessions, ServerPlayer player) {
        Optional<DialogueFlags> flags = sessions.loadedFlags(player);
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
}
