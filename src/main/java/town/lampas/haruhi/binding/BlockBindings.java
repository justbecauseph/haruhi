package town.lampas.haruhi.binding;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Decoder;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import town.lampas.haruhi.Haruhi;

/**
 * One dimension's block bindings, keyed by position, saved in that dimension's {@code data/haruhi/block_bindings.dat}.
 * Server thread only.
 *
 * <p>Loading is tolerant: an entry that does not decode is logged and skipped, so one bad entry cannot cost the rest.
 * Vanilla replaces a file that fails to decode as a whole with empty data.
 */
public final class BlockBindings extends SavedData {
    static final Identifier ID = Haruhi.id("block_bindings");
    private static final String BINDINGS = "bindings";

    /** One saved binding: its position and the binding itself, flattened into one object. */
    record Entry(BlockPos pos, BlockBinding binding) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                BlockBinding.CODEC.fieldOf("binding").forGetter(Entry::binding)
        ).apply(instance, Entry::new));
    }

    public static final Codec<BlockBindings> CODEC = Codec.of(
            Entry.CODEC.listOf().fieldOf(BINDINGS).codec().comap(BlockBindings::entries),
            new TolerantDecoder());

    public static final SavedDataType<BlockBindings> TYPE = new SavedDataType<>(ID, BlockBindings::new, CODEC, null);

    private final Map<BlockPos, BlockBinding> bindings = new HashMap<>();

    public BlockBindings() {}

    /** The level's bindings, loaded from its data folder the first time. */
    public static BlockBindings of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<BlockBinding> get(BlockPos pos) {
        return Optional.ofNullable(bindings.get(pos));
    }

    /** Binds the position, replacing any binding it had, which is returned. */
    public Optional<BlockBinding> put(BlockPos pos, BlockBinding binding) {
        Optional<BlockBinding> previous = Optional.ofNullable(bindings.put(pos.immutable(), binding));
        setDirty();
        return previous;
    }

    /** Unbinds the position, returning the binding it had. */
    public Optional<BlockBinding> remove(BlockPos pos) {
        Optional<BlockBinding> removed = Optional.ofNullable(bindings.remove(pos));
        removed.ifPresent(binding -> setDirty());
        return removed;
    }

    public Map<BlockPos, BlockBinding> all() {
        return Collections.unmodifiableMap(bindings);
    }

    /** The bindings in position order, so the saved file and the listings are stable. */
    List<Entry> entries() {
        List<Entry> entries = new ArrayList<>(bindings.size());
        bindings.forEach((pos, binding) -> entries.add(new Entry(pos, binding)));
        entries.sort(Comparator.comparing(Entry::pos));
        return entries;
    }

    /** Reads the {@code bindings} list entry by entry, skipping the entries that do not decode. */
    private static final class TolerantDecoder implements Decoder<BlockBindings> {
        @Override
        public <T> DataResult<Pair<BlockBindings, T>> decode(DynamicOps<T> ops, T input) {
            return ops.getMap(input).map(map -> {
                BlockBindings data = new BlockBindings();
                T list = map.get(BINDINGS);
                if (list == null) {
                    return Pair.of(data, input);
                }
                DataResult<Stream<T>> elements = ops.getStream(list);
                if (elements.result().isEmpty()) {
                    Haruhi.LOGGER.warn("Haruhi block bindings: '{}' is not a list; no block bindings were loaded",
                            BINDINGS);
                    return Pair.of(data, input);
                }
                elements.result().get().forEach(element -> {
                    DataResult<Entry> entry = Entry.CODEC.parse(ops, element);
                    entry.result().ifPresentOrElse(
                            read -> data.bindings.put(read.pos(), read.binding()),
                            () -> Haruhi.LOGGER.warn("Haruhi block bindings: skipped an entry that does not decode: {}",
                                    entry.error().map(DataResult.Error::message).orElse("?")));
                });
                return Pair.of(data, input);
            });
        }
    }
}
