package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import java.time.Instant;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.storage.StoredRevision;

/** The {@code /haruhi content} reply text and the permission gate on the command tree. */
class ContentCommandsTest {
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeAll
    static void boot() {
        ContentTestSupport.boot();
    }

    @Test
    void importOfAnActiveRevisionSaysItIsActive() {
        String reply = ContentCommands.storedReply(stored(3, true));

        assertTrue(reply.startsWith("Revision 3 (digest 0123456789ab) already has this content and is active"), reply);
        assertFalse(reply.contains("not active"), reply);
    }

    @Test
    void importOfAnInactiveRevisionSaysHowToActivateIt() {
        assertEquals(
                "Stored revision 3 (digest 0123456789ab). It is not active. Run /haruhi content activate 3 to use it.",
                ContentCommands.storedReply(stored(3, false)));
    }

    @Test
    void theContentTreeIsOnlyForGamemasters() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandNode<CommandSourceStack> content = dispatcher.getRoot().getChild("haruhi").getChild("content");
        CommandSourceStack gamemaster = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
        CommandSourceStack moderator = Commands.createCompilationContext(LevelBasedPermissionSet.MODERATOR);
        CommandSourceStack player = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS);

        assertTrue(content.canUse(gamemaster), "a gamemaster may use /haruhi content");
        assertFalse(content.canUse(moderator), "a moderator below gamemaster may not use /haruhi content");
        assertFalse(content.canUse(player), "a player may not use /haruhi content");
    }

    @Test
    void aPlayerIsRefusedEveryContentSubcommand() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack player = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS);

        for (String command : new String[] {
                "haruhi content status", "haruhi content list", "haruhi content rollback",
                "haruhi content activate 1", "haruhi content import greeter.json"}) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, player), command);
        }
    }

    @Test
    void aGamemasterCanParseEveryContentSubcommand() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        CommandSourceStack gamemaster = Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);

        for (String command : new String[] {
                "haruhi content status", "haruhi content list", "haruhi content rollback",
                "haruhi content activate 1", "haruhi content import greeter.json",
                "haruhi content validate greeter.json"}) {
            assertTrue(dispatcher.parse(command, gamemaster).getExceptions().isEmpty(), command);
        }
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(ContentCommands.root(new ContentServiceImpl()));
        return dispatcher;
    }

    private static StoredRevision stored(long id, boolean active) {
        return new StoredRevision(id, DIGEST, "{}", "tester", Instant.EPOCH,
                active ? Optional.of(Instant.EPOCH) : Optional.empty(), active);
    }
}
