package town.lampas.haruhi.binding;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * {@code /haruhi bind}, {@code /haruhi unbind} and {@code /haruhi bindings}, for gamemasters. Graphs and speaker profiles
 * are checked against the active content when bound. Feedback is plain text.
 */
final class BindingCommands {
    /** {@code /haruhi bindings} without {@code all} lists the bindings within this many blocks of the source. */
    static final int NEAR_RADIUS = 32;

    private static final Predicate<CommandSourceStack> GAMEMASTERS = Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

    private BindingCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext,
            Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("haruhi")
                .then(Commands.literal("bind").requires(GAMEMASTERS)
                        .then(Commands.literal("block")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(graphArgument()
                                                .executes(context -> bindBlock(context, Optional.empty()))
                                                .then(speakerArgument()
                                                        .executes(context -> bindBlock(context, speaker(context)))))))
                        .then(Commands.literal("entity")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(graphArgument()
                                                .executes(context -> bindEntities(context, Optional.empty()))
                                                .then(speakerArgument()
                                                        .executes(context -> bindEntities(context,
                                                                speaker(context))))))))
                .then(Commands.literal("unbind").requires(GAMEMASTERS)
                        .then(Commands.literal("block")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(BindingCommands::unbindBlock)))
                        .then(Commands.literal("entity")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .executes(BindingCommands::unbindEntities))))
                .then(Commands.literal("bindings").requires(GAMEMASTERS)
                        .executes(context -> list(context, false))
                        .then(Commands.literal("all").executes(context -> list(context, true)))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Identifier>
            graphArgument() {
        return Commands.argument("graph", IdentifierArgument.id()).suggests(BindingCommands::suggestGraphs);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Identifier>
            speakerArgument() {
        return Commands.argument("speaker", IdentifierArgument.id()).suggests(BindingCommands::suggestProfiles);
    }

    private static Optional<Identifier> speaker(CommandContext<CommandSourceStack> context) {
        return Optional.of(IdentifierArgument.getId(context, "speaker"));
    }

    private static int bindBlock(CommandContext<CommandSourceStack> context, Optional<Identifier> speaker)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, level, "pos");
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            throw fail("There is no block at " + describe(level, pos));
        }
        Binding binding = checked(context, speaker);
        BlockBinding bound = new BlockBinding(binding.graph(), binding.speaker(), BlockBinding.blockId(state));
        Optional<BlockBinding> previous = BlockBindings.of(level).put(pos, bound);
        String replaced = previous.map(old -> ", replacing " + old.graph()).orElse("");
        source.sendSuccess(() -> Component.literal("Bound " + bound.block() + " at " + describe(level, pos) + " to "
                + bound.graph() + BindingProblems.speakerSuffix(bound.speaker()) + replaced), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int bindEntities(CommandContext<CommandSourceStack> context, Optional<Identifier> speaker)
            throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        Binding binding = checked(context, speaker);
        List<Entity> bound = new ArrayList<>();
        int players = 0;
        int npcs = 0;
        for (Entity entity : targets) {
            if (entity instanceof Player) {
                players++;
            } else if (!BindingInteractions.isBindable(entity)) {
                npcs++;
            } else {
                EntityBindings.set(entity, binding);
                bound.add(entity);
            }
        }
        String skipped = skipped(players, npcs);
        if (bound.isEmpty()) {
            throw fail("No entity was bound" + skipped);
        }
        String what = bound.size() == 1 ? describe(bound.getFirst()) : bound.size() + " entities";
        context.getSource().sendSuccess(() -> Component.literal("Bound " + what + " to " + binding.graph()
                + BindingProblems.speakerSuffix(binding.speaker()) + skipped), true);
        return bound.size();
    }

    private static int unbindBlock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        // No loaded check: a binding is saved data, so one in an unloaded chunk can be removed too.
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        Optional<BlockBinding> removed = BlockBindings.of(level).remove(pos);
        if (removed.isEmpty()) {
            throw fail("No block binding at " + describe(level, pos));
        }
        source.sendSuccess(() -> Component.literal("Unbound " + removed.get().block() + " at " + describe(level, pos)
                + " from " + removed.get().graph()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int unbindEntities(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        List<Entity> unbound = new ArrayList<>();
        for (Entity entity : targets) {
            if (EntityBindings.remove(entity).isPresent()) {
                unbound.add(entity);
            }
        }
        if (unbound.isEmpty()) {
            throw fail("None of the selected entities has a binding");
        }
        String what = unbound.size() == 1 ? describe(unbound.getFirst()) : unbound.size() + " entities";
        context.getSource().sendSuccess(() -> Component.literal("Unbound " + what), true);
        return unbound.size();
    }

    private static int list(CommandContext<CommandSourceStack> context, boolean all) {
        CommandSourceStack source = context.getSource();
        List<String> lines = all
                ? describeAll(source.getServer(), null, null)
                : describeAll(source.getServer(), source.getLevel(), source.getPosition());
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Math.max(0, lines.size() - 1);
    }

    /**
     * The listing of {@code /haruhi bindings}: a header line, then one line per block binding, then one per bound entity,
     * each marked with its {@link BindingProblems}. Entities are listed only while loaded.
     *
     * @param nearLevel with {@code near}, lists only bindings within {@link #NEAR_RADIUS} blocks of it in this level;
     *         null for every dimension
     */
    static List<String> describeAll(MinecraftServer server, @Nullable ServerLevel nearLevel, @Nullable Vec3 near) {
        ContentBundle content = HaruhiServices.content().active();
        List<String> blocks = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        int problems = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (nearLevel != null && level != nearLevel) {
                continue;
            }
            for (BlockBindings.Entry entry : BlockBindings.of(level).entries()) {
                if (near != null && !Vec3.atCenterOf(entry.pos()).closerThan(near, NEAR_RADIUS)) {
                    continue;
                }
                BlockBinding binding = entry.binding();
                // A binding in an unloaded chunk is listed without loading it, so its block cannot be checked.
                Optional<Identifier> blockNow = level.isLoaded(entry.pos())
                        ? Optional.of(BlockBinding.blockId(level.getBlockState(entry.pos())))
                        : Optional.empty();
                List<String> found = blockNow
                        .map(id -> BindingProblems.of(binding, content, id))
                        .orElseGet(() -> BindingProblems.of(binding.binding(), content));
                problems += found.isEmpty() ? 0 : 1;
                blocks.add("block " + binding.block() + " at " + describe(level, entry.pos()) + " -> "
                        + binding.graph() + BindingProblems.speakerSuffix(binding.speaker())
                        + (blockNow.isEmpty() ? " (chunk not loaded)" : "") + BindingProblems.suffix(found));
            }
            List<Entity> bound = new ArrayList<>();
            Iterable<Entity> candidates = near == null
                    ? level.getAllEntities()
                    : level.getEntities((Entity) null, AABB.ofSize(near, 2 * NEAR_RADIUS, 2 * NEAR_RADIUS,
                            2 * NEAR_RADIUS), entity -> entity.position().closerThan(near, NEAR_RADIUS));
            for (Entity entity : candidates) {
                if (EntityBindings.get(entity).isPresent()) {
                    bound.add(entity);
                }
            }
            bound.sort(Comparator.comparing(Entity::getStringUUID));
            for (Entity entity : bound) {
                Binding binding = EntityBindings.get(entity).orElseThrow();
                List<String> found = BindingProblems.of(binding, content);
                problems += found.isEmpty() ? 0 : 1;
                entities.add("entity " + describe(entity) + " -> " + binding.graph()
                        + BindingProblems.speakerSuffix(binding.speaker()) + BindingProblems.suffix(found));
            }
        }
        List<String> lines = new ArrayList<>(1 + blocks.size() + entities.size());
        String scope = near == null ? "Haruhi bindings" : "Haruhi bindings within " + NEAR_RADIUS + " blocks";
        if (blocks.isEmpty() && entities.isEmpty()) {
            lines.add("No " + scope + (near == null ? "" : "; /haruhi bindings all lists every dimension"));
            return lines;
        }
        lines.add(String.format(Locale.ROOT, "%s: %d %s, %d loaded %s, %d with problems", scope,
                blocks.size(), blocks.size() == 1 ? "block" : "blocks",
                entities.size(), entities.size() == 1 ? "entity" : "entities", problems));
        lines.addAll(blocks);
        lines.addAll(entities);
        return lines;
    }

    /** The graph and speaker arguments as a binding, after checking both against the active content. */
    private static Binding checked(CommandContext<CommandSourceStack> context, Optional<Identifier> speaker)
            throws CommandSyntaxException {
        ContentBundle content = HaruhiServices.content().active();
        Identifier graph = IdentifierArgument.getId(context, "graph");
        if (!content.dialogues().containsKey(graph)) {
            throw fail("Unknown dialogue graph " + graph);
        }
        if (speaker.isPresent() && !content.npcs().containsKey(speaker.get())) {
            throw fail("Unknown NPC profile " + speaker.get());
        }
        return new Binding(graph, speaker);
    }

    private static String skipped(int players, int npcs) {
        List<String> parts = new ArrayList<>(2);
        if (players > 0) {
            parts.add(players + (players == 1 ? " player" : " players"));
        }
        if (npcs > 0) {
            parts.add(npcs + (npcs == 1 ? " Haruhi NPC, which talks through its profile"
                    : " Haruhi NPCs, which talk through their profiles"));
        }
        return parts.isEmpty() ? "" : "; skipped " + String.join(" and ", parts);
    }

    private static CommandSyntaxException fail(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    private static CompletableFuture<Suggestions> suggestGraphs(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(HaruhiServices.content().active().dialogues().keySet(),
                builder);
    }

    private static CompletableFuture<Suggestions> suggestProfiles(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggestResource(HaruhiServices.content().active().npcs().keySet(), builder);
    }

    private static String describe(ServerLevel level, BlockPos pos) {
        return String.format(Locale.ROOT, "%d %d %d in %s", pos.getX(), pos.getY(), pos.getZ(),
                level.dimension().identifier());
    }

    /** One entity: its type, name, UUID, dimension and position. */
    private static String describe(Entity entity) {
        return String.format(Locale.ROOT, "%s \"%s\" %s in %s at %.2f %.2f %.2f",
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                entity.getName().getString(), entity.getStringUUID(), entity.level().dimension().identifier(),
                entity.getX(), entity.getY(), entity.getZ());
    }
}
