package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import town.lampas.haruhi.tools.bbmodel.BbProject.Element;
import town.lampas.haruhi.tools.bbmodel.BbProject.Group;
import town.lampas.haruhi.tools.bbmodel.BbProject.Node;

/**
 * The Bedrock geometry ({@code minecraft:geometry}) of a project, following Blockbench's Bedrock codec
 * (js/formats/bedrock/bedrock.js {@code compile}, {@code compileGroup}, {@code compileCube}), which is the export GeckoLib
 * reads. Quoted lines below are from that file unless another is named.
 */
final class GeometryCompiler {
    static final String[] FACES = {"north", "east", "south", "west", "up", "down"};
    private static final int NOT_DRAWN = -1;

    /** The geometry, the atlas its UVs point into, and the bone name each group became. */
    record Result(JsonObject geometry, TextureAtlas atlas, Map<String, String> boneNames, Set<String> exportedBones) {}

    private final BbProject project;
    private final ConversionReport report;
    private final List<Group> groups = new ArrayList<>();
    private final Map<Group, String> names = new LinkedHashMap<>();
    private final Map<Element, int[]> faceTextures = new LinkedHashMap<>();
    private boolean faceRotation;
    private boolean binding;

    private GeometryCompiler(BbProject project, ConversionReport report) {
        this.project = project;
        this.report = report;
    }

    static Result compile(BbProject project, String name, Path sourceDirectory, ConversionReport report)
            throws IOException {
        return new GeometryCompiler(project, report).run(name, sourceDirectory);
    }

    private Result run(String name, Path sourceDirectory) throws IOException {
        collectGroups();
        nameBones();
        List<Element> cubes = collectCubes();
        SortedSet<Integer> used = new TreeSet<>();
        int defaultTexture = defaultTexture();
        for (Element cube : cubes) {
            int[] textures = new int[FACES.length];
            JsonObject faces = cube.json.has("faces") ? cube.json.getAsJsonObject("faces") : new JsonObject();
            for (int i = 0; i < FACES.length; i++) {
                textures[i] = faceTexture(cube, faces, FACES[i], defaultTexture);
                if (textures[i] != NOT_DRAWN) {
                    used.add(textures[i]);
                }
            }
            faceTextures.put(cube, textures);
        }
        TextureAtlas atlas = TextureAtlas.build(project, used, sourceDirectory, report);
        report.textureWidth = atlas.width();
        report.textureHeight = atlas.height();

        JsonArray bones = new JsonArray();
        VisibleBounds bounds = new VisibleBounds();
        for (Group group : groups) {
            bones.add(compileGroup(group, atlas, bounds));
        }
        report.bones = bones.size();

        JsonObject description = new JsonObject();
        // "identifier: 'geometry.' + (... Project.geometry_name || 'unknown')"
        description.addProperty("identifier", "geometry." + name.replace('/', '.'));
        description.addProperty("texture_width", atlas.width());
        description.addProperty("texture_height", atlas.height());
        double[] visible = bounds.visibleBox(project.visibleBox);
        description.addProperty("visible_bounds_width", visible[0]);
        description.addProperty("visible_bounds_height", visible[1]);
        JsonArray offset = new JsonArray();
        offset.add(0);
        offset.add(visible[2]);
        offset.add(0);
        description.add("visible_bounds_offset", offset);

        JsonObject model = new JsonObject();
        model.add("description", description);
        model.add("bones", bones);
        JsonArray geometries = new JsonArray();
        geometries.add(model);
        JsonObject root = new JsonObject();
        // getFormatVersion: '1.21.0' when a per-face cube has a face rotation, '1.16.0' with a bone binding, else '1.12.0'.
        String version = faceRotation ? "1.21.0" : binding ? "1.16.0" : "1.12.0";
        root.addProperty("format_version", version);
        root.add("minecraft:geometry", geometries);
        report.geometryFormatVersion = version;

        Map<String, String> boneNames = new LinkedHashMap<>();
        Set<String> exported = new HashSet<>();
        names.forEach((group, bone) -> {
            if (group.uuid != null) {
                boneNames.put(group.uuid, bone);
            }
            exported.add(bone);
        });
        return new Result(root, atlas, boneNames, exported);
    }

