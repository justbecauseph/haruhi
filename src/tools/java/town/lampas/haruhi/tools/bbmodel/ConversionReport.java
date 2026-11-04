package town.lampas.haruhi.tools.bbmodel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a conversion produced and what it could not carry over. Warnings with the same message are grouped, with the
 * details (element names, animation names) listed under them, so a model with 40 bezier keyframes gives one warning.
 */
public final class ConversionReport {
    private static final int DETAILS_SHOWN = 8;

    String modelFormat = "";
    String formatVersion = "";
    String geometryFormatVersion = "";
    int bones;
    int cubes;
    final Map<String, Integer> skippedElements = new LinkedHashMap<>();
    final List<String> texturesUsed = new ArrayList<>();
    final List<String> texturesIgnored = new ArrayList<>();
    String textureLayout = "";
    int textureWidth;
    int textureHeight;
    final List<String> animationsWritten = new ArrayList<>();
    private final Map<String, List<String>> warnings = new LinkedHashMap<>();

    void warn(String message) {
        warnings.computeIfAbsent(message, key -> new ArrayList<>());
    }

    void warn(String message, String detail) {
        List<String> details = warnings.computeIfAbsent(message, key -> new ArrayList<>());
        if (!details.contains(detail)) {
            details.add(detail);
        }
    }

    void skipped(String what) {
        skippedElements.merge(what, 1, Integer::sum);
    }

    public int bones() {
        return bones;
    }

    public int cubes() {
        return cubes;
    }

    /** Skipped elements by kind, such as {@code mesh} or {@code cube (export: false)}. */
    public Map<String, Integer> skippedElements() {
        return Map.copyOf(skippedElements);
    }

    public List<String> texturesUsed() {
        return List.copyOf(texturesUsed);
    }

    public List<String> texturesIgnored() {
        return List.copyOf(texturesIgnored);
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    public List<String> animationsWritten() {
        return List.copyOf(animationsWritten);
    }

    /** Each warning message with its details, in the order first raised. */
    public Map<String, List<String>> warnings() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        warnings.forEach((message, details) -> copy.put(message, List.copyOf(details)));
        return copy;
    }

    /** True if some warning message contains {@code text}. */
    public boolean hasWarning(String text) {
        return warnings.keySet().stream().anyMatch(message -> message.contains(text));
    }

    /** The summary the CLI prints. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("  source:      bbmodel format ").append(formatVersion).append(", model_format ")
                .append(modelFormat.isEmpty() ? "(none)" : modelFormat).append('\n');
        out.append("  geometry:    format_version ").append(geometryFormatVersion).append('\n');
        out.append("  bones:       ").append(bones).append('\n');
        out.append("  cubes:       ").append(cubes).append('\n');
        out.append("  skipped:     ");
        if (skippedElements.isEmpty()) {
            out.append("none");
        } else {
            List<String> parts = new ArrayList<>();
            skippedElements.forEach((what, count) -> parts.add(count + " " + what));
            out.append(String.join(", ", parts));
        }
        out.append('\n');
        out.append("  textures:    used ").append(texturesUsed.isEmpty() ? "none" : String.join(", ", texturesUsed))
                .append('\n');
        out.append("               ignored ")
                .append(texturesIgnored.isEmpty() ? "none" : String.join(", ", texturesIgnored)).append('\n');
        out.append("  texture:     ").append(textureWidth).append('x').append(textureHeight).append(' ')
                .append(textureLayout).append('\n');
        out.append("  animations:  ").append(animationsWritten.size());
        if (!animationsWritten.isEmpty()) {
            out.append(" (").append(String.join(", ", animationsWritten)).append(')');
        }
        out.append('\n');
        out.append("  warnings:    ").append(warnings.size()).append('\n');
        warnings.forEach((message, details) -> {
            out.append("    - ").append(message);
            if (!details.isEmpty()) {
                List<String> shown = details.subList(0, Math.min(DETAILS_SHOWN, details.size()));
                out.append(": ").append(String.join(", ", shown));
                if (details.size() > shown.size()) {
                    out.append(", and ").append(details.size() - shown.size()).append(" more");
                }
            }
            out.append('\n');
        });
        return out.toString();
    }
}
