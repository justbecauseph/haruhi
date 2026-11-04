package town.lampas.haruhi.content;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.serialization.DataResult;
import java.nio.file.Path;
import java.util.List;
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
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.storage.RevisionSummary;

/**
 * The {@code /haruhi content} commands, for game masters: import, validate, list, activate, rollback and status.
 * Feedback is plain text. The author of an import or an activation is the command source's name.
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
                                .executes(ctx -> importFile(ctx.getSource(), service,
                                        StringArgumentType.getString(ctx, "file")))))
                .then(Commands.literal("validate")
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> validateFile(ctx.getSource(), service,
                                        StringArgumentType.getString(ctx, "file")))))
                .then(Commands.literal("list").executes(ctx -> list(ctx.getSource(), service)))
                .then(Commands.literal("activate")
                        .then(Commands.argument("id", LongArgumentType.longArg(1))
                                .executes(ctx -> activate(ctx.getSource(), service,
                                        LongArgumentType.getLong(ctx, "id")))))
                .then(Commands.literal("rollback").executes(ctx -> rollback(ctx.getSource(), service)))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource(), service))));
    }

    private static int importFile(CommandSourceStack source, ContentServiceImpl service, String name) {
        String author = source.getTextName();
        MinecraftServer server = source.getServer();
        ServerThread.hop(server, BundleFiles.read(configDir(), name)).whenComplete((text, readError) -> {
            if (readError != null) {
                fail(source, readError);
                return;
            }
            DataResult<ValidatedBundle> validated = service.validate(text);
            if (validated.error().isPresent()) {
                fail(source, validated.error().get().message());
                return;
            }
            service.store(validated.result().orElseThrow(), author).whenComplete((stored, storeError) -> {
                if (storeError != null) {
                    fail(source, storeError);
                } else {
                    reply(source, "Stored revision " + stored.id() + " (digest " + shortDigest(stored.digest())
                            + "). It is not active. Run /haruhi content activate " + stored.id() + " to use it.");
                }
            });
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int validateFile(CommandSourceStack source, ContentServiceImpl service, String name) {
        ServerThread.hop(source.getServer(), BundleFiles.read(configDir(), name)).whenComplete((text, readError) -> {
            if (readError != null) {
                fail(source, readError);
                return;
            }
            DataResult<ValidatedBundle> validated = service.validate(text);
            if (validated.error().isPresent()) {
                fail(source, validated.error().get().message());
                return;
            }
            ContentBundle bundle = validated.result().orElseThrow().bundle();
            reply(source, "Valid: " + bundle.npcs().size() + " NPCs, " + bundle.dialogues().size()
                    + " graphs, digest " + validated.result().orElseThrow().digest());
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandSourceStack source, ContentServiceImpl service) {
        ServerThread.hop(source.getServer(), HaruhiServices.storage().listRevisions(LIST_LIMIT))
                .whenComplete((revisions, error) -> {
                    if (error != null) {
                        fail(source, error);
                    } else if (revisions.isEmpty()) {
                        reply(source, "No content revisions are stored.");
                    } else {
                        for (RevisionSummary revision : revisions) {
                            reply(source, "Revision " + revision.id() + " " + revision.createdAt() + " by "
                                    + revision.author() + " digest " + shortDigest(revision.digest())
                                    + (revision.active() ? " (active)" : ""));
                        }
                    }
                });
        return Command.SINGLE_SUCCESS;
    }

    private static int activate(CommandSourceStack source, ContentServiceImpl service, long id) {
        service.activateRevision(id, source.getTextName()).whenComplete((revision, error) -> {
            if (error != null) {
                fail(source, error);
            } else {
                reply(source, "Activated revision " + revision.id() + ".");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int rollback(CommandSourceStack source, ContentServiceImpl service) {
        service.rollback(source.getTextName()).whenComplete((revision, error) -> {
            if (error != null) {
                fail(source, error);
            } else {
                reply(source, "Rolled back to revision " + revision.id() + ".");
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int status(CommandSourceStack source, ContentServiceImpl service) {
        ContentBundle bundle = service.active();
        OptionalLong id = service.activeRevisionId();
        Optional<String> digest = service.activeDigest();
        reply(source, "Active revision: " + (id.isPresent() ? id.getAsLong() : "none"));
        reply(source, "Digest: " + digest.orElse("none"));
        reply(source, "NPCs: " + bundle.npcs().size() + ", graphs: " + bundle.dialogues().size());
        return Command.SINGLE_SUCCESS;
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

    /** Sends each line of {@code message} as its own failure. Unwraps the cause of a failed future first. */
    private static void fail(CommandSourceStack source, Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        fail(source, cause.getMessage() == null ? cause.toString() : cause.getMessage());
    }

    private static void fail(CommandSourceStack source, String message) {
        List<String> lines = message.lines().toList();
        for (String line : lines) {
            source.sendFailure(Component.literal(line));
        }
    }
}
