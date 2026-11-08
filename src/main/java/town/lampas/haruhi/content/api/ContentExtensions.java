package town.lampas.haruhi.content.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * Registered bundle extensions. A mod registers its extension during its own init, in its own namespace, before any
 * content is validated. A bundle that holds a section under an id nobody registered is refused. Registering the same id
 * twice throws.
 */
public final class ContentExtensions {
    private static final Map<Identifier, Registered<?>> EXTENSIONS = new ConcurrentHashMap<>();

    /** A registered extension: its key and its parser. */
    public record Registered<T>(ContentExtensionKey<T> key, ContentExtensionType<T> type) {}

    private ContentExtensions() {}

    public static <T> ContentExtensionKey<T> register(Identifier id, Class<T> valueClass, ContentExtensionType<T> type) {
        ContentExtensionKey<T> key = new ContentExtensionKey<>(id, valueClass);
        if (EXTENSIONS.putIfAbsent(id, new Registered<>(key, type)) != null) {
            throw new IllegalStateException("Content extension " + id + " is already registered");
        }
        return key;
    }

    public static Optional<Registered<?>> get(Identifier id) {
        return Optional.ofNullable(EXTENSIONS.get(id));
    }

    /** The ids registered so far, sorted by their text. */
    public static List<Identifier> ids() {
        return EXTENSIONS.keySet().stream().sorted(Comparator.comparing(Identifier::toString)).toList();
    }
}
