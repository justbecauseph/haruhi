package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A .bbmodel file read into its outliner tree, the way Blockbench's project codec (formats/bbmodel.js) loads it. Groups
 * come from the inline outliner objects of files before 5.0 or from the top-level {@code groups} array of 5.0 files.
 */
final class BbProject {
    /** Formats whose faces all draw with the one default texture (ModelFormat {@code single_texture: true}). */
    private static final Set<String> SINGLE_TEXTURE = Set.of("bedrock", "bedrock_old", "image", "modded_entity", "skin");
    /**
     * Formats without {@code per_texture_uv_size}: their face UVs are in the project resolution, whatever a texture's own
     * uv_width says. Every other built-in format and the plugin formats (figura, ...) are read per texture.
     */
    private static final Set<String> PROJECT_UV_SIZE =
            Set.of("bedrock", "bedrock_block", "bedrock_old", "java_block", "modded_entity", "skin");
    /** Formats without {@code centered_grid}: their origin is a block corner, 8 units off the entity origin in X and Z. */
    private static final Set<String> CORNER_GRID = Set.of("java_block", "image");
    /**
     * Formats that set {@code animated_textures} in their ModelFormat options (js/formats: free, java_block, bedrock,
     * bedrock_block, bedrock_old, image). Blockbench's Format.animated_textures gates a texture's frame count, so
     * modded_entity and skin keep a flipbook texture whole. Plugin formats are not listed and count as not animated.
     */
    private static final Set<String> ANIMATED_TEXTURES =
            Set.of("free", "java_block", "bedrock", "bedrock_block", "bedrock_old", "image");

    final String formatVersion;
    final String modelFormat;
    /** meta.box_uv, Blockbench's Project.box_uv: the default UV mode of cubes and the condition for bone mirroring. */
    final boolean projectBoxUv;
    final double resolutionWidth;
    final double resolutionHeight;
    final List<JsonObject> textures = new ArrayList<>();
    final Map<String, Element> elements = new LinkedHashMap<>();
    final List<Node> root = new ArrayList<>();
    final List<JsonObject> animations = new ArrayList<>();
    /** The stored visible box [width, height, y offset], ModelProject's {@code visible_box} with default [1, 1, 0]. */
    final double[] visibleBox;
    final boolean singleTexture;
    final boolean perTextureUvSize;
    final boolean centeredGrid;
    /** modded_entity sets {@code box_uv_float_size}; elsewhere box UV floors the cube size. */
    final boolean boxUvFloatSize;
    /** Format.animated_textures: a texture taller than its UV aspect is a flipbook, and its first frame is used. */
    final boolean animatedTextures;
    /** processCompatibility: before format 3.2 the Z rotation of every group is negated on load. */
    private final boolean zRotationFlipped;

    sealed interface Node permits Group, Element {
        Group parent();

        boolean export();
    }

    static final class Group implements Node {
        final String uuid;
        String name;
        final double[] origin;
        final double[] rotation;
        final boolean export;
        final boolean mirrorUv;
        final String bedrockBinding;
        Group parent;
        final List<Node> children = new ArrayList<>();

        Group(String uuid, String name, double[] origin, double[] rotation, boolean export, boolean mirrorUv,
              String bedrockBinding) {
            this.uuid = uuid;
            this.name = name;
            this.origin = origin;
            this.rotation = rotation;
            this.export = export;
            this.mirrorUv = mirrorUv;
            this.bedrockBinding = bedrockBinding;
        }

        @Override
        public Group parent() {
            return parent;
        }

        @Override
        public boolean export() {
            return export;
        }
    }

    static final class Element implements Node {
        final String uuid;
        final String type;
        final String name;
        final JsonObject json;
        final boolean export;
        /** The cube's mirror_uv after processCompatibility, which also sets it for shade false in old box UV projects. */
        final boolean mirrorUv;
        Group parent;
        /** Set when the element is in the outliner under another element (an armature), which the converter skips. */
        boolean underElement;

        Element(String uuid, String type, String name, JsonObject json, boolean mirrorUv) {
            this.uuid = uuid;
            this.type = type;
            this.name = name;
            this.json = json;
            this.export = !json.has("export") || json.get("export").getAsBoolean();
            this.mirrorUv = mirrorUv;
        }

        @Override
        public Group parent() {
            return parent;
        }

        @Override
        public boolean export() {
            return export;
        }
    }

