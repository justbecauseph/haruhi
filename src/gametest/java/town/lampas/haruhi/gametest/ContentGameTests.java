package town.lampas.haruhi.gametest;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.ContentServiceImpl;
import town.lampas.haruhi.content.ValidatedBundle;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.NpcProfile;
import town.lampas.haruhi.storage.RevisionSummary;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * Server GameTests owned by the content component. They need every module, so they run only after integration. The
 * validation tests run on the server thread against the real built-ins and the server's registries.
 */
public final class ContentGameTests {
    private static final Identifier GREETER = Identifier.parse("haruhi:example/greeter");
    private static final Identifier GATE = Identifier.parse("haruhi:example/gate");
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


    /** AGENTS.md: every haruhi:open graph must exist in the bundle. Checked with the real built-in action. */
    @GameTest(maxTicks = 20)
    public void openMustNameAGraphOfTheBundle(GameTestHelper helper) {
        DataResult<ValidatedBundle> missing = content().validate(openBundle("haruhi:example/nowhere"));
        List<String> lines = missing.error().map(error -> error.message().lines().toList()).orElse(List.of());
        helper.assertTrue(lines.size() == 1, "an open of a missing graph gives one problem, not " + lines);
        helper.assertTrue(lines.getFirst().startsWith("haruhi:example/gate#hello: ")
                        && lines.getFirst().contains("haruhi:example/nowhere"),
                "the problem does not name the node and the missing graph: " + lines);

        DataResult<ValidatedBundle> present = content().validate(openBundle("haruhi:example/gate"));
        helper.assertTrue(present.error().isEmpty(), "an open of a graph in the bundle was refused: "
                + present.error().map(error -> error.message()).orElse(""));
        helper.succeed();
    }

    /**
     * Every /haruhi command is for game masters: AGENTS.md asks for permission checks, and the content, dialogue and
     * npc command classes each say so. Walks the tree the server registered from every module. A source below game
     * master must not reach any node that runs a command, and a game master must reach all of them.
     */
    @GameTest(maxTicks = 20)
    public void everyHaruhiCommandIsForGameMastersOnly(GameTestHelper helper) {
        CommandDispatcher<CommandSourceStack> dispatcher =
                helper.getLevel().getServer().getCommands().getDispatcher();
        CommandNode<CommandSourceStack> haruhi = dispatcher.getRoot().getChild("haruhi");
        helper.assertTrue(haruhi != null, "no /haruhi command is registered");

        List<String> every = runnable(haruhi, node -> true);
        for (String part : List.of("content", "dialogue", "npc")) {
            helper.assertTrue(every.stream().anyMatch(path -> path.startsWith("haruhi " + part + " ")),
                    "no /haruhi " + part + " command runs anything: " + every);
        }
        CommandSourceStack gameMaster = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
        helper.assertTrue(runnable(haruhi, node -> node.canUse(gameMaster)).equals(every),
                "a game master cannot run every /haruhi command: " + every);
        Map<String, PermissionSet> below = Map.of(
                "no permissions", PermissionSet.NO_PERMISSIONS, "moderator", LevelBasedPermissionSet.MODERATOR);
        below.forEach((name, permissions) -> {
            CommandSourceStack source = Commands.createCompilationContext(permissions);
            List<String> reachable = runnable(haruhi, node -> node.canUse(source));
            helper.assertTrue(reachable.isEmpty(), "a source with " + name + " can run " + reachable);
        });
        helper.succeed();
    }

    /** The paths of the nodes from {@code root} down that run a command, through nodes {@code usable} accepts. */
    private static List<String> runnable(
            CommandNode<CommandSourceStack> root, Predicate<CommandNode<CommandSourceStack>> usable) {
        List<String> found = new ArrayList<>();
        collect(root, root.getName(), usable, found);
        return found;
    }

    private static void collect(
            CommandNode<CommandSourceStack> node,
            String path,
            Predicate<CommandNode<CommandSourceStack>> usable,
            List<String> found) {
        if (!usable.test(node)) {
            return;
        }
        if (node.getCommand() != null) {
            found.add(path);
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            collect(child, path + " " + child.getUsageText(), usable, found);
        }
    }

