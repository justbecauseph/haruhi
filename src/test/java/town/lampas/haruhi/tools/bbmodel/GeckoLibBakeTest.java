package town.lampas.haruhi.tools.bbmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.array;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.cube;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.face;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.filled;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.group;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.project;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.texture;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.uuid;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GeckoLib 5.5.5 itself loads the converter's output. The test converts a small synthetic project into a resource pack,
 * has GeckoLib bake it in {@link GeckoLibBakeSandbox}, and checks what GeckoLib made of it.
 *
 * <p>GeckoLib reads Bedrock files back into Blockbench's own axes (GeometryCube.bake: origin "(origin + size.x) * (-1, 1,
 * 1)", rotation "* (-1, -1, 1)" in radians, pivot "* (-1, 1, 1)"), so the baked cube must carry the rotation and pivot
 * the Blockbench project has, and each face's corners must carry the texture coordinates of that face's UV rectangle.
 */
class GeckoLibBakeTest {
    private static final Identifier RIG = Identifier.fromNamespaceAndPath("haruhi_test", "rig");
    private static final Identifier BOXES = Identifier.fromNamespaceAndPath("haruhi_test", "boxes");

    @TempDir
    static Path pack;

    @BeforeAll
    static void convertAndBake() throws Throwable {
        BbmodelConverter.write(BbmodelConverter.convert(rig(), "rig", null), pack, "haruhi_test", "rig");
        BbmodelConverter.write(BbmodelConverter.convert(boxes(), "boxes", null), pack, "haruhi_test", "boxes");
        System.setProperty(GeckoLibBakeSandbox.PACK_PROPERTY, pack.toString());
        GeckoLibBakeSandbox.start();
    }

    /** A 4.10 project: a rotated cube with distinct per-face UVs in a rotated bone, and three animations. */
    private static JsonObject rig() {
        JsonObject project = project("4.10", "free", false, 32, 32);
        texture(project, "skin", filled(32, 32, 0xFFFFFFFF), 32, 32);
        JsonObject cube = cube(project, "c1", "forearm", new double[] {1, 2, 3}, new double[] {4, 6, 8});
        cube.add("origin", array(2, 4, 5));
        cube.add("rotation", array(15, -25, 35));
        JsonObject faces = cube.getAsJsonObject("faces");
        faces.add("north", face(0, 0, 0, 3, 4));
        faces.add("east", face(0, 3, 0, 8, 4));
        faces.add("south", face(0, 8, 0, 11, 4));
        faces.add("west", face(0, 11, 0, 16, 4));
        faces.add("up", face(0, 20, 10, 23, 15));
        faces.add("down", face(0, 24, 10, 27, 15));
        project.getAsJsonArray("outliner").add(group("g1", "arm", new double[] {2, 10, -3}, new double[] {10, 20, 30},
                uuid("c1")));
        project.add("animations", JsonParser.parseString("""
                [
                  {"name": "idle", "loop": "loop", "length": 2, "snapping": 24, "animators": {"g1": {"name": "arm",
                    "type": "bone", "keyframes": [
                      {"channel": "rotation", "time": 0, "interpolation": "catmullrom",
                       "data_points": [{"x": "Math.sin(query.anim_time * 180) * 2", "y": "0", "z": "0"}]},
                      {"channel": "rotation", "time": 1, "interpolation": "catmullrom",
                       "data_points": [{"x": "5", "y": "0", "z": "0"}]},
                      {"channel": "rotation", "time": 2, "interpolation": "catmullrom",
                       "data_points": [{"x": "0", "y": "0", "z": "0"}]}]}}},
                  {"name": "wave", "loop": "once", "length": 1, "snapping": 24, "animators": {"g1": {"name": "arm",
                    "type": "bone", "keyframes": [
                      {"channel": "position", "time": 0, "interpolation": "step",
                       "data_points": [{"x": "0", "y": "0", "z": "0"}]},
                      {"channel": "position", "time": 0.5, "interpolation": "linear",
                       "data_points": [{"x": "0", "y": "2", "z": "0"}]}]}}},
                  {"name": "pose", "loop": "hold", "length": 0.5, "snapping": 24, "animators": {"g1": {"name": "arm",
                    "type": "bone", "keyframes": [
                      {"channel": "scale", "time": 0, "interpolation": "linear",
                       "data_points": [{"x": "1.2", "y": "1.2", "z": "1.2"}]}]}}}
                ]
                """).getAsJsonArray());
        return project;
    }

