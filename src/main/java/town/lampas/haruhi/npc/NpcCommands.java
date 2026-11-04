package town.lampas.haruhi.npc;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import town.lampas.haruhi.api.HaruhiServices;

/**
 * The {@code /haruhi npc} commands: spawn, set, remove and list. Gamemasters only. Profiles are checked against the active
 * content, and the feedback is plain text.
 */
final class NpcCommands {
    private static final Predicate<CommandSourceStack> GAMEMASTERS = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);
    private static final SimpleCommandExceptionType NO_SUCH_NPC = new SimpleCommandExceptionType(
            Component.literal("No NPC entity is selected"));

    private NpcCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
                         Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("haruhi")
                .then(Commands.literal("npc").requires(GAMEMASTERS)
                        .then(Commands.literal("spawn")
                                .then(Commands.argument("profile", IdentifierArgument.id())
                                        .suggests(NpcCommands::suggestProfiles)
                                        .executes(ctx -> spawn(ctx, ctx.getSource().getPosition()))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .executes(ctx -> spawn(ctx, Vec3Argument.getVec3(ctx, "pos"))))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .then(Commands.argument("profile", IdentifierArgument.id())
                                                .suggests(NpcCommands::suggestProfiles)
                                                .executes(NpcCommands::set))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("entity", EntityArgument.entity())
                                        .executes(NpcCommands::remove)))
                        .then(Commands.literal("list").executes(NpcCommands::list))));
    }

    private static int spawn(CommandContext<CommandSourceStack> context, Vec3 pos) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Identifier profileId = IdentifierArgument.getId(context, "profile");
        requireProfile(profileId);
        // The same check /summon makes: an NPC above or below the world would never be reachable.
        if (!Level.isInSpawnableBounds(BlockPos.containing(pos))) {
            throw fail("Invalid position for spawn");
        }

        ServerLevel level = source.getLevel();
        NpcEntity npc = NpcEntities.NPC.create(level, EntitySpawnReason.COMMAND);
        if (npc == null) {
            throw fail("The NPC entity type could not be created");
        }
        npc.setPos(pos.x, pos.y, pos.z);
        // Toward the source, so an NPC spawned in front of a player (^ ^ ^3) faces that player, not away from them.
        npc.turnTo(NpcFacing.spawnYaw(pos, source.getPosition(), source.getRotation().y));
        npc.setProfile(profileId);
        level.addFreshEntity(npc);
        source.sendSuccess(() -> Component.literal("Spawned NPC " + describe(npc)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        NpcEntity npc = npcArgument(context);
        Identifier profileId = IdentifierArgument.getId(context, "profile");
        requireProfile(profileId);
        npc.setProfile(profileId);
        context.getSource().sendSuccess(() -> Component.literal("Set NPC " + describe(npc)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        NpcEntity npc = npcArgument(context);
        String description = describe(npc);
        npc.discard();
        context.getSource().sendSuccess(() -> Component.literal("Removed NPC " + description), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> lines = new ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof NpcEntity npc) {
                    lines.add(describe(npc));
                }
            }
        }
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No NPCs are loaded"), false);
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static NpcEntity npcArgument(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(context, "entity");
        if (entity instanceof NpcEntity npc) {
            return npc;
        }
        throw NO_SUCH_NPC.create();
    }

    private static void requireProfile(Identifier profileId) throws CommandSyntaxException {
        if (!HaruhiServices.content().active().npcs().containsKey(profileId)) {
            throw fail("Unknown NPC profile " + profileId);
        }
    }

    private static CommandSyntaxException fail(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    private static CompletableFuture<Suggestions> suggestProfiles(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(HaruhiServices.content().active().npcs().keySet(), builder);
    }

    /** One line for an NPC: its profile, dimension and position. */
    private static String describe(NpcEntity npc) {
        String profile = npc.profileId().map(Identifier::toString).orElse("none");
        return String.format(Locale.ROOT, "%s in %s at %.2f %.2f %.2f (entity %d)", profile,
                npc.level().dimension().identifier(), npc.getX(), npc.getY(), npc.getZ(), npc.getId());
    }
}
