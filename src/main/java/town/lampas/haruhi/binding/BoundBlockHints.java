package town.lampas.haruhi.binding;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.network.BoundBlocksPayload;

/**
 * Client-side cache of bound blocks in the current dimension and active dialogue graph ids. Kept in the common binding
 * package with no client-only imports so client receivers, visual indicators, and optional HUD integrations can query
 * it without violating {@code ArchitectureTest}.
 */
public final class BoundBlockHints {
    private static final State EMPTY = new State(null, Map.of(), Set.of());
    private static volatile State current = EMPTY;

    private BoundBlockHints() {}

    /** Replaces the cached block entries and active graph ids with those from {@code payload}. */
    public static void apply(BoundBlocksPayload payload) {
        Map<BlockPos, BoundBlocksPayload.Entry> entries = new HashMap<>(payload.blocks().size());
        for (BoundBlocksPayload.Entry entry : payload.blocks()) {
            entries.put(entry.pos().immutable(), entry);
        }
        current = new State(payload.dimension(), Map.copyOf(entries), Set.copyOf(payload.activeGraphs()));
    }

    /** Clears all cached block entries and active graph ids, called on disconnect. */
    public static void clear() {
        current = EMPTY;
    }

    /**
     * True when {@code pos} (or its connected two-block half) matches a cached binding with a {@code speaker_name}
     * override for the current block at {@code pos}.
     */
    public static boolean hasOverride(@Nullable Level level, BlockPos pos, BlockState state) {
        return hasOverride(level == null ? null : level.dimension().identifier(), level, pos, state);
    }

    static boolean hasOverride(@Nullable Identifier dimension, @Nullable BlockGetter level, BlockPos pos,
            BlockState state) {
        return entryAt(dimension, level, pos, state).filter(BoundBlocksPayload.Entry::hasNameOverride).isPresent();
    }

    /**
     * True when {@code pos} (or its connected two-block half) matches a cached binding whose block matches {@code state}
     * and whose graph exists in the active content.
     */
    public static boolean isActiveBound(@Nullable Level level, BlockPos pos, BlockState state) {
        return isActiveBound(level == null ? null : level.dimension().identifier(), level, pos, state);
    }

    static boolean isActiveBound(@Nullable Identifier dimension, @Nullable BlockGetter level, BlockPos pos,
            BlockState state) {
        return entryAt(dimension, level, pos, state).filter(BoundBlocksPayload.Entry::active).isPresent();
    }

    /** True when {@code graph} exists in the server's active content bundle as last synced. */
    public static boolean isGraphActive(Identifier graph) {
        return current.activeGraphs().contains(graph);
    }

    /** All cached bound block entries in {@code level}, or an empty collection when none or in another dimension. */
    public static Collection<BoundBlocksPayload.Entry> entriesIn(@Nullable Level level) {
        return entriesIn(level == null ? null : level.dimension().identifier());
    }

    static Collection<BoundBlocksPayload.Entry> entriesIn(@Nullable Identifier dimension) {
        State snapshot = current;
        if (dimension == null || !dimension.equals(snapshot.dimension())) {
            return Set.of();
        }
        return snapshot.entries().values();
    }

    private static Optional<BoundBlocksPayload.Entry> entryAt(@Nullable Identifier dimension,
            @Nullable BlockGetter level, BlockPos pos, BlockState state) {
        State snapshot = current;
        if (dimension == null || level == null || snapshot.entries().isEmpty()
                || !dimension.equals(snapshot.dimension())) {
            return Optional.empty();
        }
        Optional<BoundBlocksPayload.Entry> direct = matching(snapshot.entries(), pos, state);
        if (direct.isPresent()) {
            return direct;
        }
        return BlockParts.connectedHalf(level, pos, state)
                .flatMap(other -> matching(snapshot.entries(), other, state));
    }

    private static Optional<BoundBlocksPayload.Entry> matching(Map<BlockPos, BoundBlocksPayload.Entry> entries,
            BlockPos pos, BlockState state) {
        BoundBlocksPayload.Entry entry = entries.get(pos);
        if (entry != null && entry.block().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
            return Optional.of(entry);
        }
        return Optional.empty();
    }

    private record State(
            @Nullable Identifier dimension,
            Map<BlockPos, BoundBlocksPayload.Entry> entries,
            Set<Identifier> activeGraphs) {}
}
