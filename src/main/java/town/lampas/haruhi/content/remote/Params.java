package town.lampas.haruhi.content.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads one method's param leniently. Vanilla logs a param that fails to decode together with the whole call, which
 * would put a fetch token in the log, so every method's param decodes as any JSON and is checked here. A call whose
 * envelope vanilla refuses before decoding is logged with its params redacted by
 * {@code town.lampas.haruhi.mixin.JsonRpcParamsLogMixin}. A field of the
 * wrong type or form is reported by name through {@link Invalid}; nothing quotes the value. Unknown fields are ignored.
 */
final class Params {
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX_64 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    /** A bearer token: visible ASCII, so it cannot break the header it is sent in. */
    private static final Pattern TOKEN = Pattern.compile("[\\x21-\\x7E]{1,4096}");
    static final int MAX_NAME_LENGTH = 512;

    /** A field that is missing, of the wrong type or of the wrong form. */
    static final class Invalid extends Exception {
        private final String field;

        Invalid(String field, String message) {
            super(message, null, false, false);
            this.field = field;
        }

        String field() {
            return field;
        }
    }

    private final JsonObject object;

    private Params(JsonObject object) {
        this.object = object;
    }

    /** The param, which must be a JSON object. A missing param reads as an empty object. */
    static Params of(JsonElement param) throws Invalid {
        if (param == null || param.isJsonNull()) {
            return new Params(new JsonObject());
        }
        if (!param.isJsonObject()) {
            throw new Invalid("", "the param must be a JSON object");
        }
        return new Params(param.getAsJsonObject());
    }

    /** A dashed UUID, as {@link UUID#toString()} writes it, in either case. */
    UUID uuid(String field) throws Invalid {
        String text = string(field).orElseThrow(() -> missing(field));
        if (!UUID_TEXT.matcher(text).matches()) {
            throw new Invalid(field, field + " must be a UUID such as 123e4567-e89b-12d3-a456-426614174000");
        }
        return UUID.fromString(text);
    }

    /** A SHA-256 as 64 hex digits, returned in lowercase. */
    String sha256(String field) throws Invalid {
        String text = string(field).orElseThrow(() -> missing(field));
        if (!HEX_64.matcher(text).matches()) {
            throw new Invalid(field, field + " must be a SHA-256 as 64 hex digits");
        }
        return text.toLowerCase(Locale.ROOT);
    }

    /** A bearer token: 1 to 4096 visible ASCII characters. */
    String token(String field) throws Invalid {
        String text = string(field).orElseThrow(() -> missing(field));
        if (!TOKEN.matcher(text).matches()) {
            throw new Invalid(field, field + " must be 1 to 4096 visible ASCII characters");
        }
        return text;
    }

    /** A name for the record, such as an author or an actor: not blank, no control characters, at most 512 long. */
    String name(String field) throws Invalid {
        String text = string(field).orElseThrow(() -> missing(field));
        if (text.isBlank() || text.length() > MAX_NAME_LENGTH || text.chars().anyMatch(Character::isISOControl)) {
            throw new Invalid(field, field + " must be 1 to " + MAX_NAME_LENGTH
                    + " characters, not blank, without control characters");
        }
        return text;
    }

    /** A required whole number of at least {@code min}. */
    long whole(String field, long min) throws Invalid {
        OptionalLong value = optionalWhole(field, min);
        if (value.isEmpty()) {
            throw missing(field);
        }
        return value.getAsLong();
    }

    /**
     * An optional whole number of at least {@code min}. Read from the number's text, so no value overflows or throws:
     * {@code 1.5}, {@code 1e3}, {@code -1} and {@code "5"} are refused, and a number past {@link Long#MAX_VALUE} reads
     * as {@link Long#MAX_VALUE}.
     */
    OptionalLong optionalWhole(String field, long min) throws Invalid {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return OptionalLong.empty();
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()
                || !DIGITS.matcher(primitive.getAsString()).matches()) {
            throw new Invalid(field, field + " must be a whole number of at least " + min);
        }
        long value;
        try {
            value = Long.parseLong(primitive.getAsString());
        } catch (NumberFormatException e) {
            value = Long.MAX_VALUE;
        }
        if (value < min) {
            throw new Invalid(field, field + " must be a whole number of at least " + min);
        }
        return OptionalLong.of(value);
    }

    /** An optional string. */
    Optional<String> string(String field) throws Invalid {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return Optional.empty();
        }
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new Invalid(field, field + " must be a string");
        }
        return Optional.of(primitive.getAsString());
    }

    private static Invalid missing(String field) {
        return new Invalid(field, field + " is required");
    }
}
