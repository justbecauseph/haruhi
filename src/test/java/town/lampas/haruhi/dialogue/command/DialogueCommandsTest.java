package town.lampas.haruhi.dialogue.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.flag.FeatureFlags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The permission gate on the {@code /haruhi dialogue} and {@code /haruhi flags} trees: gamemasters only. */
class DialogueCommandsTest {
    /**
     * One command for each gated subcommand: open, close, and each flags subcommand. The flag namespace is quoted,
     * because an unquoted string argument stops at the colon.
     */
    private static final List<String> GATED_COMMANDS = List.of(
            "haruhi dialogue open haruhi:example/greeter",
            "haruhi dialogue open haruhi:example/greeter @a",
            "haruhi dialogue close",
            "haruhi dialogue close @a",
            "haruhi flags get Alice \"haruhi:example/greeter\" met",
            "haruhi flags set Alice \"haruhi:example/greeter\" met 1",
            "haruhi flags clear Alice \"haruhi:example/greeter\" met");

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theOpenCloseAndFlagsNodesAreOnlyForGamemasters() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack gamemaster = source(LevelBasedPermissionSet.GAMEMASTER);
        CommandSourceStack moderator = source(LevelBasedPermissionSet.MODERATOR);
        CommandSourceStack player = source(PermissionSet.NO_PERMISSIONS);
        CommandNode<CommandSourceStack> dialogue = dispatcher.getRoot().getChild("haruhi").getChild("dialogue");
        CommandNode<CommandSourceStack> flags = dispatcher.getRoot().getChild("haruhi").getChild("flags");

        List<CommandNode<CommandSourceStack>> gated =
                List.of(dialogue.getChild("open"), dialogue.getChild("close"), flags);
        for (CommandNode<CommandSourceStack> node : gated) {
            assertTrue(node.canUse(gamemaster), "a gamemaster may use " + node.getName());
            assertFalse(node.canUse(moderator), "a moderator below gamemaster may not use " + node.getName());
            assertFalse(node.canUse(player), "a player may not use " + node.getName());
        }
    }

    @Test
    void aPlayerOrModeratorIsRefusedEveryGatedDialogueAndFlagsCommand() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        for (CommandSourceStack source : List.of(
                source(PermissionSet.NO_PERMISSIONS), source(LevelBasedPermissionSet.MODERATOR))) {
            for (String command : GATED_COMMANDS) {
                assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, source), command);
            }
        }
    }

    @Test
    void aGamemasterCanParseEveryGatedDialogueAndFlagsCommandToItsEnd() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack gamemaster = source(LevelBasedPermissionSet.GAMEMASTER);

        for (String command : GATED_COMMANDS) {
            ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, gamemaster);
            assertTrue(parsed.getExceptions().isEmpty(), command);
            assertFalse(parsed.getReader().canRead(), command + " is parsed to its end");
        }
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        CommandBuildContext buildContext =
                CommandBuildContext.simple(HolderLookup.Provider.create(Stream.empty()), FeatureFlags.VANILLA_SET);
        DialogueCommands.registerCommands(dispatcher, buildContext, Commands.CommandSelection.DEDICATED);
        return dispatcher;
    }

    private static CommandSourceStack source(PermissionSet permissions) {
        return Commands.createCompilationContext(permissions);
    }
}
