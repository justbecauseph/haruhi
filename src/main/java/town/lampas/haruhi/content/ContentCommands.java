package town.lampas.haruhi.content;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.DataResult;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.storage.RevisionSummary;
import town.lampas.haruhi.storage.StoredRevision;

/**
 * The {@code /haruhi content} commands, for game masters: import, validate, list, activate, rollback and status.
 * Feedback is plain text. The author of an import or an activation is the command source's name.
 *
 * <p>Every command but {@code status} replies once its file read or storage call has finished, after the command has
 * returned. A source may have stopped listening by then: RCON collects only what is sent while the command runs. So
 * each of those replies also goes to the server log, through {@link Replies}. The replies of import, activate and
 * rollback, which change what is stored or active, are also shown to other operators, as vanilla commands do.
 */
final class ContentCommands {
    /** How many revisions {@code list} shows, newest first. */
    static final int LIST_LIMIT = 20;

    private static final Predicate<CommandSourceStack> GAMEMASTERS = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private ContentCommands() {}

    static void register(ContentServiceImpl service) {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                dispatcher.register(root(service)));
    }

    static LiteralArgumentBuilder<CommandSourceStack> root(ContentServiceImpl service) {
        return Commands.literal("haruhi").then(Commands.literal("content").requires(GAMEMASTERS)
                .then(Commands.literal("import")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> importFile(Replies.to(ctx), service,
                                        StringArgumentType.getString(ctx, "file")))))
                .then(Commands.literal("validate")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> validateFile(Replies.to(ctx), service,
                                        StringArgumentType.getString(ctx, "file")))))
                .then(Commands.literal("list").executes(ctx -> list(Replies.to(ctx), service)))
                .then(Commands.literal("activate")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> activate(Replies.to(ctx), service,
                                        LongArgumentType.getLong(ctx, "id")))))
                .then(Commands.literal("rollback").executes(ctx -> rollback(Replies.to(ctx), service)))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource(), service))));
    }

    private static int importFile(Replies replies, ContentServiceImpl service, String name) {
        String author = replies.source().getTextName();
        MinecraftServer server = replies.source().getServer();
        ServerThread.hop(server, BundleFiles.read(configDir(), name)).whenComplete((text, readError) -> {
            if (readError != null) {
                replies.fail(readError);
                return;
            }
            DataResult<ValidatedBundle> validated = service.validate(text);
            if (validated.error().isPresent()) {
                replies.fail(validated.error().get().message());
                return;
            }
            service.store(validated.result().orElseThrow(), author).whenComplete((stored, storeError) -> {
                if (storeError != null) {
                    replies.fail(storeError);
                } else {
                    replies.success(storedReply(stored), true);
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int validateFile(Replies replies, ContentServiceImpl service, String name) {
        ServerThread.hop(replies.source().getServer(), BundleFiles.read(configDir(), name))
                .whenComplete((text, readError) -> {
                    if (readError != null) {
                        replies.fail(readError);
                        return;
                    }
                    DataResult<ValidatedBundle> validated = service.validate(text);
                    if (validated.error().isPresent()) {
                        replies.fail(validated.error().get().message());
                        return;
                    }
                    ContentBundle bundle = validated.result().orElseThrow().bundle();
                    replies.success("Valid: " + bundle.npcs().size() + " NPCs, " + bundle.dialogues().size()
                            + " graphs, digest " + validated.result().orElseThrow().digest(), false);
                });
        return Command.SINGLE_SUCCESS;
    }

    private static int list(Replies replies, ContentServiceImpl service) {
        ServerThread.hop(replies.source().getServer(), service.storage().listRevisions(LIST_LIMIT))
                .whenComplete((revisions, error) -> {
                    if (error != null) {
                        replies.fail(error);
                    } else if (revisions.isEmpty()) {
                        replies.success("No content revisions are stored.", false);
                    } else {
                        for (RevisionSummary revision : revisions) {
                            replies.success("Revision " + revision.id() + " " + revision.createdAt() + " by "
                                    + revision.author() + " digest " + shortDigest(revision.digest())
                                    + (revision.active() ? " (active)" : ""), false);
                        }
                    }
                });
        return Command.SINGLE_SUCCESS;
    }

    private static int activate(Replies replies, ContentServiceImpl service, long id) {
        service.activateRevision(id, replies.source().getTextName()).whenComplete((revision, error) -> {
            if (error != null) {
                replies.fail(error);
            } else {
                replies.success("Activated revision " + revision.id() + ".", true);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int rollback(Replies replies, ContentServiceImpl service) {
        service.rollback(replies.source().getTextName()).whenComplete((revision, error) -> {
            if (error != null) {
                replies.fail(error);
            } else {
                replies.success("Rolled back to revision " + revision.id() + ".", true);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /** Answers while the command runs, so every source sees it, RCON included. Not logged. */
    private static int status(CommandSourceStack source, ContentServiceImpl service) {
        ContentBundle bundle = service.active();
        OptionalLong id = service.activeRevisionId();
        Optional<String> digest = service.activeDigest();
        reply(source, "Active revision: " + (id.isPresent() ? id.getAsLong() : "none"));
        reply(source, "Digest: " + digest.orElse("none"));
        reply(source, "NPCs: " + bundle.npcs().size() + ", graphs: " + bundle.dialogues().size());
        return Command.SINGLE_SUCCESS;
    }

    /**
     * The reply to an import. Storing the digest of an existing revision returns that revision, so an active one is
     * said to be active rather than inactive.
     */
    static String storedReply(StoredRevision stored) {
        String digest = "(digest " + shortDigest(stored.digest()) + ")";
        if (stored.active()) {
            return "Revision " + stored.id() + " " + digest + " already has this content and is active.";
        }
        return "Stored revision " + stored.id() + " " + digest + ". It is not active. Run /haruhi content activate "
                + stored.id() + " to use it.";
    }

    /**
     * The line the server log gets for one reply line: who ran which command, then the reply, for example
     * {@code Rcon ran /haruhi content validate town.json: Valid: 3 NPCs, 4 graphs, digest ...}.
     */
    private static String logLine(String sourceName, String command, String line) {
        return sourceName + " ran " + command + ": " + line;
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static String shortDigest(String digest) {
        return digest.substring(0, Math.min(12, digest.length()));
    }

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    /**
     * The replies of one command that answers after it has returned. Each line goes to the source and to the server
     * log, at INFO for a success and WARN for a failure, prefixed by {@link #logLine}.
     *
     * @param command the command as typed, with a leading {@code /}
     */
    private record Replies(CommandSourceStack source, String command) {
        static Replies to(CommandContext<CommandSourceStack> ctx) {
            String input = ctx.getInput();
            return new Replies(ctx.getSource(), input.startsWith("/") ? input : "/" + input);
        }

        /** Sends {@code text}; with {@code broadcast}, other operators see it as well, as vanilla allows. */
        void success(String text, boolean broadcast) {
            Haruhi.LOGGER.info(logLine(source.getTextName(), command, text));
            source.sendSuccess(() -> Component.literal(text), broadcast);
        }

        /** Sends each line of the error's message as its own failure. Unwraps the cause of a failed future first. */
        void fail(Throwable error) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause()
                    : error;
            fail(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }

        /** Sends each line of {@code message} as its own failure. */
        void fail(String message) {
            for (String line : message.lines().toList()) {
                Haruhi.LOGGER.warn(logLine(source.getTextName(), command, line));
                source.sendFailure(Component.literal(line));
            }
        }
    }
}