    BbProject(JsonObject model, ConversionReport report) {
        JsonObject meta = model.has("meta") && model.get("meta").isJsonObject() ? model.getAsJsonObject("meta") : null;
        if (meta == null) {
            throw new IllegalArgumentException("not a Blockbench project: the file has no \"meta\" object");
        }
        // processHeader: format_version falls back to the legacy meta.format.
        String version = string(meta, "format_version", string(meta, "format", "0"));
        this.formatVersion = version;
        // processCompatibility: a file without model_format is bedrock_old when it has a bone rig, else java_block.
        String format = string(meta, "model_format", "");
        if (format.isEmpty()) {
            format = bool(meta, "bone_rig", false) ? "bedrock_old" : "java_block";
        }
        this.modelFormat = format;
        this.projectBoxUv = bool(meta, "box_uv", false);
        JsonObject resolution = model.has("resolution") ? model.getAsJsonObject("resolution") : new JsonObject();
        this.resolutionWidth = number(resolution, "width", 16);
        this.resolutionHeight = number(resolution, "height", 16);
        this.singleTexture = SINGLE_TEXTURE.contains(format);
        this.perTextureUvSize = !PROJECT_UV_SIZE.contains(format);
        this.centeredGrid = !CORNER_GRID.contains(format);
        this.boxUvFloatSize = format.equals("modded_entity");
        this.animatedTextures = ANIMATED_TEXTURES.contains(format);
        this.zRotationFlipped = compareVersions(version, "3.2") < 0;
        this.visibleBox = model.has("visible_box") ? vector(model.getAsJsonArray("visible_box"), 3, new double[] {1, 1, 0})
                : new double[] {1, 1, 0};
        if (!centeredGrid) {
            report.warn("model_format " + format + " has its origin at a block corner; moved by -8 in X and Z, "
                    + "as Blockbench does when converting to an entity format");
        }

        if (model.has("textures")) {
            for (JsonElement texture : model.getAsJsonArray("textures")) {
                textures.add(texture.getAsJsonObject());
            }
        }
        // processCompatibility: very old files call the elements "cubes".
        JsonArray elementArray = model.has("elements") ? model.getAsJsonArray("elements")
                : model.has("cubes") ? model.getAsJsonArray("cubes") : new JsonArray();
        // "if (model.elements && model.meta.box_uv && VersionUtil.compare(model.meta.format_version, '<', '4.5')) {
        //      model.elements.forEach(element => { if (element.shade === false) { element.mirror_uv = true; } }) }"
        boolean shadeMirrorsUv = projectBoxUv && compareVersions(version, "4.5") < 0;
        for (JsonElement entry : elementArray) {
            JsonObject json = entry.getAsJsonObject();
            String uuid = string(json, "uuid", "element-" + elements.size());
            String type = string(json, "type", "cube");
            boolean mirrorUv = bool(json, "mirror_uv", false) || (shadeMirrorsUv && !bool(json, "shade", true));
            elements.put(uuid, new Element(uuid, type, string(json, "name", type), json, mirrorUv));
        }
        Map<String, JsonObject> groupData = new LinkedHashMap<>();
        if (model.has("groups")) {
            for (JsonElement entry : model.getAsJsonArray("groups")) {
                JsonObject json = entry.getAsJsonObject();
                groupData.put(string(json, "uuid", "group-" + groupData.size()), json);
            }
        }
        Set<String> placed = new HashSet<>();
        if (model.has("outliner")) {
            readOutliner(model.getAsJsonArray("outliner"), null, null, groupData, placed, report);
        }
        // Outliner.loadJSON: "Existing element missing in outliner. Adding to root instead."
        for (Element element : elements.values()) {
            if (!placed.contains(element.uuid)) {
                root.add(element);
                report.warn("element not in the outliner, added at the root as Blockbench does", element.name);
            }
        }
        if (model.has("animations")) {
            for (JsonElement animation : model.getAsJsonArray("animations")) {
                animations.add(animation.getAsJsonObject());
            }
        }
    }

    /** bbmodel.js processCompatibility: files before 5.0 store keyframes with Bedrock's signs. */
    boolean keyframesUseBedrockSigns() {
        return compareVersions(formatVersion, "5.0") < 0;
    }

