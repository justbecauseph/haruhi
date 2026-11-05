package town.lampas.haruhi.content.api;

import com.google.gson.JsonElement;
import java.util.Optional;

/**
 * Parses and validates one section of a bundle's {@code extensions} object. It runs once per validation, after the
 * bundle's flags, NPC profiles and dialogue graphs have been read and before any condition, action or provider is
 * parsed, so those parsers can read the result through {@link town.lampas.haruhi.dialogue.api.ParseContext#extension}.
 *
 * <p>Report every problem through {@link ExtensionContext#problem}, so one validation lists them all. Return the parsed
 * value when the section is usable, and empty otherwise. The bundle is refused whenever any problem was reported, so a
 * value returned beside problems is only seen by the rest of that validation pass. The value should be immutable: every
 * reader of the active bundle shares it.
 *
 * <p>{@code section} is a copy, so changing it cannot change the revision that is stored. It is not shared with other
 * extensions.
 */
@FunctionalInterface
public interface ContentExtensionType<T> {
    Optional<T> parse(JsonElement section, ExtensionContext context);
}
