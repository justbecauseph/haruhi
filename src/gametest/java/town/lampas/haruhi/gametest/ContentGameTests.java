package town.lampas.haruhi.gametest;

import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.ContentServiceImpl;
import town.lampas.haruhi.content.ValidatedBundle;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.NpcProfile;
import town.lampas.haruhi.storage.RevisionSummary;

/**
 * Server GameTests owned by the content component. They need every module, so they run only after integration. The
 * validation tests run on the server thread against the real built-ins and the server's registries.
 */
public final class ContentGameTests {
    private static final Identifier GREETER = Identifier.parse("haruhi:example/greeter");
    private static final Identifier GATE = Identifier.parse("haruhi:example/gate");
    private static final long ACTIVATION_SPACING_TICKS = 3;
    /**
     * The test environment of the rollback test, in {@code data/haruhi-gametest/test_environment/content.json}. The
     * GameTest runner runs each environment as its own batch, one batch after another, so the activations in that test
     * cannot replace the content another batch's test is using.
     */
    private static final String ROLLBACK_ENVIRONMENT = "haruhi-gametest:content";

    @GameTest(maxTicks = 20)
    public void seedBundleValidatesWithTheBuiltIns(GameTestHelper helper) {
        DataResult<ValidatedBundle> result = content().validate(seedText());
        String problems = result.error().map(error -> error.message()).orElse("");
        helper.assertTrue(problems.isEmpty(), "the seed bundle does not validate:\n" + problems);

        ContentBundle bundle = result.result().orElseThrow().bundle();
        DialogueGraph greeter = bundle.dialogues().get(GREETER);
        helper.assertTrue(greeter != null, "the seed bundle has no haruhi:example/greeter graph");
        helper.assertValueEqual(greeter.start(), "hello", "start node of the greeter graph");
        NpcProfile npc = bundle.npcs().get(GREETER);
        helper.assertTrue(npc != null, "the seed bundle has no haruhi:example/greeter NPC");
        helper.assertValueEqual(npc.name().getString(), "Greeter", "name of the greeter NPC");
        helper.succeed();
    }

    @GameTest(maxTicks = 20)
    public void unknownConditionAndUnreachableNodeAreBothRefused(GameTestHelper helper) {
        String json = """
                {
                  "schema": 1,
                  "flags": { "haruhi:example/gate": ["seen"] },
                  "npcs": {
                    "haruhi:example/gate": {
                      "name": "Gate",
                      "texture": "minecraft:textures/entity/player/wide/steve.png"
                    }
                  },
                  "dialogues": {
                    "haruhi:example/gate": {
                      "start": "hello",
                      "nodes": {
                        "hello": {
                          "text": "Hi.",
                          "choices": [
                            { "text": "Bye", "next": "bye", "only_if": [ { "type": "haruhi:no_such_condition" } ] }
                          ]
                        },
                        "bye": { "text": "Bye." },
                        "orphan": { "text": "Nobody comes here." }
                      }
                    }
                  }
                }
                """;
        DataResult<ValidatedBundle> result = content().validate(json);
        helper.assertTrue(result.error().isPresent(),
                "a bundle with an unknown condition and an orphan node was accepted");
        List<String> lines = result.error().orElseThrow().message().lines().toList();
        helper.assertTrue(lines.stream().anyMatch(line -> line.contains("haruhi:no_such_condition is not registered")),
                "the unknown condition is not listed: " + lines);
        helper.assertTrue(lines.stream().anyMatch(line -> line.startsWith("haruhi:example/gate#orphan: ")
                        && line.contains("is not reachable from start 'hello'")),
                "the unreachable node is not listed: " + lines);
        helper.assertValueEqual(lines.size(), 2, "number of problems");
        helper.succeed();
    }

    /**
     * Activates revisions after the startup load has finished, so the startup cannot replace them. Rollback must pick
     * the revision with the latest activation other than the active one, which is not the newest revision by creation.
     * It runs in its own environment, because the other content-activating tests share the active content.
     */
    @GameTest(environment = ROLLBACK_ENVIRONMENT, maxTicks = 400)
    public void rollbackGoesToTheLatestActivationNotTheNewestRevision(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentServiceImpl content = content();
        AtomicReference<CompletableFuture<Outcome>> flow = new AtomicReference<>();
        helper.succeedWhen(() -> {
            if (flow.get() == null) {
                helper.assertTrue(content.isStartupComplete(), "the startup content load has not finished");
                flow.set(runFlow(helper, server, content));
            }
            CompletableFuture<Outcome> running = flow.get();
            helper.assertTrue(running.isDone(), "the store, activation and rollback have not finished");
            Throwable failure = running.handle((value, error) -> error).join();
            helper.assertTrue(failure == null, "the flow failed: " + failure);
            Outcome outcome = running.join();

            helper.assertValueEqual(outcome.rolledBack(), outcome.alpha(),
                    "rollback goes to Alpha, the latest activation other than the active Bravo; Charlie was"
                            + " created last but activated first");
            helper.assertValueEqual(outcome.activeAfterRollback(), OptionalLong.of(outcome.alpha()),
                    "active revision after rollback");
            helper.assertValueEqual(outcome.activeNpcName(), "Alpha", "name of the NPC after rollback");
            helper.assertTrue(outcome.listing().stream().anyMatch(r -> r.id() == outcome.alpha() && r.active()),
                    "storage does not mark the rolled-back revision active");
            helper.assertTrue(outcome.listing().stream().anyMatch(r -> r.id() == outcome.bravo() && !r.active()),
                    "storage still marks Bravo active");
            // Deactivated revisions keep the time of their last activation, which rollback reads.
            helper.assertTrue(outcome.listing().stream()
                            .anyMatch(r -> r.id() == outcome.bravo() && !r.active() && r.activatedAt().isPresent()),
                    "Bravo lost its activation time when it was deactivated");
            helper.assertTrue(outcome.listing().stream()
                            .anyMatch(r -> r.id() == outcome.charlie() && !r.active() && r.activatedAt().isPresent()),
                    "Charlie lost its activation time when it was deactivated");
        });
    }

