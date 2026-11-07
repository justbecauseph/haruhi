package town.lampas.haruhi.binding;

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

/** The shape and permission gate of {@code /haruhi bind}, {@code /haruhi unbind} and {@code /haruhi bindings}. */
class BindingCommandsTest {
    private static final String ENTITY = "00000000-0000-0000-0000-000000000001";
    /** Targets are UUIDs, not selectors: a selector needs a permission of its own. */
    private static final String[] COMMANDS = {
            "haruhi bind block 1 64 2 haruhi:test/lectern",
            "haruhi bind block 1 64 2 haruhi:test/lectern haruhi:test/clerk",
            "haruhi bind entity " + ENTITY + " haruhi:test/lectern",
            "haruhi bind entity " + ENTITY + " haruhi:test/lectern haruhi:test/clerk",
            "haruhi unbind block 1 64 2",
            "haruhi unbind entity " + ENTITY,
            "haruhi bindings",
            "haruhi bindings all"
    };

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void theBindingTreesAreOnlyForGamemasters() {
        CommandNode<CommandSourceStack> haruhi = dispatcher().getRoot().getChild("haruhi");
        CommandSourceStack gamemaster = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
        CommandSourceStack moderator = Commands.createCompilationContext(LevelBasedPermissionSet.MODERATOR);

        for (String name : new String[] {"bind", "unbind", "bindings"}) {
            CommandNode<CommandSourceStack> node = haruhi.getChild(name);
            assertTrue(node.canUse(gamemaster), "a gamemaster may use /haruhi " + name);
            assertFalse(node.canUse(moderator), "a moderator below gamemaster may not use /haruhi " + name);
        }
    }

    @Test
    void aGamemasterCanRunEveryFormAndAPlayerNone() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack gamemaster = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
        CommandSourceStack player = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS);

        for (String command : COMMANDS) {
            assertTrue(runs(dispatcher, command, gamemaster), command);
            assertFalse(runs(dispatcher, command, player), command);
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, player), command);
        }
    }

    private static boolean runs(
            CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(command, source);
        return parsed.getExceptions().isEmpty()
                && !parsed.getReader().canRead()
                && parsed.getContext().getLastChild().getCommand() != null;
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        // BindingCommands.register uses neither the build context nor the selection.
        BindingCommands.register(dispatcher, null, Commands.CommandSelection.DEDICATED);
        return dispatcher;
    }
}
