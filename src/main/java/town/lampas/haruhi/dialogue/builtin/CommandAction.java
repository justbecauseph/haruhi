package town.lampas.haruhi.dialogue.builtin;

import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.permissions.PermissionSet;
import town.lampas.haruhi.dialogue.DialogueConfig;
import town.lampas.haruhi.dialogue.api.ActionType;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueContext;

/**
 * {@code haruhi:command}: runs a command as the player at the permission level from the dialogue config. The command's
 * first word must be on the config's allowlist, or the bundle is refused when it is validated.
 */
final class CommandAction {

    private CommandAction() {}

    static ActionType parser(DialogueConfig config) {
        return (json, context) -> Args.<DialogueAction>parse(context, "haruhi:command", json, Set.of("command"), args -> {
            String command = normalize(args.string("command"));
            if (command.isEmpty()) {
                throw Args.invalid("'command' has no command in it");
            }
            String firstWord = command.split("\\s+", 2)[0];
            if (!config.commandAllowlist().contains(firstWord)) {
                throw Args.invalid("command '" + firstWord + "' is not on the allowlist in "
                        + DialogueConfig.FILE_NAME);
            }
            return ctx -> run(ctx, command, config.commandPermissionLevel());
        });
    }

    /** The command without surrounding space and without a leading slash. */
    static String normalize(String raw) {
        String trimmed = raw.strip();
        return trimmed.startsWith("/") ? trimmed.substring(1).strip() : trimmed;
    }

    private static void run(DialogueContext context, String command, int level) {
        ServerPlayer player = context.player();
        MinecraftServer server = player.level().getServer();
        PermissionSet permissions = LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level));
        CommandSourceStack source = player.createCommandSourceStack().withPermission(permissions);
        server.getCommands().performPrefixedCommand(source, command);
    }
}
