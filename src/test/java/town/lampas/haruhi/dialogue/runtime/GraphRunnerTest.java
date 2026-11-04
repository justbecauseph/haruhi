package town.lampas.haruhi.dialogue.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.WeightedNext;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.runtime.GraphFixtures.FakeFlags;
import town.lampas.haruhi.network.DialogueChoicePayload;

class GraphRunnerTest {
    private final GraphRunner runner = new GraphRunner();

    private static PresentedNode shown(Step step) {
        assertInstanceOf(Step.Show.class, step, "expected a node to be shown");
        return ((Step.Show) step).node();
    }

    private static String nodeOf(Step step) {
        return shown(step).node().id();
    }

    @Test
    void choosingAnEntryRunsItsActionsAndMovesToItsNext() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("hello",
                GraphFixtures.node("hello", "Hi", null, List.of(
                        GraphFixtures.choice("Thanks", GraphFixtures.ALWAYS, "bye",
                                ctx -> ctx.flags().set(GraphFixtures.MET, 1)),
                        GraphFixtures.choice("Leave", GraphFixtures.ALWAYS, "bye"))),
                GraphFixtures.node("bye", "Safe travels", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);

        PresentedNode first = shown(runner.start(session));
        assertEquals("hello", first.node().id());
        assertEquals(2, first.choices().size());
        assertTrue(first.choices().get(0).enabled());

        assertEquals("bye", nodeOf(runner.choose(session, 0)));
        assertEquals(1, flags.get(GraphFixtures.MET), "the chosen entry's action ran before the move");
        assertInstanceOf(Step.Ended.class, runner.choose(session, DialogueChoicePayload.CONTINUE),
                "a node with no next ends on continue");
    }

    @Test
    void aChoiceWithNoNextEndsTheConversation() {
        CompiledGraph graph = GraphFixtures.graph("hello", GraphFixtures.node("hello", "Hi", null,
                List.of(GraphFixtures.choice("Bye", GraphFixtures.ALWAYS, null))));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);
        runner.start(session);

