package town.lampas.haruhi.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The canonical form of a bundle and its digest. The canonical form is the parsed JSON with object keys sorted and no
 * whitespace, so two files that differ only in key order or layout have the same digest.
 */
final class BundleDigest {
    private BundleDigest() {}

    static String canonicalJson(JsonElement element) {
        return sorted(element).toString();
    }

    /** The lowercase hex SHA-256 of the UTF-8 bytes of {@code text}. */
    static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("The JRE has no SHA-256", e);
        }
    }

    private static JsonElement sorted(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject sorted = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : new TreeMap<>(element.getAsJsonObject().asMap()).entrySet()) {
                sorted.add(entry.getKey(), sorted(entry.getValue()));
            }
            return sorted;
        }
        if (element.isJsonArray()) {
            JsonArray sorted = new JsonArray();
            for (JsonElement entry : element.getAsJsonArray()) {
                sorted.add(sorted(entry));
            }
            return sorted;
        }
        return element;
    }
}