    /**
     * The bones in outliner order (getAllGroups walks the outliner depth first). "if (loose_elements.length) { let
     * group = new Group({name: 'bb_main'}); ... groups.splice(0, 0, group); }": elements at the root go in a first bone.
     */
    private void collectGroups() {
        List<Node> loose = new ArrayList<>();
        for (Node node : project.root) {
            if (node instanceof Element element && !element.underElement) {
                loose.add(element);
            }
        }
        if (!loose.isEmpty()) {
            Group main = new Group(null, "bb_main", new double[3], new double[3], true, false, "");
            main.children.addAll(loose);
            groups.add(main);
        }
        for (Node node : project.root) {
            if (node instanceof Group group) {
                walk(group);
            }
        }
        // Elements under an armature are not in any bone.
        for (Element element : project.elements.values()) {
            if (element.underElement) {
                skip(element, "inside an armature");
            }
        }
    }

    private void walk(Group group) {
        if (exportsGroup(group)) {
            groups.add(group);
        } else {
            countUnexported(group);
        }
        for (Node child : group.children) {
            if (child instanceof Group inner) {
                walk(inner);
            }
        }
    }

    /**
     * compileGroup's skip rules: "if (g.export == false) { ... if (!hasChildrenToExport(g)) return false; }", and "if
     * (g.children.length && g.children.allAre(c => c instanceof BoundingBox)) return;". Empty groups are exported
     * (the export_empty_groups setting defaults to true).
     */
    private static boolean exportsGroup(Group group) {
        if (!group.export && !hasChildrenToExport(group)) {
            return false;
        }
        return group.children.isEmpty() || !group.children.stream()
                .allMatch(child -> child instanceof Element element && element.type.equals("bounding_box"));
    }

    private static boolean hasChildrenToExport(Group group) {
        for (Node child : group.children) {
            if (child.export()) {
                return true;
            }
            if (child instanceof Group inner && hasChildrenToExport(inner)) {
                return true;
            }
        }
        return false;
    }

    private void countUnexported(Group group) {
        for (Node child : group.children) {
            if (child instanceof Element element) {
                skip(element, element.export ? "in a group that is not exported" : "export: false");
            }
        }
    }