    /**
     * Stores Alpha, Bravo and Charlie, activates Charlie, then Alpha, then Bravo, and rolls back. Each step runs after
     * the previous one completes.
     */
    private static CompletableFuture<Outcome> runFlow(
            GameTestHelper helper, MinecraftServer server, ContentServiceImpl content) {
        ValidatedBundle alpha = content.validate(gateBundle("Alpha")).result().orElseThrow();
        ValidatedBundle bravo = content.validate(gateBundle("Bravo")).result().orElseThrow();
        ValidatedBundle charlie = content.validate(gateBundle("Charlie")).result().orElseThrow();
        return content.store(alpha, "gametest").thenCompose(storedAlpha ->
                content.store(bravo, "gametest").thenCompose(storedBravo ->
                        content.store(charlie, "gametest").thenCompose(storedCharlie ->
                                activateInOrder(helper, content, storedCharlie.id(), storedAlpha.id(),
                                        storedBravo.id())
                                        .thenCompose(ignored -> content.rollback("gametest"))
                                        .thenCompose(rolledBack ->
                                                onServerThread(server, HaruhiServices.storage().listRevisions(50))
                                                        .thenApply(listing -> new Outcome(
                                                                storedAlpha.id(),
                                                                storedBravo.id(),
                                                                storedCharlie.id(),
                                                                rolledBack.id(),
                                                                content.activeRevisionId(),
                                                                npcName(content.active()),
                                                                listing))))));
    }

    /**
     * Activates the revisions one after another, in the order given. Each activation is stamped with the time it runs,
     * and two activations in the same clock tick can share a stamp. Rollback then cannot tell them apart, so the steps
     * are spaced by a few server ticks.
     */
    private static CompletableFuture<Void> activateInOrder(
            GameTestHelper helper, ContentServiceImpl content, long... ids) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (long id : ids) {
            chain = chain.thenCompose(ignored -> content.activateRevision(id, "gametest"))
                    .thenCompose(revision -> afterTicks(helper, ACTIVATION_SPACING_TICKS));
        }
        return chain;
    }

    /** Completes on the server thread after {@code ticks} server ticks. */
    private static CompletableFuture<Void> afterTicks(GameTestHelper helper, long ticks) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        helper.runAfterDelay(ticks, () -> done.complete(null));
        return done;
    }

    /** Ends a flow's storage callbacks back on the server thread, as the content service does for its own futures. */
    private static <T> CompletableFuture<T> onServerThread(MinecraftServer server, CompletableFuture<T> source) {
        CompletableFuture<T> hopped = new CompletableFuture<>();
        source.whenComplete((value, error) -> server.execute(() -> {
            if (error != null) {
                hopped.completeExceptionally(error);
            } else {
                hopped.complete(value);
            }
        }));
        return hopped;
    }

    private record Outcome(
            long alpha,
            long bravo,
            long charlie,
            long rolledBack,
            OptionalLong activeAfterRollback,
            String activeNpcName,
            List<RevisionSummary> listing) {}

    private static String npcName(ContentBundle bundle) {
        NpcProfile npc = bundle.npcs().get(GATE);
        return npc == null ? "" : npc.name().getString();
    }

    private static String gateBundle(String npcName) {
        return """
                {
                  "schema": 1,
                  "flags": { "haruhi:example/gate": ["seen"] },
                  "npcs": {
                    "haruhi:example/gate": {
                      "name": "%s",
                      "texture": "minecraft:textures/entity/player/wide/steve.png"
                    }
                  },
                  "dialogues": {
                    "haruhi:example/gate": { "start": "hello", "nodes": { "hello": { "text": "Hi." } } }
                  }
                }
                """.formatted(npcName);
    }

    private static ContentServiceImpl content() {
        return (ContentServiceImpl) HaruhiServices.content();
    }

    private static String seedText() {
        try (InputStream in = ContentGameTests.class.getResourceAsStream("/haruhi/seed_bundle.json")) {
            if (in == null) {
                throw new IllegalStateException("The jar has no /haruhi/seed_bundle.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the seed bundle", e);
        }
    }
}
