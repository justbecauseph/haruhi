package town.lampas.haruhi.content.api;

import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * The typed handle of a registered bundle extension. {@link ContentExtensions#register} returns it, and
 * {@link town.lampas.haruhi.content.model.ContentBundle#extension} reads the parsed section with it.
 *
 * @param id the key of the section under the bundle's {@code extensions} object
 * @param valueClass the class of the value the extension's parser returns
 */
public record ContentExtensionKey<T>(Identifier id, Class<T> valueClass) {
    public ContentExtensionKey {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(valueClass, "valueClass");
    }
}