    @Test
    void geckoLibBakesTheModelWithBlockbenchsPivotsRotationsAndUvs() throws Throwable {
        GeckoLibBakeSandbox.run(GeckoLibBakeTest.class, "checkModel");
    }

    @Test
    void geckoLibBakesEveryAnimationWithItsLoopMode() throws Throwable {
        GeckoLibBakeSandbox.run(GeckoLibBakeTest.class, "checkAnimations");
    }

    /** Runs in the sandbox. */
    private static void checkModel() throws ReflectiveOperationException {
        Object models = Class.forName("com.geckolib.cache.GeckoLibResources").getMethod("getBakedModels").invoke(null);
        Map<?, ?> cache = (Map<?, ?>) invoke(models, "cache");
        Object model = cache.get(RIG);
        assertNotNull(model, "GeckoLib baked haruhi_test:rig");
        assertFalse((Boolean) invoke(model, "isMissingno"));
        Object[] bones = (Object[]) invoke(model, "topLevelBones");
        assertEquals(1, bones.length);
        Object arm = bones[0];
        assertEquals("arm", invoke(arm, "name"));
        // GeoBone pivot and base rotation: Blockbench's group origin [2, 10, -3] and rotation [10, 20, 30] degrees.
        assertClose(new double[] {2, 10, -3},
                new double[] {(Float) invoke(arm, "pivotX"), (Float) invoke(arm, "pivotY"), (Float) invoke(arm, "pivotZ")});
        assertClose(radians(10, 20, 30), new double[] {(Float) invoke(arm, "baseRotX"), (Float) invoke(arm, "baseRotY"),
                (Float) invoke(arm, "baseRotZ")});

        Object[] cubes = (Object[]) field(arm, "cubes");
        assertEquals(1, cubes.length);
        Object cube = cubes[0];
        assertClose(radians(15, -25, 35), vec(invoke(cube, "rotation")));
        assertClose(new double[] {2, 4, 5}, vec(invoke(cube, "pivot")));

        // Minecraft's block model convention, which Blockbench's face UVs follow (its Java codec writes face.uv
        // unchanged): FaceInfo lists each face's corners, and CuboidFace.UVs gives corner 0 (u1, v1), 1 (u1, v2),
        // 2 (u2, v2) and 3 (u2, v1) of the UV rectangle [u1, v1, u2, v2].
        Map<String, double[]> rects = Map.of(
                "north", new double[] {0, 0, 3, 4}, "east", new double[] {3, 0, 8, 4},
                "south", new double[] {8, 0, 11, 4}, "west", new double[] {11, 0, 16, 4},
                "up", new double[] {20, 10, 23, 15}, "down", new double[] {24, 10, 27, 15});
        Map<String, Map<String, String>> corners = corners((Object[]) invoke(cube, "quads"), 32);
        assertEquals(new TreeSet<>(rects.keySet()), corners.keySet(), "GeckoLib baked all six faces");
        double[] from = {1, 2, 3};
        double[] to = {4, 6, 8};
        for (Map.Entry<String, double[]> face : rects.entrySet()) {
            String[] faceInfo = FACE_INFO.get(face.getKey());
            double[] uv = face.getValue();
            Map<String, String> expected = new TreeMap<>();
            for (int corner = 0; corner < 4; corner++) {
                String extents = faceInfo[corner];
                double x = extents.charAt(0) == '-' ? from[0] : to[0];
                double y = extents.charAt(1) == '-' ? from[1] : to[1];
                double z = extents.charAt(2) == '-' ? from[2] : to[2];
                double u = corner <= 1 ? uv[0] : uv[2];
                double v = corner == 0 || corner == 3 ? uv[1] : uv[3];
                expected.put(key(x, y, z), key(u, v));
            }
            assertEquals(expected, corners.get(face.getKey()), face.getKey() + " face: corner position -> texture pixel");
        }
    }