        assertInstanceOf(Step.Ended.class, runner.choose(session, 0));
    }

    @Test
    void aNodeWithNoChoicesShowsAContinueThatMovesToNext() {
        CompiledGraph graph = GraphFixtures.graph("a",
                GraphFixtures.node("a", "First", "b", List.of()),
                GraphFixtures.node("b", "Second", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);

        PresentedNode first = shown(runner.start(session));
        assertTrue(first.choices().isEmpty(), "no choices means one continue control");
        assertEquals("b", nodeOf(runner.choose(session, DialogueChoicePayload.CONTINUE)));
    }

    @Test
    void aGotoFromAnActionOverridesTheChoiceNext() {
        CompiledGraph graph = GraphFixtures.graph("hello",
                GraphFixtures.node("hello", "Hi", null, List.of(
                        GraphFixtures.choice("Go", GraphFixtures.ALWAYS, "bye", ctx -> ctx.goTo("other")))),
                GraphFixtures.node("bye", "Bye", null, List.of()),
                GraphFixtures.node("other", "Elsewhere", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);
        runner.start(session);

        assertEquals("other", nodeOf(runner.choose(session, 0)));
    }

    @Test
    void anEndFromAnActionEndsEvenWhenNextIsSet() {
        CompiledGraph graph = GraphFixtures.graph("hello",
                GraphFixtures.node("hello", "Hi", null, List.of(
                        GraphFixtures.choice("Stop", GraphFixtures.ALWAYS, "bye", ctx -> ctx.end()))),
                GraphFixtures.node("bye", "Bye", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);
        runner.start(session);

        assertInstanceOf(Step.Ended.class, runner.choose(session, 0));
    }

    @Test
    void anOpenFromAnActionReturnsTheOtherGraph() {
        CompiledGraph graph = GraphFixtures.graph("hello",
                GraphFixtures.node("hello", "Hi", null, List.of(
                        GraphFixtures.choice("Next", GraphFixtures.ALWAYS, null,
                                ctx -> ctx.openGraph(GraphFixtures.OTHER_GRAPH)))));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);
        runner.start(session);

        Step step = runner.choose(session, 0);
        assertInstanceOf(Step.Opened.class, step);
        assertEquals(GraphFixtures.OTHER_GRAPH, ((Step.Opened) step).graph());
    }

    @Test
    void onEnterActionsRunBeforeTheNodeIsShownAndCanRedirectIt() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("start",
                GraphFixtures.node("start", "Never shown", null, List.of(),
                        ctx -> {
                            ctx.flags().set(GraphFixtures.MET, 1);
                            ctx.goTo("skip");
                        }),
                GraphFixtures.node("skip", "Shown", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);

        assertEquals("skip", nodeOf(runner.start(session)));
        assertEquals(1, flags.get(GraphFixtures.MET));
    }

    @Test
    void visitsAreTheCountTheSessionWasOpenedWith() {
        CompiledGraph graph = GraphFixtures.graph("hello",
                GraphFixtures.node("hello", "Hi", null, List.of(
                        GraphFixtures.choice("Only first", ctx -> ctx.visits() == 1, null),
                        GraphFixtures.choice("Only later", ctx -> ctx.visits() > 1, null))));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);

        List<VisibleChoice> shown = shown(runner.start(session)).choices();
        assertEquals(1, shown.size());
        assertEquals(0, shown.get(0).choiceIndex());
    }

    @Test
    void routerPicksBranchesInProportionToTheirWeights() {
        CompiledGraph graph = GraphFixtures.graph("r",
                GraphFixtures.router("r", new WeightedNext("a", 1), new WeightedNext("b", 3)),
                GraphFixtures.node("a", "A", null, List.of()),
                GraphFixtures.node("b", "B", null, List.of()));
        FakeFlags flags = new FakeFlags();
        RandomSource random = RandomSource.create(1234L);
        int draws = 20_000;
        int tookA = 0;
        for (int i = 0; i < draws; i++) {
            DialogueSession session = GraphFixtures.session(i + 1, graph, flags, 1, random);
            if (nodeOf(runner.start(session)).equals("a")) {
                tookA++;
            }
        }
        double share = (double) tookA / draws;
        assertEquals(0.25, share, 0.02, "weight 1 of 4 should be taken about a quarter of the time");
    }

    @Test
    void routerSharesMatchWeightsForThreeBranches() {
        CompiledGraph graph = GraphFixtures.graph("r",
                GraphFixtures.router("r", new WeightedNext("a", 1), new WeightedNext("b", 2),
                        new WeightedNext("c", 3)),
                GraphFixtures.node("a", "A", null, List.of()),
                GraphFixtures.node("b", "B", null, List.of()),
                GraphFixtures.node("c", "C", null, List.of()));
        FakeFlags flags = new FakeFlags();
        RandomSource random = RandomSource.create(99L);
        int draws = 30_000;
        int[] counts = new int[3];
        for (int i = 0; i < draws; i++) {
            DialogueSession session = GraphFixtures.session(i + 1, graph, flags, 1, random);
            counts[nodeOf(runner.start(session)).charAt(0) - 'a']++;
        }
        assertEquals(1.0 / 6, (double) counts[0] / draws, 0.02);
        assertEquals(2.0 / 6, (double) counts[1] / draws, 0.02);
        assertEquals(3.0 / 6, (double) counts[2] / draws, 0.02);
    }

    @Test
    void sameSeedRoutesTheSameWay() {
        CompiledGraph graph = GraphFixtures.graph("r",
                GraphFixtures.router("r", new WeightedNext("a", 1), new WeightedNext("b", 1)),
                GraphFixtures.node("a", "A", null, List.of()),
                GraphFixtures.node("b", "B", null, List.of()));
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            first.add(nodeOf(runner.start(GraphFixtures.session(i, graph, new FakeFlags(), 1, 42L))));
        }
        for (int i = 0; i < 50; i++) {
            second.add(nodeOf(runner.start(GraphFixtures.session(i, graph, new FakeFlags(), 1, 42L))));
        }
        assertEquals(first, second);
    }

    @Test
    void aRouterLoopIsCutOffWithAnEndedConversation() {
        CompiledGraph graph = GraphFixtures.graph("r",
                GraphFixtures.router("r", new WeightedNext("r", 1)));
        assertInstanceOf(Step.Ended.class, runner.start(GraphFixtures.session(1, graph, new FakeFlags(), 1, 7)));
    }

    @Test
    void aJumpLoopInOnEnterActionsIsCutOffWithAnEndedConversation() {
        CompiledGraph graph = GraphFixtures.graph("a",
                GraphFixtures.node("a", "A", null, List.of(), ctx -> ctx.goTo("a")));
        assertInstanceOf(Step.Ended.class, runner.start(GraphFixtures.session(1, graph, new FakeFlags(), 1, 7)));
    }

    @Test
    void hiddenChoicesAreLeftOutAndGrayedOutChoicesKeepTheirIndex() {
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", null, List.of(
                GraphFixtures.choice("Open", GraphFixtures.ALWAYS, null),
                GraphFixtures.choice("Hidden", GraphFixtures.NEVER, null),
                GraphFixtures.grayed("Locked", GraphFixtures.NEVER, "Not yet", null),
                GraphFixtures.choice("Last", GraphFixtures.ALWAYS, null))));

        List<VisibleChoice> shown = shown(runner.start(GraphFixtures.session(1, graph, new FakeFlags(), 1, 7)))
                .choices();
        assertEquals(3, shown.size(), "the hidden choice takes no index");
        assertEquals("Open", shown.get(0).text().getString());
        assertEquals("Locked", shown.get(1).text().getString());
        assertFalse(shown.get(1).enabled());
        assertEquals("Not yet", shown.get(1).unavailableReason().orElseThrow().getString());
        assertEquals("Last", shown.get(2).text().getString());
        assertEquals(3, shown.get(2).choiceIndex());
    }

    @Test
    void pickingAGrayedOutEntryRunsNothingAndShowsTheNodeAgain() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", null, List.of(
                GraphFixtures.grayed("Locked", GraphFixtures.NEVER, "Not yet", "bye", ctx -> ctx.flags().set(GraphFixtures.MET, 1)))),
                GraphFixtures.node("bye", "Bye", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);
        runner.start(session);

        Step step = runner.choose(session, 0);
        assertEquals("menu", nodeOf(step));
        assertEquals(0, flags.get(GraphFixtures.MET));
    }

    @Test
    void anEntryThatBecomesAvailableKeepsItsIndexAndCanBePicked() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", null, List.of(
                GraphFixtures.choice("Open", GraphFixtures.ALWAYS, null),
                GraphFixtures.grayed("Gate", ctx -> ctx.flags().isSet(GraphFixtures.MET), "Locked", null,
                        ctx -> ctx.flags().set(GraphFixtures.MET, 2)))));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);
        runner.start(session);
        flags.set(GraphFixtures.MET, 1);
        runner.choose(session, 1);
        assertEquals(2, flags.get(GraphFixtures.MET), "the choice that was grayed out runs after it becomes available");
    }

    @Test
    void aStaleIndexIsRefusedWhenAHiddenChoiceAppearsBeforeIt() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", null, List.of(
                GraphFixtures.choice("A", GraphFixtures.ALWAYS, null),
                GraphFixtures.choice("B", ctx -> ctx.flags().isSet(GraphFixtures.MET), null,
                        ctx -> ctx.flags().set(GraphFixtures.MET, 9)),
                GraphFixtures.choice("C", GraphFixtures.ALWAYS, null, ctx -> ctx.flags().set(GraphFixtures.MET, 5)))));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);
        List<VisibleChoice> before = shown(runner.start(session)).choices();
        assertEquals("C", before.get(1).text().getString(), "with B hidden, index 1 is C");

        flags.set(GraphFixtures.MET, 1);
        Step step = runner.choose(session, 1);
        assertEquals("menu", nodeOf(step), "index 1 now names B, so the pick is refused");
        assertEquals(1, flags.get(GraphFixtures.MET), "nothing from B or C ran");
        assertEquals("B", session.presented().get(1).text().getString(), "the shown list was refreshed");
    }

    @Test
    void anIndexOutsideTheShownEntriesIsRefused() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", null, List.of(
                GraphFixtures.choice("A", GraphFixtures.ALWAYS, "bye", ctx -> ctx.flags().set(GraphFixtures.MET, 1)))),
                GraphFixtures.node("bye", "Bye", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);
        runner.start(session);

        assertEquals("menu", nodeOf(runner.choose(session, 99)), "a forged index shows the node again");
        assertEquals("menu", nodeOf(runner.choose(session, -2)));
        assertEquals(0, flags.get(GraphFixtures.MET));
    }

    @Test
    void continueIsRefusedWhileEntriesAreShown() {
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", "bye", List.of(
                GraphFixtures.choice("A", GraphFixtures.ALWAYS, null))),
                GraphFixtures.node("bye", "Bye", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);
        runner.start(session);

        assertEquals("menu", nodeOf(runner.choose(session, DialogueChoicePayload.CONTINUE)));
    }

    @Test
    void aNodeWhoseEntriesAreAllHiddenShowsAContinue() {
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", "bye", List.of(
                GraphFixtures.choice("A", GraphFixtures.NEVER, null))),
                GraphFixtures.node("bye", "Bye", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);

        assertTrue(shown(runner.start(session)).choices().isEmpty());
        assertEquals("bye", nodeOf(runner.choose(session, DialogueChoicePayload.CONTINUE)));
    }

    @Test
    void providedEntriesExpandWhereTheChoiceSitsAndRunTheirOwnActions() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("board", GraphFixtures.node("board", "Jobs", null, List.of(
                GraphFixtures.providing(GraphFixtures.ALWAYS, ctx -> List.of(
                        GraphFixtures.entry("Job one", true, "bye", ctx2 -> ctx2.flags().set(GraphFixtures.MET, 3)),
                        GraphFixtures.entry("Job two", false, null, ctx2 -> {}))),
                GraphFixtures.choice("Back", GraphFixtures.ALWAYS, null))),
                GraphFixtures.node("bye", "Bye", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);

        List<VisibleChoice> shown = shown(runner.start(session)).choices();
        assertEquals(3, shown.size());
        assertEquals("Job one", shown.get(0).text().getString());
        assertEquals("Job two", shown.get(1).text().getString());
        assertFalse(shown.get(1).enabled());
        assertEquals("Back", shown.get(2).text().getString());

        assertEquals("bye", nodeOf(runner.choose(session, 0)));
        assertEquals(3, flags.get(GraphFixtures.MET));
    }

    @Test
    void aProviderBehindAFailedGateContributesNothing() {
        CompiledGraph graph = GraphFixtures.graph("board", GraphFixtures.node("board", "Jobs", null, List.of(
                GraphFixtures.providing(GraphFixtures.NEVER, ctx -> {
                    throw new AssertionError("a gated provider must not run");
                }))));
        assertTrue(shown(runner.start(GraphFixtures.session(1, graph, new FakeFlags(), 1, 7))).choices().isEmpty());
    }

    @Test
    void anActionThatThrowsEndsTheConversationInsteadOfEscaping() {
        CompiledGraph graph = GraphFixtures.graph("hello", GraphFixtures.node("hello", "Hi", null, List.of(
                GraphFixtures.choice("Boom", GraphFixtures.ALWAYS, "bye", (DialogueAction) ctx -> {
                    throw new IllegalStateException("boom");
                }))),
                GraphFixtures.node("bye", "Bye", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);
        runner.start(session);

        assertInstanceOf(Step.Ended.class, runner.choose(session, 0));
    }

    @Test
    void acceptsOnlyTheCurrentSessionAndTheNodeLastSent() {
        CompiledGraph graph = GraphFixtures.graph("hello", GraphFixtures.node("hello", "Hi", null, List.of(
                GraphFixtures.choice("A", GraphFixtures.ALWAYS, null))));
        DialogueSession session = GraphFixtures.session(5, graph, new FakeFlags(), 1, 7);
        runner.start(session);

        assertTrue(runner.accepts(session, 5, "hello"));
        assertFalse(runner.accepts(session, 4, "hello"), "a stale session id is ignored");
        assertFalse(runner.accepts(session, 5, "bye"), "a node that is not the one sent is ignored");
        assertFalse(runner.accepts(session, 5, null));
    }

    @Test
    void aGraphOfNoChoicesAndNoNextEndsOnContinue() {
        CompiledGraph graph = GraphFixtures.graph("bye", GraphFixtures.node("bye", "Safe travels", null, List.of()));
        DialogueSession session = GraphFixtures.session(1, graph, new FakeFlags(), 1, 7);
        runner.start(session);
        assertInstanceOf(Step.Ended.class, runner.choose(session, DialogueChoicePayload.CONTINUE));
    }

    @Test
    void aChoiceConditionThatThrowsEndsTheConversationInsteadOfEscaping() {
        CompiledGraph graph = GraphFixtures.graph("hello", GraphFixtures.node("hello", "Hi", null, List.of(
                GraphFixtures.choice("Broken", ctx -> {
                    throw new IllegalStateException("no such flag");
                }, "bye"))),
                GraphFixtures.node("bye", "Bye", null, List.of()));

        assertInstanceOf(Step.Ended.class, runner.start(GraphFixtures.session(1, graph, new FakeFlags(), 1, 7)));
    }

    @Test
    void aProviderThatThrowsEndsTheConversationInsteadOfEscaping() {
        CompiledGraph graph = GraphFixtures.graph("board", GraphFixtures.node("board", "Jobs", null, List.of(
                GraphFixtures.providing(GraphFixtures.ALWAYS, ctx -> {
                    throw new IllegalStateException("the board is closed");
                }))));

        assertInstanceOf(Step.Ended.class, runner.start(GraphFixtures.session(1, graph, new FakeFlags(), 1, 7)));
    }

    @Test
    void aConditionThatThrowsWhenAPickIsWorkedOutEndsTheConversation() {
        FakeFlags flags = new FakeFlags();
        CompiledGraph graph = GraphFixtures.graph("menu", GraphFixtures.node("menu", "Pick", null, List.of(
                GraphFixtures.choice("A", GraphFixtures.ALWAYS, null),
                GraphFixtures.choice("B", ctx -> {
                    if (ctx.flags().isSet(GraphFixtures.MET)) {
                        throw new IllegalStateException("gate broke");
                    }
                    return true;
                }, null))));
        DialogueSession session = GraphFixtures.session(1, graph, flags, 1, 7);
        runner.start(session);
        flags.set(GraphFixtures.MET, 1);

        assertInstanceOf(Step.Ended.class, runner.choose(session, 0));
    }

    @Test
    void routerWeightsThatEachFitAnIntButSumPastItStillRoute() {
        CompiledGraph graph = GraphFixtures.graph("r",
                GraphFixtures.router("r", new WeightedNext("a", 2_000_000_000), new WeightedNext("b", 2_000_000_000)),
                GraphFixtures.node("a", "A", null, List.of()),
                GraphFixtures.node("b", "B", null, List.of()));
        FakeFlags flags = new FakeFlags();
        RandomSource random = RandomSource.create(5L);
        int draws = 4_000;
        int tookA = 0;
        for (int i = 0; i < draws; i++) {
            DialogueSession session = GraphFixtures.session(i + 1, graph, flags, 1, random);
            if (nodeOf(runner.start(session)).equals("a")) {
                tookA++;
            }
        }
        assertEquals(0.5, (double) tookA / draws, 0.03, "equal weights take each branch about half the time");
    }
}
