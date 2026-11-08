package town.lampas.haruhi.content.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code config/haruhi-content.json}, owned by the content component: whether the server fetches bundles from the
 * Portal for the {@code haruhi:content/validate} and {@code haruhi:content/import} management methods, from where, and
 * how large a bundle it takes.
 *
 * <pre>{@code
 * {
 *   "remote": {
 *     "enabled": false,
 *     "origin": "https://dev.lampas.town",
 *     "max_bytes": 4194304
 *   }
 * }
 * }</pre>
 *
 * <p>The origin is a scheme, a host and an optional port, nothing else. It must be {@code https}, except that
 * {@code http} is allowed for a loopback host ({@code localhost}, {@code 127.0.0.1}, {@code [::1]}), for a Portal on
 * the same machine. The bundle URL is built from it and the request id, never taken from a call.
 *
 * @param enabled whether validate and import fetch; when false they answer {@code remote_disabled}
 * @param origin the Portal origin, without a trailing slash; present whenever {@code enabled} is
 * @param maxBytes the largest bundle body taken, in bytes
 */
public record RemoteContentConfig(boolean enabled, Optional<String> origin, long maxBytes) {
    public static final String FILE = "haruhi-content.json";
    public static final long DEFAULT_MAX_BYTES = 4L * 1024 * 1024;
    public static final long MAX_MAX_BYTES = 64L * 1024 * 1024;
    public static final RemoteContentConfig DISABLED = new RemoteContentConfig(false, Optional.empty(), DEFAULT_MAX_BYTES);

    private static final Set<String> KEYS = Set.of("enabled", "origin", "max_bytes");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final String DEFAULT_JSON = """
            {
              "remote": {
                "enabled": false,
                "origin": "",
                "max_bytes": 4194304
              }
            }
            """;

    /** {@code <origin>/api/v1/haruhi/requests/<request>/bundle}, the Portal's bundle route for one request. */
    public URI bundleUri(UUID request) {
        return URI.create(origin.orElseThrow(() -> new IllegalStateException("No remote origin is configured"))
                + "/api/v1/haruhi/requests/" + request + "/bundle");
    }

    /**
     * Reads the file. When it does not exist, writes the defaults there first and returns {@link #DISABLED}.
     *
     * @throws IllegalStateException when the file cannot be read or written, or does not hold a valid configuration
     */
    public static RemoteContentConfig loadOrCreate(Path file) {
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8);
                return DISABLED;
            }
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Haruhi cannot read or write " + file, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Haruhi content configuration " + file + " is invalid: " + e.getMessage(), e);
        }
    }

    /**
     * Parses the file's text. Missing keys take their defaults; unknown keys are refused, so a misspelt key cannot
     * fall back to a default.
     *
     * @throws IllegalArgumentException naming the first problem found
     */
    public static RemoteContentConfig parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("not valid JSON", e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("the file must hold a JSON object");
        }
        for (String key : root.getAsJsonObject().keySet()) {
            if (!key.equals("remote")) {
                throw new IllegalArgumentException("unknown top-level key \"" + key + "\"; the only key is \"remote\"");
            }
        }
        JsonElement section = root.getAsJsonObject().get("remote");
        if (section == null) {
            return DISABLED;
        }
        if (!section.isJsonObject()) {
            throw new IllegalArgumentException("\"remote\" must be an object");
        }
        JsonObject remote = section.getAsJsonObject();
        for (String key : remote.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown key remote." + key);
            }
        }
        boolean enabled = bool(remote, "enabled", false);
        String originText = text(remote, "origin", "");
        long maxBytes = whole(remote, "max_bytes", DEFAULT_MAX_BYTES);
        if (maxBytes < 1 || maxBytes > MAX_MAX_BYTES) {
            throw new IllegalArgumentException("remote.max_bytes must be from 1 to " + MAX_MAX_BYTES + ", found "
                    + maxBytes);
        }
        Optional<String> origin = originText.isEmpty() ? Optional.empty() : Optional.of(origin(originText));
        if (enabled && origin.isEmpty()) {
            throw new IllegalArgumentException("remote.origin must be set when remote.enabled is true");
        }
        return new RemoteContentConfig(enabled, origin, maxBytes);
    }

    /** The origin in its normal form: lowercase scheme and host, the port when one is given, no trailing slash. */
    static String origin(String text) {
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("remote.origin is not a URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.isOpaque() || host.isEmpty()) {
            throw new IllegalArgumentException("remote.origin must be an absolute URL with a host, such as "
                    + "https://dev.lampas.town");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("remote.origin must be a scheme, a host and an optional port, with no "
                    + "user, path, query or fragment");
        }
        if (!scheme.equals("https") && !(scheme.equals("http") && LOOPBACK.contains(host))) {
            throw new IllegalArgumentException("remote.origin must use https; http is allowed only for localhost, "
                    + "127.0.0.1 and [::1]");
        }
        return scheme + "://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("remote." + key + " must be true or false");
        }
        return element.getAsBoolean();
    }

    private static String text(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("remote." + key + " must be a string");
        }
        return element.getAsString();
    }

    private static long whole(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("remote." + key + " must be a whole number");
        }
        // Parse the literal text, which refuses 2.5 and 1e3 instead of truncating them.
        try {
            return Long.parseLong(element.getAsString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("remote." + key + " must be a whole number", e);
        }
    }
}