    private static String openBundle(String target) {
        return """
                {
                  "schema": 1,
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
                        "hello": { "text": "Hi.", "on_enter": [ { "type": "haruhi:open", "graph": "%s" } ] }
                      }
                    }
                  }
                }
                """.formatted(target);
    }

    /**
     * Activates revisions after the startup load has finished, so the startup cannot replace them. The activations of
     * Charlie, Alpha and Bravo and a rollback are requested in the same tick. The service runs them one at a time in
     * that order and stamps each activation later than the one before, so rollback must pick Alpha: the latest
     * activation other than the active Bravo. Charlie is the newest revision by creation. The test runs in its own
     * environment, because its activations replace the content that tests in another batch use.
     */
    @GameTest(environment = ROLLBACK_ENVIRONMENT, maxTicks = 400)
    public void rollbackGoesToTheLatestActivationNotTheNewestRevision(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentServiceImpl content = content();
        AtomicReference<CompletableFuture<Outcome>> flow = new AtomicReference<>();
        helper.succeedWhen(() -> {
            if (flow.get() == null) {
                helper.assertTrue(content.isStartupComplete(), "the startup content load has not finished");
                flow.set(runFlow(server, content));
            }
            CompletableFuture<Outcome> running = flow.get();
            helper.assertTrue(running.isDone(), "the store, activation and rollback have not finished");
            Throwable failure = running.handle((value, error) -> error).join();
            helper.assertTrue(failure == null, "the flow failed: " + failure);
            Outcome outcome = running.join();

            List<Long> requested = List.of(outcome.charlie(), outcome.alpha(), outcome.bravo());
            helper.assertTrue(outcome.activated().stream().map(StoredRevision::id).toList().equals(requested),
                    "the activations returned " + outcome.activated() + ", not the revisions " + requested);
            for (int i = 1; i < outcome.activated().size(); i++) {
                Instant before = outcome.activated().get(i - 1).activatedAt().orElseThrow();
                Instant after = outcome.activated().get(i).activatedAt().orElseThrow();
                helper.assertTrue(after.isAfter(before), "activation " + (i + 1) + " is stamped " + after
                        + ", not after activation " + i + " at " + before);
            }
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
     * Stores Alpha, Bravo and Charlie one after another, then requests the activations of Charlie, Alpha and Bravo and
     * the rollback together, without waiting between them.
     */
    private static CompletableFuture<Outcome> runFlow(MinecraftServer server, ContentServiceImpl content) {
        ValidatedBundle alpha = content.validate(gateBundle("Alpha")).result().orElseThrow();
        ValidatedBundle bravo = content.validate(gateBundle("Bravo")).result().orElseThrow();
        ValidatedBundle charlie = content.validate(gateBundle("Charlie")).result().orElseThrow();
        return content.store(alpha, "gametest").thenCompose(storedAlpha ->
                content.store(bravo, "gametest").thenCompose(storedBravo ->
                        content.store(charlie, "gametest").thenCompose(storedCharlie -> {
                            List<CompletableFuture<StoredRevision>> activations = List.of(
                                    content.activateRevision(storedCharlie.id(), "gametest"),
                                    content.activateRevision(storedAlpha.id(), "gametest"),
                                    content.activateRevision(storedBravo.id(), "gametest"));
                            CompletableFuture<StoredRevision> rollback = content.rollback("gametest");
                            return CompletableFuture.allOf(activations.toArray(CompletableFuture[]::new))
                                    .thenCompose(ignored -> rollback)
                                    .thenCompose(rolledBack ->
                                            onServerThread(server, HaruhiServices.storage().listRevisions(50))
                                                    .thenApply(listing -> new Outcome(
                                                            storedAlpha.id(),
                                                            storedBravo.id(),
                                                            storedCharlie.id(),
                                                            activations.stream().map(CompletableFuture::join).toList(),
                                                            rolledBack.id(),
                                                            content.activeRevisionId(),
                                                            npcName(content.active()),
                                                            listing)));
                        })));
    }

    /** Ends the storage callbacks of a flow on the server thread, as the content service does for its own futures. */
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
            List<StoredRevision> activated,
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