    /**
     * FaceInfo's corner order per face (javap of the 26.2 client), as min (-) or max (+) of X, Y and Z: DOWN MIN_X MIN_Y
     * MAX_Z, MIN_X MIN_Y MIN_Z, MAX_X MIN_Y MIN_Z, MAX_X MIN_Y MAX_Z, and so on.
     */
    private static final Map<String, String[]> FACE_INFO = Map.of(
            "down", new String[] {"--+", "---", "+--", "+-+"},
            "up", new String[] {"-+-", "-++", "+++", "++-"},
            "north", new String[] {"++-", "+--", "---", "-+-"},
            "south", new String[] {"-++", "--+", "+-+", "+++"},
            "west", new String[] {"-+-", "---", "--+", "-++"},
            "east", new String[] {"+++", "+-+", "+--", "++-"});

    /**
     * A box UV cube that had to be written per face (one face is not drawn) must draw its other faces as GeckoLib draws
     * the same cube from box UV, mirrored and not.
     */
    @Test
    void aBoxUvCubeWrittenPerFaceDrawsLikeGeckoLibsBoxUv() throws Throwable {
        GeckoLibBakeSandbox.run(GeckoLibBakeTest.class, "checkBoxes");
    }

    /** Four copies of one box UV cube: kept and written per face, each unmirrored and mirrored. */
    private static JsonObject boxes() {
        JsonObject project = project("4.10", "free", true, 64, 64);
        texture(project, "skin", filled(64, 64, 0xFFFFFFFF), 64, 64);
        String[][] cubes = {{"kept", "false", "false"}, {"per_face", "false", "true"},
                {"kept_mirrored", "true", "false"}, {"per_face_mirrored", "true", "true"}};
        for (String[] spec : cubes) {
            JsonObject cube = cube(project, spec[0], spec[0], new double[] {0, 0, 0}, new double[] {4, 6, 2});
            cube.addProperty("box_uv", true);
            cube.add("uv_offset", array(8, 16));
            cube.addProperty("mirror_uv", Boolean.parseBoolean(spec[1]));
            if (Boolean.parseBoolean(spec[2])) {
                cube.getAsJsonObject("faces").getAsJsonObject("west").add("texture", JsonNull.INSTANCE);
            }
            project.getAsJsonArray("outliner").add(group(spec[0] + "_group", spec[0], new double[3], null, uuid(spec[0])));
        }
        return project;
    }

    /** Runs in the sandbox. */
    private static void checkBoxes() throws ReflectiveOperationException {
        Object models = Class.forName("com.geckolib.cache.GeckoLibResources").getMethod("getBakedModels").invoke(null);
        Object model = ((Map<?, ?>) invoke(models, "cache")).get(BOXES);
        assertNotNull(model, "GeckoLib baked haruhi_test:boxes");
        Map<String, Map<String, Map<String, String>>> byBone = new HashMap<>();
        for (Object bone : (Object[]) invoke(model, "topLevelBones")) {
            Object cube = ((Object[]) field(bone, "cubes"))[0];
            byBone.put((String) invoke(bone, "name"), corners((Object[]) invoke(cube, "quads"), 64));
        }
        for (String mirror : new String[] {"", "_mirrored"}) {
            // Faces are matched by where their corners are: a mirrored GeckoLib box UV cube keeps its quads' direction
            // labels but swaps the east and west quads' places, so the quad labelled east sits on the west side.
            Map<String, Map<String, String>> kept = byPlace(byBone.get("kept" + mirror));
            Map<String, Map<String, String>> perFace = byPlace(byBone.get("per_face" + mirror));
            assertEquals(6, kept.size());
            assertEquals(5, perFace.size(), "the per-face copy does not draw its west face");
            for (Map.Entry<String, Map<String, String>> face : perFace.entrySet()) {
                assertEquals(kept.get(face.getKey()), face.getValue(),
                        "kept" + mirror + " vs per_face" + mirror + ", face at " + face.getKey());
            }
        }
    }

