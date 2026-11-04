package town.lampas.haruhi.dialogue.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.FlagNamespace;
import town.lampas.haruhi.dialogue.DialogueModule;
import town.lampas.haruhi.dialogue.DialogueSessions;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.storage.FlagKey;

/**
 * The {@code /haruhi dialogue} and {@code /haruhi flags} commands, for gamemasters. Feedback is {@link Component#literal}
 * text. Flag commands act only on a player whose flags have loaded, and only on flags the active content declares.
 */
public final class DialogueCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTERS =
            Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private DialogueCommands() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register(DialogueCommands::registerCommands);
    }

    static void registerCommands(
            CommandDispatcher<CommandSourceStack> dispatcher,
            CommandBuildContext buildContext,
            Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("haruhi")
                .then(Commands.literal("dialogue")
                        .then(Commands.literal("open")
                                .requires(GAMEMASTERS)
                                .then(Commands.argument("graph", IdentifierArgument.id())
                                        .executes(context -> open(context, self(context)))
                                        .then(Commands.argument("targets", EntityArgument.players())
                                                .executes(context -> open(context,
                                                        EntityArgument.getPlayers(context, "targets"))))))
                        .then(Commands.literal("close")
                                .requires(GAMEMASTERS)
                                .executes(context -> close(context, self(context)))
                                .then(Commands.argument("targets", EntityArgument.players())
                                        .executes(context -> close(context,
                                                EntityArgument.getPlayers(context, "targets"))))))
                .then(Commands.literal("flags")
                        .requires(GAMEMASTERS)
                        .then(Commands.literal("get")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("namespace", StringArgumentType.string())
                                                .then(Commands.argument("flag", StringArgumentType.string())
                                                        .executes(DialogueCommands::getFlag)))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("namespace", StringArgumentType.string())
                                                .then(Commands.argument("flag", StringArgumentType.string())
                                                        .executes(context -> setFlag(context, 1))
                                                        .then(Commands.argument("value", IntegerArgumentType.integer())
                                                                .executes(context -> setFlag(context,
                                                                        IntegerArgumentType.getInteger(context,
                                                                                "value"))))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("namespace", StringArgumentType.string())
                                                .then(Commands.argument("flag", StringArgumentType.string())
                                                        .executes(DialogueCommands::clearFlag))))))
        );
    }

    private static List<ServerPlayer> self(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return List.of(context.getSource().getPlayerOrException());
    }

    private static int open(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> targets)
            throws CommandSyntaxException {
        Identifier graph = IdentifierArgument.getId(context, "graph");
        DialogueSessions sessions = DialogueModule.sessions();
        int opened = 0;
        for (ServerPlayer player : targets) {
            if (sessions.open(player, graph, null)) {
                opened++;
            }
        }
        if (opened == 0) {
            context.getSource().sendFailure(Component.literal("Dialogue " + graph + " is not in the active content"));
            return 0;
        }
        int count = opened;
        context.getSource().sendSuccess(() -> Component.literal("Opened " + graph + " for " + count + " player(s)"), true);
        return count;
    }

    private static int close(CommandContext<CommandSourceStack> context, Collection<ServerPlayer> targets) {
        DialogueSessions sessions = DialogueModule.sessions();
        int closed = 0;
        for (ServerPlayer player : targets) {
            if (sessions.isInDialogue(player)) {
                sessions.close(player);
                closed++;
            }
        }
        int count = closed;
        context.getSource().sendSuccess(() -> Component.literal("Closed " + count + " conversation(s)"), true);
        return count;
    }

    private static int getFlag(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Optional<FlagKey> key = declaredFlag(context);
        if (key.isEmpty()) {
            return undeclared(context);
        }
        Optional<DialogueFlags> flags = DialogueModule.sessions().loadedFlags(player);
        if (flags.isEmpty()) {
            return notLoaded(context, player);
        }
        int value = flags.get().get(key.get());
        context.getSource().sendSuccess(() -> Component.literal(player.getName().getString() + " "
                + key.get().namespace() + " " + key.get().flag() + " = " + value), false);
        return value;
    }

    private static int setFlag(CommandContext<CommandSourceStack> context, int value) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Optional<FlagKey> key = declaredFlag(context);
        if (key.isEmpty()) {
            return undeclared(context);
        }
        Optional<DialogueFlags> flags = DialogueModule.sessions().loadedFlags(player);
        if (flags.isEmpty()) {
            return notLoaded(context, player);
        }
        flags.get().set(key.get(), value);
        context.getSource().sendSuccess(() -> Component.literal("Set " + key.get().flag() + " to " + value
                + " for " + player.getName().getString()), true);
        return value;
    }

    private static int clearFlag(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(context, "player");
        Optional<FlagKey> key = declaredFlag(context);
        if (key.isEmpty()) {
            return undeclared(context);
        }
        Optional<DialogueFlags> flags = DialogueModule.sessions().loadedFlags(player);
        if (flags.isEmpty()) {
            return notLoaded(context, player);
        }
        flags.get().clear(key.get());
        context.getSource().sendSuccess(() -> Component.literal("Cleared " + key.get().flag()
                + " for " + player.getName().getString()), true);
        return 1;
    }

    private static Optional<FlagKey> declaredFlag(CommandContext<CommandSourceStack> context) {
        FlagKey key = new FlagKey(StringArgumentType.getString(context, "namespace"),
                StringArgumentType.getString(context, "flag"));
        FlagNamespace namespace = HaruhiServices.content().active().flagNamespaces().get(key.namespace());
        boolean declared = namespace != null && namespace.flags().contains(key.flag());
        return declared ? Optional.of(key) : Optional.empty();
    }

    private static int undeclared(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.literal("That flag is not declared in the active content"));
        return 0;
    }

    private static int notLoaded(CommandContext<CommandSourceStack> context, ServerPlayer player) {
        context.getSource().sendFailure(Component.literal(
                player.getName().getString() + "'s dialogue data is still loading; try again"));
        return 0;
    }
}
