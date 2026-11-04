package town.lampas.haruhi.npc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The permission gate on the {@code /haruhi npc} command tree: spawn, set, remove and list are for gamemasters. */
class NpcCommandsTest {
    /** One command per subcommand. Each entity selector takes at most one entity. */
    private static final String[] SUBCOMMANDS = {
            "haruhi npc spawn haruhi:fixture/greeter",
            "haruhi npc set @e[limit=1] haruhi:fixture/greeter",
            "haruhi npc remove @e[limit=1]",
            "haruhi npc list"
    };

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theNpcTreeIsOnlyForGamemasters() {
        CommandNode<CommandSourceStack> npc = dispatcher().getRoot().getChild("haruhi").getChild("npc");
        CommandSourceStack gamemaster = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
        CommandSourceStack moderator = Commands.createCompilationContext(LevelBasedPermissionSet.MODERATOR);
        CommandSourceStack player = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS);

        assertTrue(npc.canUse(gamemaster), "a gamemaster may use /haruhi npc");
        assertFalse(npc.canUse(moderator), "a moderator below gamemaster may not use /haruhi npc");
        assertFalse(npc.canUse(player), "a player may not use /haruhi npc");
    }

    @Test
    void aPlayerIsRefusedEveryNpcSubcommand() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack player = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS);

        for (String command : SUBCOMMANDS) {
            assertFalse(runs(dispatcher, command, player), command);
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, player), command);
        }
    }

    @Test
    void aGamemasterCanRunEveryNpcSubcommand() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack gamemaster = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);

        for (String command : SUBCOMMANDS) {
            assertTrue(runs(dispatcher, command, gamemaster), command);
        }
    }

    /**
     * Whether {@code source} can run {@code command}: the command parses to the end, and the last node it reaches
     * has a command.
     */
    private static boolean runs(
            CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source);
        return parsed.getExceptions().isEmpty()
                && !parsed.getReader().canRead()
                && parsed.getContext().getLastChild().getCommand() != null;
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        // NpcCommands.register uses neither the build context nor the selection.
        NpcCommands.register(dispatcher, null, Commands.CommandSelection.DEDICATED);
        return dispatcher;
    }
}
