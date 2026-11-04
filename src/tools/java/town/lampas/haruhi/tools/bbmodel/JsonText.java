package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Writes the converter's JSON. Objects and arrays that hold only numbers, strings and short number arrays go on one line,
 * as Blockbench writes cube UVs and keyframes, so the files stay readable. Numbers are written without a trailing
 * {@code .0} and rounded to 6 decimals, which removes float noise such as {@code -3.9000000000000004}.
 */
final class JsonText {
    private static final int INLINE_LIMIT = 100;

    private JsonText() {}

    static String write(JsonElement element) {
        StringBuilder out = new StringBuilder();
        write(out, element, 0);
        return out.append('\n').toString();
    }

    /** A number as the converter writes it. */
    static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("not a finite number: " + value);
        }
        BigDecimal rounded = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        if (rounded.signum() == 0) {
            return "0";
        }
        return rounded.toPlainString();
    }

    private static void write(StringBuilder out, JsonElement element, int indent) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.isEmpty()) {
                out.append("{}");
            } else if (inlinable(object)) {
                out.append(inline(object));
            } else {
                out.append("{\n");
                int i = 0;
                for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                    pad(out, indent + 1).append(quote(entry.getKey())).append(": ");
                    write(out, entry.getValue(), indent + 1);
                    out.append(++i < object.size() ? ",\n" : "\n");
                }
                pad(out, indent).append('}');
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (array.isEmpty()) {
                out.append("[]");
            } else if (inlinable(array)) {
                out.append(inline(array));
            } else {
                out.append("[\n");
                for (int i = 0; i < array.size(); i++) {
                    pad(out, indent + 1);
                    write(out, array.get(i), indent + 1);
                    out.append(i + 1 < array.size() ? ",\n" : "\n");
                }
                pad(out, indent).append(']');
            }
        } else {
            out.append(primitive(element));
        }
    }

    private static boolean inlinable(JsonElement element) {
        boolean flat = element.isJsonObject()
                ? element.getAsJsonObject().asMap().values().stream().allMatch(JsonText::flatValue)
                : element.getAsJsonArray().asList().stream().allMatch(JsonText::flatValue);
        return flat && inline(element).length() <= INLINE_LIMIT;
    }

    private static boolean flatValue(JsonElement value) {
        if (value.isJsonPrimitive() || value.isJsonNull()) {
            return true;
        }
        return value.isJsonArray()
                && value.getAsJsonArray().asList().stream().allMatch(v -> v.isJsonPrimitive() || v.isJsonNull());
    }

    private static String inline(JsonElement element) {
        if (element.isJsonObject()) {
            StringBuilder out = new StringBuilder("{");
            int i = 0;
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                out.append(i++ == 0 ? "" : ", ").append(quote(entry.getKey())).append(": ").append(inline(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder("[");
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                out.append(i == 0 ? "" : ", ").append(inline(array.get(i)));
            }
            return out.append(']').toString();
        }
        return primitive(element);
    }

    private static String primitive(JsonElement element) {
        if (element.isJsonNull()) {
            return "null";
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return number(primitive.getAsDouble());
        }
        if (primitive.isBoolean()) {
            return Boolean.toString(primitive.getAsBoolean());
        }
        return quote(primitive.getAsString());
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static StringBuilder pad(StringBuilder out, int indent) {
        return out.append("  ".repeat(indent));
    }
}
