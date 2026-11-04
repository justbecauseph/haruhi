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

    @GameTest(maxTicks = 400)
    public void importActivateAndRollbackThroughStorage(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentServiceImpl content = content();
        // Startup activates the seed (or a stored revision) during SERVER_STARTED. The flow starts after a delay, so
        // the revisions this test activates are the newest ones and startup cannot activate over them.
        AtomicReference<CompletableFuture<Outcome>> flow = new AtomicReference<>();
        helper.runAfterDelay(100, () -> flow.set(runFlow(server, content)));
        helper.succeedWhen(() -> {
            CompletableFuture<Outcome> running = flow.get();
            helper.assertTrue(running != null && running.isDone(),
                    "the import, activation and rollback have not finished");
            Throwable failure = running.handle((value, error) -> error).join();
            helper.assertTrue(failure == null, "the flow failed: " + failure);
            Outcome outcome = running.join();

            helper.assertValueEqual(outcome.activatedFirst(), outcome.first(), "activating the first revision");
            helper.assertValueEqual(outcome.activatedSecond(), outcome.second(), "activating the second revision");
            helper.assertValueEqual(outcome.rolledBack(), outcome.first(),
                    "rollback goes to the revision with the latest activation other than the active one");
            helper.assertValueEqual(outcome.activeAfterRollback(), OptionalLong.of(outcome.first()),
                    "active revision after rollback");
            helper.assertValueEqual(outcome.activeNpcName(), "First", "name of the NPC after rollback");
            helper.assertTrue(outcome.listing().stream().anyMatch(r -> r.id() == outcome.first() && r.active()),
                    "storage does not mark the rolled-back revision active");
            helper.assertTrue(outcome.listing().stream().anyMatch(r -> r.id() == outcome.second() && !r.active()),
                    "storage still marks the second revision active");
        });
    }

    /** Stores two bundles, activates both in order, then rolls back. Each step runs after the previous completes. */
    private static CompletableFuture<Outcome> runFlow(MinecraftServer server, ContentServiceImpl content) {
        ValidatedBundle first = content.validate(gateBundle("First")).result().orElseThrow();
        ValidatedBundle second = content.validate(gateBundle("Second")).result().orElseThrow();
        return content.store(first, "gametest").thenCompose(storedFirst ->
                content.store(second, "gametest").thenCompose(storedSecond ->
                        content.activateRevision(storedFirst.id(), "gametest").thenCompose(activatedFirst ->
                                content.activateRevision(storedSecond.id(), "gametest").thenCompose(activatedSecond ->
                                        content.rollback("gametest").thenCompose(rolledBack ->
                                                onServerThread(server, HaruhiServices.storage().listRevisions(50))
                                                        .thenApply(listing -> new Outcome(
                                                                storedFirst.id(),
                                                                storedSecond.id(),
                                                                activatedFirst.id(),
                                                                activatedSecond.id(),
                                                                rolledBack.id(),
                                                                content.activeRevisionId(),
                                                                npcName(content.active()),
                                                                listing)))))));
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
            long first,
            long second,
            long activatedFirst,
            long activatedSecond,
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