    private static Map<String, Map<String, String>> byPlace(Map<String, Map<String, String>> faces) {
        Map<String, Map<String, String>> byPlace = new TreeMap<>();
        for (Map<String, String> corners : faces.values()) {
            byPlace.put(String.join(" ", corners.keySet()), corners);
        }
        return byPlace;
    }

    /** Each baked face's corners: model position (in pixels) -> texture pixel. */
    private static Map<String, Map<String, String>> corners(Object[] quads, int textureSize)
            throws ReflectiveOperationException {
        Map<String, Map<String, String>> faces = new TreeMap<>();
        for (Object quad : quads) {
            if (quad == null) {
                continue;
            }
            Map<String, String> corners = new TreeMap<>();
            for (Object vertex : (Object[]) invoke(quad, "vertices")) {
                corners.put(key((Float) invoke(vertex, "posX") * 16, (Float) invoke(vertex, "posY") * 16,
                                (Float) invoke(vertex, "posZ") * 16),
                        key((Float) invoke(vertex, "texU") * textureSize, (Float) invoke(vertex, "texV") * textureSize));
            }
            faces.put(invoke(quad, "direction").toString().toLowerCase(Locale.ROOT), corners);
        }
        return faces;
    }

    /** Runs in the sandbox. */
    private static void checkAnimations() throws ReflectiveOperationException {
        Object animations = Class.forName("com.geckolib.cache.GeckoLibResources").getMethod("getBakedAnimations")
                .invoke(null);
        Map<?, ?> cache = (Map<?, ?>) invoke(animations, "cache");
        Object file = cache.get(RIG);
        assertNotNull(file, "GeckoLib baked haruhi_test:rig animations");
        Map<?, ?> byName = (Map<?, ?>) invoke(file, "animations");
        assertEquals(Set.of("idle", "wave", "pose"), byName.keySet());
        assertLoop(byName.get("idle"), "LOOP", 2);
        assertLoop(byName.get("wave"), "PLAY_ONCE", 1);
        assertLoop(byName.get("pose"), "HOLD_ON_LAST_FRAME", 0.5);
        for (String name : Set.of("idle", "wave", "pose")) {
            Object[] boneAnimations = (Object[]) invoke(byName.get(name), "boneAnimations");
            assertEquals(1, boneAnimations.length, name);
            assertEquals("arm", invoke(boneAnimations[0], "boneName"), name + " moves bone arm");
        }
        // The idle rotation's Molang expression compiled to a variable GeckoLib tracks (query.anim_time).
        Set<?> variables = (Set<?>) invoke(byName.get("idle"), "usedVariables");
        assertTrue(variables.toString().contains("anim_time"), "idle uses query.anim_time: " + variables);
    }

    private static void assertLoop(Object animation, String loopType, double length) throws ReflectiveOperationException {
        Class<?> loopTypes = Class.forName("com.geckolib.animation.object.LoopType");
        assertEquals(loopTypes.getField(loopType).get(null), invoke(animation, "loopType"), loopType);
        assertEquals(length, (Double) invoke(animation, "length"), 1e-6, loopType + " length in seconds");
    }

    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        Method found = target.getClass().getMethod(method);
        found.setAccessible(true);
        return found.invoke(target);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field found = target.getClass().getField(name);
        return found.get(target);
    }

    private static double[] vec(Object vec3) throws ReflectiveOperationException {
        return new double[] {vec3.getClass().getField("x").getDouble(vec3), vec3.getClass().getField("y").getDouble(vec3),
                vec3.getClass().getField("z").getDouble(vec3)};
    }

    private static double[] radians(double x, double y, double z) {
        return new double[] {Math.toRadians(x), Math.toRadians(y), Math.toRadians(z)};
    }

    private static void assertClose(double[] expected, double[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-5, "component " + i);
        }
    }

    private static String key(double... values) {
        StringBuilder out = new StringBuilder();
        for (double value : values) {
            out.append(out.isEmpty() ? "" : ",").append(String.format(Locale.ROOT, "%.3f", value + 0.0));
        }
        return out.toString();
    }

}
