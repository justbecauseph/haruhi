package town.lampas.haruhi.binding;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.network.BoundBlocksPayload;

/**
 * Client-side cache of bound blocks in the current dimension that have a {@code speaker_name} override. Kept in the
 * common binding package with no client-only imports so both client receivers and optional HUD integrations can query
 * it without violating {@code ArchitectureTest}.
 */
public final class BoundBlockHints {
    private static final State EMPTY = new State(null, Map.of());
    private static volatile State current = EMPTY;

    private BoundBlockHints() {}

    /** Replaces the cached block override positions with the entries from {@code payload}. */
    public static void apply(BoundBlocksPayload payload) {
        if (payload.blocks().isEmpty()) {
            current = EMPTY;
            return;
        }
        Map<BlockPos, Identifier> entries = new HashMap<>(payload.blocks().size());
        for (BoundBlocksPayload.Entry entry : payload.blocks()) {
            entries.put(entry.pos().immutable(), entry.block());
        }
        current = new State(payload.dimension(), Map.copyOf(entries));
    }

    /** Clears all cached block override positions, called on disconnect. */
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
        State snapshot = current;
        if (dimension == null || level == null || snapshot.entries().isEmpty()
                || !dimension.equals(snapshot.dimension())) {
            return false;
        }
        if (matches(snapshot.entries(), pos, state)) {
            return true;
        }
        return BlockParts.connectedHalf(level, pos, state)
                .filter(other -> matches(snapshot.entries(), other, state))
                .isPresent();
    }

    private static boolean matches(Map<BlockPos, Identifier> entries, BlockPos pos, BlockState state) {
        Identifier expected = entries.get(pos);
        return expected != null && expected.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    private record State(@Nullable Identifier dimension, Map<BlockPos, Identifier> entries) {}
}
