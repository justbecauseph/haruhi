package town.lampas.haruhi.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.jsonrpc.api.Schema;
import net.minecraft.server.jsonrpc.methods.DiscoveryService;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.ContentServiceImpl;
import town.lampas.haruhi.content.ValidatedBundle;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.content.model.NpcProfile;
import town.lampas.haruhi.content.remote.ContentMethods;
import town.lampas.haruhi.content.remote.ContentRpc;
import town.lampas.haruhi.content.remote.RemoteContentConfig;
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

    /**
     * {@code /haruhi content validate} replies after the command has returned, so a source that collects output only
     * while the command runs, as RCON does, gets nothing, and the server log must carry the reply. Validates a file
     * under {@code config/haruhi/bundles/} and a missing file, each from a source that records what it is sent, and
     * reads Haruhi's log through a Log4j appender.
     *
     * <p>The commands run in a task queued on the server, as RCON's {@code executeBlocking} runs them. A GameTest tick
     * is not inside a task, and there {@code server.execute} runs at once instead of queuing, so a file read that
     * finished before the command returned would reply inline.
     */
    @GameTest(maxTicks = 200)
    public void contentRepliesAfterTheCommandReturnsGoToTheLog(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        String file = "haruhi-gametest/replies-" + UUID.randomUUID() + ".json";
        String missing = "haruhi-gametest/missing-" + UUID.randomUUID() + ".json";
        Path path = FabricLoader.getInstance().getConfigDir().resolve("haruhi").resolve("bundles").resolve(file);
        String seed = seedText();
        CompletableFuture<Void> written = CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, seed, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool());
        LogCapture log = new LogCapture();
        RecordingSource valid = new RecordingSource();
        RecordingSource absent = new RecordingSource();
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<String> atReturn = new AtomicReference<>();
        Runnable cleanUp = () -> {
            log.detach();
            CompletableFuture.runAsync(() -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, Util.ioPool());
        };
        // On a timeout too, so a failed run leaves no appender on the logger for the tests after it.
        helper.runAfterDelay(195, cleanUp);
        helper.succeedWhen(() -> {
            if (!started.get()) {
                helper.assertTrue(written.isDone(), "the bundle file is not written yet");
                helper.assertTrue(!written.isCompletedExceptionally(), "could not write " + path);
                started.set(true);
                log.attach();
                server.schedule(server.wrapRunnable(() -> {
                    server.getCommands().performPrefixedCommand(
                            valid.stack(server, "haruhi-gametest-valid"), "haruhi content validate " + file);
                    server.getCommands().performPrefixedCommand(
                            absent.stack(server, "haruhi-gametest-absent"), "/haruhi content validate " + missing);
                    atReturn.set(valid.lines() + " " + absent.lines());
                }));
            }
            helper.assertTrue(atReturn.get() != null, "the queued commands have not run yet");
            helper.assertValueEqual(atReturn.get(), "[] []", "what the sources had when the commands returned");

            helper.assertTrue(valid.lines().size() == 1 && valid.lines().getFirst().startsWith("Valid: "),
                    "the source of a valid file was sent " + valid.lines());
            String validLine = "INFO haruhi-gametest-valid ran /haruhi content validate " + file + ": "
                    + valid.lines().getFirst();
            helper.assertTrue(log.lines().contains(validLine), "the log has no '" + validLine + "': " + log.lines());

            helper.assertTrue(absent.lines().size() == 1
                            && absent.lines().getFirst().startsWith("There is no bundle file " + missing),
                    "the source of a missing file was sent " + absent.lines());
            String absentLine = "WARN haruhi-gametest-absent ran /haruhi content validate " + missing + ": "
                    + absent.lines().getFirst();
            helper.assertTrue(log.lines().contains(absentLine),
                    "the log has no '" + absentLine + "': " + log.lines());
            cleanUp.run();
        });
    }

    /** A command source that keeps the text of every message it is sent, and never informs operators. */
    private static final class RecordingSource implements CommandSource {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        CommandSourceStack stack(MinecraftServer server, String name) {
            return new CommandSourceStack(this, Vec3.ZERO, Vec2.ZERO, server.overworld(),
                    LevelBasedPermissionSet.OWNER, name, Component.literal(name), server, null);
        }

        List<String> lines() {
            return List.copyOf(lines);
        }

        @Override
        public void sendSystemMessage(Component message) {
            lines.add(message.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    /**
     * Records the level and text of what Haruhi's logger writes. It hangs on the root logger configuration of the
     * Log4j context the SLF4J binding gives {@link Haruhi}'s class loader, which is the context {@link Haruhi#LOGGER}
     * writes to, and keeps only events of that logger. {@code Logger.addAppender} is not used: it gives the logger a
     * configuration of its own that is not additive, which would cut Haruhi's log off from the console and the files.
     */
    private static final class LogCapture extends AbstractAppender {
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private LoggerContext context;

        LogCapture() {
            super("haruhi-gametest-log", null, null, true, Property.EMPTY_ARRAY);
        }

        void attach() {
            context = (LoggerContext) LogManager.getContext(Haruhi.class.getClassLoader(), false);
            start();
            context.getConfiguration().getRootLogger().addAppender(this, null, null);
            context.updateLoggers();
        }

        /** Takes the appender off again. Does nothing when it is not attached. */
        void detach() {
            if (context != null) {
                context.getConfiguration().getRootLogger().removeAppender(getName());
                context.updateLoggers();
                context = null;
                stop();
            }
        }

        List<String> lines() {
            return List.copyOf(lines);
        }

        @Override
        public void append(LogEvent event) {
            if (Haruhi.MOD_ID.equals(event.getLoggerName())) {
                lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
            }
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

    /**
     * The environment of the Portal tests that activate content, so the activation cannot replace content that tests in
     * another batch use.
     */
    private static final String PORTAL_ENVIRONMENT = "haruhi-gametest:content_portal";
    private static final String PORTAL_TOKEN = "gametest-token-7c1e";
    private static final Identifier PORTAL_NPC = Identifier.parse("haruhi:fixture/portal");
    /** The fetch timeout of the Portal tests' handlers. */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private static final long PENDING_TICK_MILLIS = 10;
    /** At {@link #PENDING_TICK_MILLIS} a tick while a request is pending, 40 seconds: longer than the fetch timeout. */
    private static final int PORTAL_TICKS = 4000;

    /**
     * The Portal flow through the method handlers: validate, import and activate the fixture, fetched from a Portal
     * stand-in on a local HTTP server, then read it back with status, list and export. The validate call is sent twice
     * with one request id, and the bundle is fetched once.
     */
    @GameTest(environment = PORTAL_ENVIRONMENT, maxTicks = PORTAL_TICKS)
    public void aPortalBundleValidatesImportsAndActivatesThroughTheMethods(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentServiceImpl content = content();
        FakePortal portal = FakePortal.start();
        ContentMethods methods = ContentMethods.create(content, portal.config(), server, FETCH_TIMEOUT);
        byte[] bytes = fixture("content/portal_bundle.json");
        String expectedDigest = content.validate(new String(bytes, StandardCharsets.UTF_8)).result().orElseThrow()
                .digest();
        UUID validate = UUID.randomUUID();
        UUID imported = UUID.randomUUID();
        UUID activate = UUID.randomUUID();
        AtomicReference<JsonObject> result = new AtomicReference<>();
        AtomicReference<Long> revision = new AtomicReference<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(content.isStartupComplete(),
                        "the startup content load has not finished"))
                .thenExecute(() -> answered(helper, methods.validate(portal.call(validate, bytes, null)), "accepted"))
                .thenWaitUntil(() -> finished(helper, methods, validate, result))
                .thenExecute(() -> {
                    JsonObject done = done(helper, result.get(), "validate");
                    helper.assertTrue(done.get("valid").getAsBoolean(), "the fixture is not valid: " + done);
                    helper.assertValueEqual(done.get("digest").getAsString(), expectedDigest, "validated digest");
                    helper.assertValueEqual(done.get("problem_count").getAsInt(), 0, "problem count");
                    helper.assertTrue(!done.has("revision"), "validate stored a revision: " + done);
                    answered(helper, methods.validate(portal.call(validate, bytes, null)), "accepted");
                    helper.assertValueEqual(portal.fetches(validate), 1, "fetches for one request id sent twice");
                    answered(helper, methods.importBundle(portal.call(imported, bytes, "portal:1 Sable")), "accepted");
                })
                .thenWaitUntil(() -> finished(helper, methods, imported, result))
                .thenExecute(() -> {
                    JsonObject done = done(helper, result.get(), "import");
                    helper.assertTrue(done.get("valid").getAsBoolean(), "the import was refused: " + done);
                    JsonObject stored = done.getAsJsonObject("revision");
                    helper.assertValueEqual(stored.get("digest").getAsString(), expectedDigest, "stored digest");
                    helper.assertValueEqual(stored.get("author").getAsString(),
                            "portal:1 Sable (request " + imported + ")", "recorded author");
                    helper.assertTrue(!stored.get("active").getAsBoolean(), "the import activated the revision");
                    revision.set(stored.get("id").getAsLong());
                    JsonObject param = new JsonObject();
                    param.addProperty("request", activate.toString());
                    param.addProperty("revision", revision.get());
                    param.addProperty("actor", "portal:2 Mark");
                    answered(helper, methods.activate(param), "accepted");
                })
                .thenWaitUntil(() -> finished(helper, methods, activate, result))
                .thenExecute(() -> {
                    JsonObject done = done(helper, result.get(), "activate");
                    JsonObject active = done.getAsJsonObject("revision");
                    helper.assertValueEqual(active.get("id").getAsLong(), revision.get(), "activated revision");
                    helper.assertTrue(active.get("active").getAsBoolean(), "the summary is not active: " + active);
                    helper.assertValueEqual(active.get("activated_by").getAsString(), "portal:2 Mark", "activated by");
                    helper.assertValueEqual(content.activeRevisionId(), OptionalLong.of(revision.get()),
                            "active revision id");
                    helper.assertTrue(content.active().npcs().containsKey(PORTAL_NPC),
                            "the activated content has no " + PORTAL_NPC);

                    JsonObject status = methods.status(null);
                    helper.assertValueEqual(status.getAsJsonObject("revision").get("id").getAsLong(), revision.get(),
                            "status revision");
                    helper.assertValueEqual(status.get("schema").getAsInt(), ContentBundle.SCHEMA, "status schema");
                    helper.assertTrue(status.getAsJsonObject("remote").get("enabled").getAsBoolean(),
                            "status says remote content is off");

                    JsonObject list = methods.list(null);
                    helper.assertTrue(list.getAsJsonArray("revisions").asList().stream()
                                    .map(JsonElement::getAsJsonObject)
                                    .anyMatch(entry -> entry.get("id").getAsLong() == revision.get()
                                            && entry.get("active").getAsBoolean()
                                            && entry.get("activated_by").getAsString().equals("portal:2 Mark")),
                            "list does not show the activated revision: " + list);

                    StringBuilder text = new StringBuilder();
                    JsonObject chunk;
                    long offset = 0;
                    do {
                        JsonObject param = new JsonObject();
                        param.addProperty("revision", revision.get());
                        param.addProperty("offset", offset);
                        chunk = methods.export(param);
                        helper.assertValueEqual(chunk.get("status").getAsString(), "ok", "export status " + chunk);
                        text.append(chunk.get("text").getAsString());
                        offset = chunk.has("next") ? chunk.get("next").getAsLong() : -1;
                    } while (offset >= 0);
                    helper.assertValueEqual(sha256(text.toString().getBytes(StandardCharsets.UTF_8)), expectedDigest,
                            "SHA-256 of the exported text");
                })
                .thenExecute(() -> {
                    methods.close();
                    portal.close();
                })
                .thenSucceed();
    }

    /**
     * A refused bundle through the method handlers: validate and import both finish with every problem and store
     * nothing, a wrong token fails the fetch, and activating a revision that does not exist fails.
     */
    @GameTest(maxTicks = PORTAL_TICKS)
    public void aRefusedPortalBundleListsItsProblemsAndStoresNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ContentServiceImpl content = content();
        FakePortal portal = FakePortal.start();
        ContentMethods methods = ContentMethods.create(content, portal.config(), server, FETCH_TIMEOUT);
        byte[] bytes = fixture("content/portal_refused.json");
        UUID validate = UUID.randomUUID();
        UUID imported = UUID.randomUUID();
        UUID wrongToken = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        AtomicReference<JsonObject> result = new AtomicReference<>();
        helper.startSequence()
                .thenExecute(() -> answered(helper, methods.validate(portal.call(validate, bytes, null)), "accepted"))
                .thenWaitUntil(() -> finished(helper, methods, validate, result))
                .thenExecute(() -> {
                    JsonObject done = done(helper, result.get(), "validate");
                    helper.assertTrue(!done.get("valid").getAsBoolean(), "the refused fixture validated: " + done);
                    List<String> problems = done.getAsJsonArray("problems").asList().stream()
                            .map(JsonElement::getAsString).toList();
                    helper.assertValueEqual(done.get("problem_count").getAsInt(), problems.size(), "problem count");
                    for (String expected : List.of("haruhi:fixture/nobody", "undeclared", "orphan")) {
                        helper.assertTrue(problems.stream().anyMatch(line -> line.contains(expected)),
                                "no problem names " + expected + ": " + problems);
                    }
                    helper.assertValueEqual(problems.size(), 3, "number of problems in " + problems);
                    helper.assertTrue(problems.stream().allMatch(line -> line.startsWith("haruhi:fixture/portal_")),
                            "a problem is not placed in its graph: " + problems);
                    answered(helper, methods.importBundle(portal.call(imported, bytes, "portal:1 Sable")), "accepted");
                })
                .thenWaitUntil(() -> finished(helper, methods, imported, result))
                .thenExecute(() -> {
                    JsonObject done = done(helper, result.get(), "import");
                    helper.assertTrue(!done.get("valid").getAsBoolean(), "the refused fixture imported: " + done);
                    helper.assertTrue(!done.has("revision"), "a refused import stored a revision: " + done);
                    JsonObject list = methods.list(null);
                    helper.assertTrue(list.getAsJsonArray("revisions").asList().stream()
                                    .noneMatch(entry -> entry.getAsJsonObject().get("author").getAsString()
                                            .contains(imported.toString())),
                            "a revision carries the refused import's request id: " + list);

                    JsonObject call = portal.call(wrongToken, bytes, null);
                    call.addProperty("token", "not-the-token");
                    answered(helper, methods.validate(call), "accepted");
                    JsonObject param = new JsonObject();
                    param.addProperty("request", missing.toString());
                    param.addProperty("revision", Long.MAX_VALUE);
                    param.addProperty("actor", "portal:2 Mark");
                    answered(helper, methods.activate(param), "accepted");
                })
                .thenWaitUntil(() -> finished(helper, methods, wrongToken, result))
                .thenExecute(() -> {
                    helper.assertValueEqual(result.get().get("status").getAsString(), "failed", "wrong token status");
                    helper.assertValueEqual(result.get().get("reason").getAsString(), "http_status",
                            "wrong token reason");
                })
                .thenWaitUntil(() -> finished(helper, methods, missing, result))
                .thenExecute(() -> {
                    helper.assertValueEqual(result.get().get("status").getAsString(), "failed", "missing status");
                    helper.assertValueEqual(result.get().get("reason").getAsString(), "no_such_revision",
                            "missing revision reason");
                    methods.close();
                    portal.close();
                })
                .thenSucceed();
    }

    /**
     * Every method is registered for the management server, vanilla's {@code rpc.discover} still encodes with their
     * schemas, the log mixin applies to vanilla's connection class, and calls are checked before any fetch.
     */
    @GameTest(maxTicks = 20)
    public void theMethodsAreRegisteredAndCheckTheirParams(GameTestHelper helper) {
        for (Identifier id : ContentRpc.ALL) {
            helper.assertTrue(BuiltInRegistries.INCOMING_RPC_METHOD.containsKey(id), id + " is not registered");
        }
        DataResult<JsonElement> discovered = DiscoveryService.DiscoverResponse.CODEC.codec()
                .encodeStart(JsonOps.INSTANCE, DiscoveryService.discover(Schema.getSchemaRegistry()));
        helper.assertTrue(discovered.error().isEmpty(), "rpc.discover does not encode: "
                + discovered.error().map(error -> error.message()).orElse(""));
        helper.assertTrue(discovered.result().orElseThrow().toString().contains("haruhi:content/validate"),
                "rpc.discover does not list haruhi:content/validate");
        Class<?> connection;
        try {
            // Loading the class applies JsonRpcParamsLogMixin, which fails the load if its injection point is gone.
            connection = Class.forName("net.minecraft.server.jsonrpc.Connection");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        helper.assertTrue(Arrays.stream(connection.getDeclaredMethods())
                        .anyMatch(method -> method.getName().contains("redactParams")),
                "JsonRpcParamsLogMixin is not applied to vanilla's management connection");
        ContentMethods off = ContentMethods.create(content(), RemoteContentConfig.DISABLED,
                helper.getLevel().getServer());
        JsonObject call = new JsonObject();
        call.addProperty("request", UUID.randomUUID().toString());
        call.addProperty("sha256", "0".repeat(64));
        call.addProperty("size", 10);
        call.addProperty("token", PORTAL_TOKEN);
        answered(helper, off.validate(call), "remote_disabled");

        JsonObject unknown = new JsonObject();
        unknown.addProperty("request", UUID.randomUUID().toString());
        answered(helper, off.request(unknown), "unknown");
        JsonObject bad = off.request(new JsonPrimitive(PORTAL_TOKEN));
        answered(helper, bad, "invalid");
        helper.assertTrue(!bad.toString().contains(PORTAL_TOKEN), "an invalid answer quotes the param: " + bad);
        JsonObject noSize = call.deepCopy();
        noSize.remove("size");
        JsonObject missingSize = off.importBundle(noSize);
        answered(helper, missingSize, "invalid");
        helper.assertValueEqual(missingSize.get("field").getAsString(), "size", "the field named");
        off.close();
        helper.succeed();
    }

    private static void answered(GameTestHelper helper, JsonObject answer, String status) {
        helper.assertValueEqual(answer.get("status").getAsString(), status, "answer " + answer);
    }

    /**
     * Asserts that {@code request} is no longer pending, keeping its answer in {@code result}. The GameTest server does
     * not wait between ticks, so a whole tick budget can pass in less time than one local HTTP fetch takes; a pending
     * request sleeps the tick for {@link #PENDING_TICK_MILLIS}, so {@link #PORTAL_TICKS} ticks outlast
     * {@link #FETCH_TIMEOUT} and a fetch that hangs fails the request rather than the tick budget.
     */
    private static void finished(
            GameTestHelper helper, ContentMethods methods, UUID request, AtomicReference<JsonObject> result) {
        JsonObject param = new JsonObject();
        param.addProperty("request", request.toString());
        JsonObject answer = methods.request(param);
        if (answer.get("status").getAsString().equals("pending")) {
            try {
                Thread.sleep(PENDING_TICK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            helper.fail("request " + request + " is still pending");
        }
        result.set(answer);
    }

    private static JsonObject done(GameTestHelper helper, JsonObject answer, String kind) {
        helper.assertValueEqual(answer.get("status").getAsString(), "done", kind + " answer " + answer);
        helper.assertValueEqual(answer.get("kind").getAsString(), kind, "request kind");
        return answer;
    }

    private static byte[] fixture(String name) {
        try (InputStream in = ContentGameTests.class.getResourceAsStream("/haruhi/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("The GameTest jar has no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A stand-in for the Portal's bundle route: serves the bytes registered for a request id to the bearer token, 401
     * to any other token and 404 for an unknown id. Its threads are daemons, so a failed test that never closes it does
     * not keep the GameTest server alive.
     */
    private static final class FakePortal implements AutoCloseable {
        private static final String PREFIX = "/api/v1/haruhi/requests/";
        private final HttpServer server;
        private final Map<UUID, byte[]> bundles = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> fetches = new ConcurrentHashMap<>();

        private FakePortal(HttpServer server) {
            this.server = server;
            server.createContext("/", exchange -> {
                try {
                    handle(exchange);
                } catch (RuntimeException e) {
                    Haruhi.LOGGER.error("The GameTest Portal could not answer {}", exchange.getRequestURI(), e);
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                }
            });
        }

        static FakePortal start() {
            try {
                FakePortal portal = new FakePortal(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
                // The server's dispatcher thread takes the daemon flag of the thread that starts it.
                Thread starter = new Thread(portal.server::start, "haruhi-gametest-portal");
                starter.setDaemon(true);
                starter.start();
                starter.join();
                return portal;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        RemoteContentConfig config() {
            return new RemoteContentConfig(true, Optional.of("http://127.0.0.1:" + server.getAddress().getPort()),
                    RemoteContentConfig.DEFAULT_MAX_BYTES);
        }

        /** Registers {@code bytes} for {@code request} and returns a validate or import call for them. */
        JsonObject call(UUID request, byte[] bytes, String author) {
            bundles.put(request, bytes);
            JsonObject call = new JsonObject();
            call.addProperty("request", request.toString());
            call.addProperty("sha256", sha256(bytes));
            call.addProperty("size", bytes.length);
            call.addProperty("token", PORTAL_TOKEN);
            if (author != null) {
                call.addProperty("author", author);
            }
            return call;
        }

        int fetches(UUID request) {
            return fetches.getOrDefault(request, 0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getRawPath();
            byte[] body = null;
            int status = 404;
            if (path.startsWith(PREFIX) && path.endsWith("/bundle")) {
                UUID request = UUID.fromString(path.substring(PREFIX.length(), path.length() - "/bundle".length()));
                fetches.merge(request, 1, Integer::sum);
                if (!("Bearer " + PORTAL_TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    status = 401;
                } else if (bundles.containsKey(request)) {
                    status = 200;
                    body = bundles.get(request);
                }
            }
            exchange.sendResponseHeaders(status, body == null ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (body != null) {
                    out.write(body);
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