    private void readOutliner(JsonArray items, Group parent, Element parentElement, Map<String, JsonObject> groupData,
                              Set<String> placed, ConversionReport report) {
        for (JsonElement item : items) {
            if (item.isJsonPrimitive()) {
                Element element = elements.get(item.getAsString());
                if (element == null || !placed.add(element.uuid)) {
                    continue;
                }
                element.parent = parent;
                element.underElement = parentElement != null;
                if (parent == null) {
                    if (parentElement == null) {
                        root.add(element);
                    }
                } else {
                    parent.children.add(element);
                }
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String uuid = string(object, "uuid", "");
            JsonArray children = object.has("children") ? object.getAsJsonArray("children")
                    : object.has("content") ? object.getAsJsonArray("content") : new JsonArray();
            Element asElement = elements.get(uuid);
            if (asElement != null && !object.has("name")) {
                // An element with children in the outliner (an armature). Its subtree is not a bone hierarchy.
                if (placed.add(uuid)) {
                    asElement.parent = parent;
                    asElement.underElement = parentElement != null;
                    if (parent == null && parentElement == null) {
                        root.add(asElement);
                    } else if (parent != null) {
                        parent.children.add(asElement);
                    }
                }
                readOutliner(children, null, asElement, groupData, placed, report);
                continue;
            }
            // Legacy inline groups carry their own data; 5.0 groups are in the groups array.
            JsonObject data = object.has("name") ? object : groupData.get(uuid);
            if (data == null) {
                report.warn("outliner entry names no group or element; skipped", uuid);
                continue;
            }
            if (!placed.add(uuid.isEmpty() ? "group-" + System.identityHashCode(object) : uuid)) {
                continue;
            }
            Group group = group(uuid, data);
            group.parent = parent;
            if (parentElement != null) {
                report.warn("group inside an element (armature) skipped with its contents", group.name);
            } else if (parent == null) {
                root.add(group);
            } else {
                parent.children.add(group);
            }
            readOutliner(children, parentElement != null ? null : group, parentElement, groupData, placed, report);
        }
    }

    private Group group(String uuid, JsonObject data) {
        // Group: origin defaults to [0, 0, 0] on a centered grid, else [8, 8, 8].
        double[] origin = data.has("origin") ? vector(data.getAsJsonArray("origin"), 3, null)
                : centeredGrid ? new double[] {0, 0, 0} : new double[] {8, 8, 8};
        double[] rotation = new double[3];
        if (data.has("rotation")) {
            rotation = vector(data.getAsJsonArray("rotation"), 3, null);
            // processCompatibility: "//Fix Z-axis inversion pre 3.2" ... "if (child.rotation) child.rotation[2] *= -1"
            if (zRotationFlipped) {
                rotation[2] *= -1;
            }
        }
        if (!centeredGrid) {
            origin[0] -= 8;
            origin[2] -= 8;
        }
        return new Group(uuid, string(data, "name", "group"), origin, rotation, bool(data, "export", true),
                bool(data, "mirror_uv", false), string(data, "bedrock_binding", ""));
    }

    /** Cube corner or pivot, moved onto the entity origin for corner-grid formats (ModelFormat.convertTo). */
    double[] cubeVector(JsonObject json, String key, double[] fallback) {
        double[] value = json.has(key) && json.get(key).isJsonArray() ? vector(json.getAsJsonArray(key), 3, fallback)
                : fallback.clone();
        if (!centeredGrid) {
            value[0] -= 8;
            value[2] -= 8;
        }
        return value;
    }

    /** A cube's "from" corner. */
    double[] cubeFrom(JsonObject json) {
        return cubeVector(json, "from", new double[3]);
    }

    /** A cube's "to" corner, or from + "size" in legacy files (Cube.extend). */
    double[] cubeTo(JsonObject json) {
        double[] from = cubeFrom(json);
        if (json.has("size") && json.get("size").isJsonArray()) {
            double[] size = vector(json.getAsJsonArray("size"), 3, null);
            return new double[] {from[0] + size[0], from[1] + size[1], from[2] + size[2]};
        }
        if (json.has("to") && json.get("to").isJsonArray()) {
            return cubeVector(json, "to", new double[3]);
        }
        return from;
    }

    static double[] vector(JsonArray array, int size, double[] fallback) {
        double[] out = fallback == null ? new double[size] : fallback.clone();
        for (int i = 0; i < size && i < array.size(); i++) {
            JsonElement value = array.get(i);
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                out[i] = value.getAsDouble();
            } else if (value.isJsonPrimitive()) {
                try {
                    out[i] = Double.parseDouble(value.getAsString());
                } catch (NumberFormatException ignored) {
                    // Merge.number keeps the default for a value that is not a number.
                }
            }
        }
        return out;
    }

    static String string(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean()
                : fallback;
    }

    static double number(JsonObject json, String key, double fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsDouble()
                : fallback;
    }

    /** util/version_util.ts compare: dotted numeric parts, a missing part counts as 0. */
    static int compareVersions(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int l = i < left.length ? parseIntOrZero(left[i]) : 0;
            int r = i < right.length ? parseIntOrZero(right[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private static int parseIntOrZero(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