    /**
     * Bone names: Bedrock names allow [\w.-] (the format's node_name_regex, applied by OutlinerNode.sanitizeName), and a
     * name must be unique, compared case-insensitively. OutlinerNode.createUniqueName makes a duplicate unique by
     * replacing its trailing digits with the first free number from 2 ("from 1 when the name ends in a lone 0"). Unlike
     * Blockbench, which renames whichever group it checks first, the first bone in outliner order keeps its name.
     */
    private void nameBones() {
        Set<String> taken = new HashSet<>();
        for (Group group : groups) {
            String original = group.name;
            String sanitized = original.replaceAll("[^\\w.-]", "");
            if (sanitized.isEmpty()) {
                sanitized = "bone";
            }
            if (!sanitized.equals(original)) {
                report.warn("bone name has characters Bedrock names do not allow; they were removed",
                        "\"" + original + "\" -> " + sanitized);
            }
            String unique = sanitized;
            if (taken.contains(unique.toLowerCase(Locale.ROOT))) {
                boolean zeroBased = unique.matches(".*[^\\d]0$");
                String base = unique.replaceAll("\\d+$", "").replaceAll("\\s+", "_");
                for (int number = zeroBased ? 1 : 2; number < 8000; number++) {
                    String candidate = base + number;
                    if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                        unique = candidate;
                        break;
                    }
                }
                report.warn("duplicate bone name renamed, Bedrock bone names must be unique", sanitized + " -> " + unique);
            }
            taken.add(unique.toLowerCase(Locale.ROOT));
            names.put(group, unique);
        }
    }

    /** The exported cubes of the exported bones; every other element is counted as skipped. */
    private List<Element> collectCubes() {
        List<Element> cubes = new ArrayList<>();
        for (Group group : groups) {
            for (Node child : group.children) {
                if (!(child instanceof Element element)) {
                    continue;
                }
                if (!element.export) {
                    skip(element, "export: false");
                } else if (element.type.equals("cube")) {
                    cubes.add(element);
                } else {
                    skip(element, null);
                }
            }
        }
        return cubes;
    }

    private void skip(Element element, String reason) {
        String kind = reason == null ? element.type : element.type + " (" + reason + ")";
        report.skipped(kind);
        if (reason == null) {
            String why = switch (element.type) {
                case "mesh" -> "mesh elements skipped: GeckoLib cannot draw meshes";
                case "locator", "null_object" -> element.type + " elements skipped: the converter writes cubes only";
                default -> element.type + " elements skipped: GeckoLib geometry here is cubes only";
            };
            report.warn(why, element.name);
        } else if (!reason.equals("export: false")) {
            report.warn(element.type + " elements skipped (" + reason + ")", element.name);
        }
    }

    /**
     * Texture.getDefault, as far as a file can tell. Blockbench reads use_as_default only in a format that sets
     * single_texture_default, which among the built-in formats is bedrock_block alone: "if
     * (Format.single_texture_default) { let default_enabled = Texture.all.find(tex => tex.use_as_default); if
     * (default_enabled) return default_enabled; }". Otherwise it returns the selected texture, which a file does not
     * record, and after a fresh load that is the first texture, so the first is taken here.
     */
    private int defaultTexture() {
        if (SINGLE_TEXTURE_DEFAULT_FORMAT.equals(project.modelFormat)) {
            for (int i = 0; i < project.textures.size(); i++) {
                if (BbProject.bool(project.textures.get(i), "use_as_default", false)) {
                    return i;
                }
            }
        }
        return project.textures.isEmpty() ? NOT_DRAWN : 0;
    }

    /** The one built-in Blockbench format that sets single_texture_default. */
    private static final String SINGLE_TEXTURE_DEFAULT_FORMAT = "bedrock_block";

    /**
     * The texture a face draws with, or NOT_DRAWN. A bbmodel face stores its texture as an index into the textures array
     * (Face.getSaveCopy), {@code null} for a face that is not drawn, or nothing for a face without a texture. compileCube
     * exports every face whose "face.texture !== null"; in the single-texture Bedrock format such a face draws with the
     * default texture, so a face without a texture draws with it here too.
     */
    private int faceTexture(Element cube, JsonObject faces, String key, int defaultTexture) {
        JsonElement face = faces.get(key);
        if (face == null || !face.isJsonObject()) {
            report.warn("cube has no data for a face; the face is not drawn", cube.name + " " + key);
            return NOT_DRAWN;
        }
        JsonElement texture = face.getAsJsonObject().get("texture");
        if (texture != null && texture.isJsonNull()) {
            return NOT_DRAWN;
        }
        int index = NOT_DRAWN;
        if (texture != null && texture.isJsonPrimitive()) {
            if (texture.getAsJsonPrimitive().isNumber()) {
                index = texture.getAsInt();
            } else if (texture.getAsJsonPrimitive().isString()) {
                index = textureByUuid(texture.getAsString());
            }
        }
        // bbmodel.js load: in a single-texture format every face that is not null gets the default texture.
        if (project.singleTexture && defaultTexture != NOT_DRAWN) {
            return defaultTexture;
        }
        if (index >= 0 && index < project.textures.size()) {
            return index;
        }
        if (defaultTexture == NOT_DRAWN) {
            report.warn("face has no texture and the model has none; the face is not drawn", cube.name + " " + key);
            return NOT_DRAWN;
        }
        report.warn("face without a texture drawn with the default texture, as Blockbench's Bedrock export does",
                cube.name + " " + key);
        return defaultTexture;
    }

    private int textureByUuid(String reference) {
        for (int i = 0; i < project.textures.size(); i++) {
            if (reference.equals(BbProject.string(project.textures.get(i), "uuid", ""))) {
                return i;
            }
        }
        try {
            return Integer.parseInt(reference);
        } catch (NumberFormatException e) {
            return NOT_DRAWN;
        }
    }

    private JsonObject compileGroup(Group group, TextureAtlas atlas, VisibleBounds bounds) {
        JsonObject bone = new JsonObject();
        bone.addProperty("name", names.get(group));
        // "if (g.parent.type === 'group') bone.parent = g.parent.name"
        if (group.parent != null && names.containsKey(group.parent)) {
            bone.addProperty("parent", names.get(group.parent));
        }
        // "bone.pivot = g.origin.slice(); bone.pivot[0] *= -1"
        bone.add("pivot", array(-group.origin[0], group.origin[1], group.origin[2]));
        // "if (!g.rotation.allEqual(0)) { bone.rotation = g.rotation.slice(); bone.rotation[0] *= -1; bone.rotation[1] *= -1; }"
        if (!allZero(group.rotation)) {
            bone.add("rotation", array(-group.rotation[0], -group.rotation[1], group.rotation[2]));
        }
        if (!group.bedrockBinding.isEmpty()) {
            bone.addProperty("binding", group.bedrockBinding);
            binding = true;
            report.warn("bone binding written as Blockbench does; GeckoLib 5.5.5 reads it but does not apply it",
                    names.get(group));
        }
        // "if (g.mirror_uv && Project.box_uv) bone.mirror = true"
        boolean mirror = group.mirrorUv && project.projectBoxUv;
        if (mirror) {
            bone.addProperty("mirror", true);
        }
        JsonArray cubes = new JsonArray();
        for (Node child : group.children) {
            if (child instanceof Element element && faceTextures.containsKey(element)) {
                JsonObject cube = compileCube(element, mirror, atlas);
                if (cube != null) {
                    cubes.add(cube);
                    bounds.add(project, element, group.uuid == null ? null : group);
                }
            }
        }
        if (!cubes.isEmpty()) {
            bone.add("cubes", cubes);
        }
        report.cubes += cubes.size();
        return bone;
    }

    private JsonObject compileCube(Element element, boolean boneMirror, TextureAtlas atlas) {
        JsonObject json = element.json;
        int[] textures = faceTextures.get(element);
        boolean anyFace = false;
        for (int texture : textures) {
            anyFace |= texture != NOT_DRAWN;
        }
        if (!anyFace) {
            skip(element, "no face is drawn");
            return null;
        }
        double[] from = project.cubeFrom(json);
        double[] to = project.cubeTo(json);
        double[] size = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
        double[] pivot = project.cubeVector(json, "origin", new double[3]);
        double[] rotation = rotation(json);
        double inflate = BbProject.number(json, "inflate", 0);

        JsonObject cube = new JsonObject();
        // "origin: cube.from.slice(), size: cube.size(), inflate: cube.inflate||undefined"
        // "template.origin[0] = -(template.origin[0] + template.size[0])"
        cube.add("origin", array(-(from[0] + size[0]), from[1], from[2]));
        cube.add("size", array(size[0], size[1], size[2]));
        if (inflate != 0) {
            cube.addProperty("inflate", inflate);
        }
        // "if (!cube.rotation.allEqual(0)) { template.pivot = cube.origin.slice(); template.pivot[0] *= -1;
        //  template.rotation = cube.rotation.slice(); ... if (axis != 2) template.rotation[axis] *= -1 }"
        if (!allZero(rotation)) {
            cube.add("pivot", array(-pivot[0], pivot[1], pivot[2]));
            cube.add("rotation", array(-rotation[0], -rotation[1], rotation[2]));
        }
        if (json.has("stretch") && !allOne(BbProject.vector(json.getAsJsonArray("stretch"), 3, new double[] {1, 1, 1}))) {
            report.warn("cube stretch is not a Bedrock property and was dropped", element.name);
        }

        boolean boxUv = BbProject.bool(json, "box_uv", project.projectBoxUv);
        // The file's mirror_uv after processCompatibility (BbProject.Element.mirrorUv).
        boolean mirrorUv = element.mirrorUv;
        if (boxUv) {
            String reason = boxUvProblem(textures, size, atlas);
            if (reason == null) {
                double[] uvOffset = json.has("uv_offset") ? BbProject.vector(json.getAsJsonArray("uv_offset"), 2, null)
                        : new double[2];
                TextureAtlas.Placement placement = atlas.placement(textures[0]);
                if (atlas.stitched()) {
                    // The six box faces are checked the way the per-face rectangles are below.
                    double[][] boxRects = boxUvRects(json, size, mirrorUv);
                    for (int i = 0; i < FACES.length; i++) {
                        warnIfOutside(element, FACES[i], boxRects[i], placement);
                    }
                }
                // "template.uv = cube.uv_offset; if (cube.mirror_uv === !bone.mirror) template.mirror = cube.mirror_uv"
                cube.add("uv", array(placement.u(uvOffset[0]), placement.v(uvOffset[1])));
                if (mirrorUv == !boneMirror) {
                    cube.addProperty("mirror", mirrorUv);
                }
                return cube;
            }
            report.warn("box UV cube written as per-face UV", element.name + " (" + reason + ")");
        }
        JsonObject uv = new JsonObject();
        double[][] rects = boxUv ? boxUvRects(json, size, mirrorUv) : faceRects(json);
        JsonObject faces = json.has("faces") ? json.getAsJsonObject("faces") : new JsonObject();
        for (int i = 0; i < FACES.length; i++) {
            if (textures[i] == NOT_DRAWN) {
                continue;
            }
            TextureAtlas.Placement placement = atlas.placement(textures[i]);
            double[] rect = rects[i];
            if (atlas.stitched()) {
                warnIfOutside(element, FACES[i], rect, placement);
            }
            double u = placement.u(rect[0]);
            double v = placement.v(rect[1]);
            double width = placement.u(rect[2]) - u;
            double height = placement.v(rect[3]) - v;
            // "if (key == 'up' || key == 'down') { uv[0] += uv_size[0]; uv[1] += uv_size[1]; uv_size[0] *= -1;
            //  uv_size[1] *= -1; }"
            if (FACES[i].equals("up") || FACES[i].equals("down")) {
                u += width;
                v += height;
                width = -width;
                height = -height;
            }
            JsonObject face = new JsonObject();
            face.add("uv", array(u, v));
            face.add("uv_size", array(width, height));
            JsonObject source = faces.has(FACES[i]) ? faces.getAsJsonObject(FACES[i]) : new JsonObject();
            // "if (face.rotation) template.uv[key].uv_rotation = face.rotation"; box UV faces have no rotation.
            int faceRotationDegrees = boxUv ? 0 : (int) BbProject.number(source, "rotation", 0);
            if (faceRotationDegrees != 0) {
                face.addProperty("uv_rotation", faceRotationDegrees);
                faceRotation = true;
            }
            String material = boxUv ? "" : BbProject.string(source, "material_name", "");
            if (!material.isEmpty()) {
                face.addProperty("material_instance", material);
            }
            uv.add(FACES[i], face);
        }
        cube.add("uv", uv);
        if (boneMirror) {
            // GeckoLib's GeometryCube.bake falls back to the bone's mirror flag and applies it to per-face UVs too.
            cube.addProperty("mirror", false);
        }
        return cube;
    }

    /**
     * Why a box UV cube cannot stay box UV in GeckoLib, or null. GeckoLib lays box UV out from {@code Mth.floor} of the
     * size (GeometryUvPair.bakeQuad) and always draws all six faces from one texture.
     */
    private String boxUvProblem(int[] textures, double[] size, TextureAtlas atlas) {
        for (int texture : textures) {
            if (texture == NOT_DRAWN) {
                return "some faces are not drawn";
            }
            if (texture != textures[0]) {
                return "its faces use different textures";
            }
        }
        if (!atlas.placement(textures[0]).unscaled()) {
            return "its texture is scaled in the atlas";
        }
        for (double s : size) {
            if (project.boxUvFloatSize ? s != Math.floor(s) : Math.floor(s + 1e-7) != Math.floor(s)) {
                return "its size is not whole";
            }
        }
        return null;
    }

    /** The per-face UV rectangles [u1, v1, u2, v2] in face order. */
    private static double[][] faceRects(JsonObject json) {
        double[][] rects = new double[FACES.length][];
        JsonObject faces = json.has("faces") ? json.getAsJsonObject("faces") : new JsonObject();
        for (int i = 0; i < FACES.length; i++) {
            JsonObject face = faces.has(FACES[i]) ? faces.getAsJsonObject(FACES[i]) : new JsonObject();
            rects[i] = face.has("uv") ? BbProject.vector(face.getAsJsonArray("uv"), 4, null) : new double[4];
        }
        return rects;
    }

    /**
     * Box UV unwrapped into per-face rectangles, as Blockbench draws it (cube.js preview controller updateUV): each face
     * at its place around uv_offset, from the floored size ({@code element.size(undefined, Format.box_uv_float_size !=
     * true)}). Mirroring flips each face horizontally and swaps east and west.
     */
    private double[][] boxUvRects(JsonObject json, double[] rawSize, boolean mirror) {
        double[] size = new double[3];
        for (int i = 0; i < 3; i++) {
            size[i] = project.boxUvFloatSize ? rawSize[i] : Math.floor(rawSize[i] + 0.0000001);
        }
        double[] offset = json.has("uv_offset") ? BbProject.vector(json.getAsJsonArray("uv_offset"), 2, null)
                : new double[2];
        double x = size[0];
        double y = size[1];
        double z = size[2];
        // {face, from: [u, v], size: [w, h]} in the order east, west, up, down, south, north.
        Map<String, double[]> layout = new LinkedHashMap<>();
        layout.put("east", new double[] {0, z, z, y});
        layout.put("west", new double[] {z + x, z, z, y});
        layout.put("up", new double[] {z + x, z, -x, -z});
        layout.put("down", new double[] {z + x * 2, 0, -x, z});
        layout.put("south", new double[] {z * 2 + x, z, x, y});
        layout.put("north", new double[] {z, z, x, y});
        if (mirror) {
            for (double[] face : layout.values()) {
                face[0] += face[2];
                face[2] *= -1;
            }
            double[] east = layout.get("east");
            layout.put("east", layout.get("west"));
            layout.put("west", east);
        }
        double[][] rects = new double[FACES.length][];
        for (int i = 0; i < FACES.length; i++) {
            double[] face = layout.get(FACES[i]);
            rects[i] = new double[] {face[0] + offset[0], face[1] + offset[1], face[0] + face[2] + offset[0],
                    face[1] + face[3] + offset[1]};
        }
        return rects;
    }

    /** A face rectangle that reaches past its texture's UV space would show part of a neighbouring texture in the atlas. */
    private void warnIfOutside(Element element, String face, double[] rect, TextureAtlas.Placement placement) {
        if (outside(rect, placement)) {
            report.warn("face UV reaches outside its texture, so in the atlas it shows part of another texture",
                    element.name + " " + face + " (" + placement.name() + ")");
        }
    }

    private static boolean outside(double[] rect, TextureAtlas.Placement placement) {
        double epsilon = 1e-6;
        return Math.min(rect[0], rect[2]) < -epsilon || Math.min(rect[1], rect[3]) < -epsilon
                || Math.max(rect[0], rect[2]) > placement.uvWidth() + epsilon
                || Math.max(rect[1], rect[3]) > placement.uvHeight() + epsilon;
    }

    /** The cube rotation; legacy files call it "rotated" (Cube.extend). */
    static double[] rotation(JsonObject json) {
        if (json.has("rotation") && json.get("rotation").isJsonArray()) {
            return BbProject.vector(json.getAsJsonArray("rotation"), 3, null);
        }
        if (json.has("rotated") && json.get("rotated").isJsonArray()) {
            return BbProject.vector(json.getAsJsonArray("rotated"), 3, null);
        }
        return new double[3];
    }

    static JsonArray array(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    private static boolean allZero(double[] values) {
        for (double value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allOne(double[] values) {
        for (double value : values) {
            if (value != 1) {
                return false;
            }
        }
        return true;
    }
}
