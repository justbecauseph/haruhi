package town.lampas.haruhi.tools.bbmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.array;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.bone;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.cube;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.description;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.face;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.filled;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.group;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.project;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.texture;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.uuid;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The geometry the converter writes. Expected values are worked out by hand from Blockbench's Bedrock codec
 * (js/formats/bedrock/bedrock.js compileGroup and compileCube, quoted at each test), not from the converter, and the box
 * UV layout from GeckoLib 5.5.5's own box UV code (GeometryQuadUvs.ofBoxUv, read with javap).
 */
class GeometryCompilerTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final String OUTSIDE =
            "face UV reaches outside its texture, so in the atlas it shows part of another texture";
    private static final String FIRST_FRAME =
            "animated texture: only its first frame is used, GeckoLib needs a .png.mcmeta to animate it";

    private static JsonObject oneTextureProject() {
        JsonObject project = project("4.10", "free", false, 16, 16);
        texture(project, "skin", filled(16, 16, RED), 16, 16);
        return project;
    }

    private static BbmodelConverter.Conversion convert(JsonObject project) throws IOException {
        return BbmodelConverter.convert(project, "test", null);
    }

    /**
     * Group origin [2, 10, -3], rotation [10, 20, 30]: "bone.pivot[0] *= -1", "bone.rotation[0] *= -1;
     * bone.rotation[1] *= -1" give pivot [-2, 10, -3] and rotation [-10, -20, 30]. Cube from [1, 2, 3] to [4, 6, 8]
     * (size [3, 4, 5]), origin [2, 4, 5], rotation [15, -25, 35]: "template.origin[0] = -(template.origin[0] +
     * template.size[0])" gives origin [-4, 2, 3]; "template.pivot[0] *= -1" gives pivot [-2, 4, 5]; "if (axis != 2)
     * template.rotation[axis] *= -1" gives rotation [-15, 25, 35].
     */
    @Test
    void aRotatedCubeInARotatedGroupIsMirroredInXLikeBlockbench() throws IOException {
        JsonObject project = oneTextureProject();
        JsonObject cube = cube(project, "c1", "forearm", new double[] {1, 2, 3}, new double[] {4, 6, 8});
        cube.add("origin", array(2, 4, 5));
        cube.add("rotation", array(15, -25, 35));
        cube.addProperty("inflate", 0.5);
        project.getAsJsonArray("outliner").add(group("g1", "arm", new double[] {2, 10, -3}, new double[] {10, 20, 30},
                group("g2", "hand", new double[] {1, 1, 1}, null, uuid("c1"))));

        JsonObject geometry = convert(project).geometry();

        JsonObject arm = bone(geometry, "arm");
        assertEquals(array(-2, 10, -3), arm.get("pivot"));
        assertEquals(array(-10, -20, 30), arm.get("rotation"));
        assertFalse(arm.has("parent"));
        JsonObject hand = bone(geometry, "hand");
        assertEquals("arm", hand.get("parent").getAsString());
        assertEquals(array(-1, 1, 1), hand.get("pivot"));
        assertFalse(hand.has("rotation"), "a zero rotation is left out");
        JsonObject compiled = hand.getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(array(-4, 2, 3), compiled.get("origin"));
        assertEquals(array(3, 4, 5), compiled.get("size"));
        assertEquals(array(-2, 4, 5), compiled.get("pivot"));
        assertEquals(array(-15, 25, 35), compiled.get("rotation"));
        assertEquals(0.5, compiled.get("inflate").getAsDouble());
        assertEquals("1.12.0", geometry.get("format_version").getAsString());
    }

    /**
     * Per-face UV: "uv: [face.uv[0], face.uv[1]], uv_size: [face.uv_size[0], face.uv_size[1]]", where uv_size is
     * [u2 - u1, v2 - v1]; for up and down "uv[0] += uv_size[0]; uv[1] += uv_size[1]; uv_size[0] *= -1;
     * uv_size[1] *= -1". A face whose texture is null is not exported ("if (face.texture !== null)"), and a face
     * rotation becomes uv_rotation, which moves the file to format 1.21.0 (getFormatVersion).
     */
    @Test
    void perFaceUvsFollowTheBedrockExportIncludingUpAndDown() throws IOException {
        JsonObject project = oneTextureProject();
        JsonObject cube = cube(project, "c1", "box", new double[] {0, 0, 0}, new double[] {3, 4, 5});
        JsonObject faces = cube.getAsJsonObject("faces");
        faces.add("north", face(0, 0, 0, 3, 4));
        faces.add("east", face(0, 3, 0, 8, 4));
        JsonObject south = face(0, 8, 0, 11, 4);
        south.addProperty("rotation", 90);
        faces.add("south", south);
        JsonObject west = face(0, 11, 0, 16, 4);
        west.add("texture", JsonNull.INSTANCE);
        faces.add("west", west);
        faces.add("up", face(0, 10, 12, 13, 17));
        faces.add("down", face(0, 13, 12, 16, 17));
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));

        JsonObject geometry = convert(project).geometry();
        JsonObject uv = bone(geometry, "body").getAsJsonArray("cubes").get(0).getAsJsonObject().getAsJsonObject("uv");

        assertEquals(array(0, 0), uv.getAsJsonObject("north").get("uv"));
        assertEquals(array(3, 4), uv.getAsJsonObject("north").get("uv_size"));
        assertEquals(array(3, 0), uv.getAsJsonObject("east").get("uv"));
        assertEquals(array(5, 4), uv.getAsJsonObject("east").get("uv_size"));
        assertEquals(90, uv.getAsJsonObject("south").get("uv_rotation").getAsInt());
        assertFalse(uv.has("west"), "a face with texture null is not drawn");
        // up [10, 12, 13, 17]: uv [10 + 3, 12 + 5], uv_size [-3, -5]
        assertEquals(array(13, 17), uv.getAsJsonObject("up").get("uv"));
        assertEquals(array(-3, -5), uv.getAsJsonObject("up").get("uv_size"));
        // down [13, 12, 16, 17]: uv [13 + 3, 12 + 5], uv_size [-3, -5]
        assertEquals(array(16, 17), uv.getAsJsonObject("down").get("uv"));
        assertEquals(array(-3, -5), uv.getAsJsonObject("down").get("uv_size"));
        assertEquals("1.21.0", geometry.get("format_version").getAsString());
    }

    /**
     * Box UV: "template.uv = cube.uv_offset; if (cube.mirror_uv === !bone.mirror) template.mirror = cube.mirror_uv",
     * and the bone mirrors when "g.mirror_uv && Project.box_uv". So in a plain bone a mirrored cube says mirror: true and
     * an unmirrored one says nothing; in a mirrored bone an unmirrored cube says mirror: false.
     */
    @Test
    void boxUvKeepsItsOffsetAndWritesMirrorAgainstTheBone() throws IOException {
        JsonObject project = project("4.10", "free", true, 64, 64);
        texture(project, "skin", filled(64, 64, RED), 64, 64);
        JsonObject mirrored = cube(project, "c1", "mirrored", new double[] {0, 0, 0}, new double[] {4, 12, 4});
        mirrored.addProperty("box_uv", true);
        mirrored.add("uv_offset", array(16, 32));
        mirrored.addProperty("mirror_uv", true);
        JsonObject plain = cube(project, "c2", "plain", new double[] {0, 0, 0}, new double[] {4, 12, 4});
        plain.addProperty("box_uv", true);
        plain.add("uv_offset", array(40, 16));
        JsonObject inMirroredBone = cube(project, "c3", "unmirrored", new double[] {0, 0, 0}, new double[] {4, 12, 4});
        inMirroredBone.addProperty("box_uv", true);
        project.getAsJsonArray("outliner").add(group("g1", "leg", new double[3], null, uuid("c1"), uuid("c2")));
        JsonObject mirrorGroup = group("g2", "left_leg", new double[3], null, uuid("c3"));
        mirrorGroup.addProperty("mirror_uv", true);
        project.getAsJsonArray("outliner").add(mirrorGroup);

        JsonObject geometry = convert(project).geometry();

        JsonObject leg = bone(geometry, "leg");
        assertFalse(leg.has("mirror"));
        JsonObject first = leg.getAsJsonArray("cubes").get(0).getAsJsonObject();
        assertEquals(array(16, 32), first.get("uv"));
        assertTrue(first.get("mirror").getAsBoolean());
        JsonObject second = leg.getAsJsonArray("cubes").get(1).getAsJsonObject();
        assertEquals(array(40, 16), second.get("uv"));
        assertFalse(second.has("mirror"));
        JsonObject leftLeg = bone(geometry, "left_leg");
        assertTrue(leftLeg.get("mirror").getAsBoolean());
        assertFalse(leftLeg.getAsJsonArray("cubes").get(0).getAsJsonObject().get("mirror").getAsBoolean());
    }

    /**
     * A box UV cube with a face that is not drawn cannot stay box UV (GeckoLib draws all six box faces), so it is written
     * per face. The faces it keeps must land where GeckoLib's own box UV would put them. GeometryQuadUvs.ofBoxUv for
     * offset (u, v) and floored size (x, y, z): east (u, v+z) size (z, y); north (u+z, v+z) size (x, y); west (u+z+x,
     * v+z) size (z, y); south (u+2z+x, v+z) size (x, y); up (u+z, v) size (x, z); down (u+z+x, v+z) size (x, -z).
     * Here u = 8, v = 4, size (4, 6, 2) with the fraction .5 in Y floored away.
     */
    @Test
    void boxUvWrittenPerFaceLandsWhereGeckoLibsBoxUvWould() throws IOException {
        JsonObject project = project("4.10", "free", true, 64, 64);
        texture(project, "skin", filled(64, 64, RED), 64, 64);
        JsonObject cube = cube(project, "c1", "torso", new double[] {0, 0, 0}, new double[] {4, 6.5, 2});
        cube.addProperty("box_uv", true);
        cube.add("uv_offset", array(8, 4));
        cube.getAsJsonObject("faces").getAsJsonObject("west").add("texture", JsonNull.INSTANCE);
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));

        BbmodelConverter.Conversion conversion = convert(project);
        JsonObject uv = bone(conversion.geometry(), "body").getAsJsonArray("cubes").get(0).getAsJsonObject()
                .getAsJsonObject("uv");

        assertFaceUv(uv, "east", 8, 6, 2, 6);
        assertFaceUv(uv, "north", 10, 6, 4, 6);
        assertFalse(uv.has("west"));
        assertFaceUv(uv, "south", 16, 6, 4, 6);
        assertFaceUv(uv, "up", 10, 4, 4, 2);
        assertFaceUv(uv, "down", 14, 6, 4, -2);
        assertTrue(conversion.report().hasWarning("box UV cube written as per-face UV"));
    }

    private static void assertFaceUv(JsonObject uv, String face, double u, double v, double width, double height) {
        assertEquals(array(u, v), uv.getAsJsonObject(face).get("uv"), face + " uv");
        assertEquals(array(width, height), uv.getAsJsonObject(face).get("uv_size"), face + " uv_size");
    }

    /**
     * Two used textures become one atlas: a 4x4 red texture in a 4x4 UV space and a 2x2 blue texture in a 1x1 UV space
     * (so its UVs scale by 2). Each face's UV rectangle in the atlas must cover its own texture's pixels, the geometry's
     * texture size must be the atlas size, and a texture no face uses is left out.
     */
    @Test
    void twoTexturesAreStitchedIntoAnAtlasAndFaceUvsRemapped() throws IOException {
        JsonObject project = project("4.10", "free", false, 16, 16);
        texture(project, "red", filled(4, 4, RED), 4, 4);
        texture(project, "blue", filled(2, 2, BLUE), 1, 1);
        texture(project, "unused", filled(8, 8, 0xFF00FF00), 8, 8);
        JsonObject cube = cube(project, "c1", "box", new double[] {0, 0, 0}, new double[] {4, 4, 4});
        JsonObject faces = cube.getAsJsonObject("faces");
        faces.add("north", face(0, 0, 0, 4, 4));
        faces.add("south", face(1, 0, 0, 1, 1));
        faces.add("up", face(1, 0, 0, 1, 1));
        for (String hidden : List.of("east", "west", "down")) {
            faces.getAsJsonObject(hidden).add("texture", JsonNull.INSTANCE);
        }
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));

        BbmodelConverter.Conversion conversion = convert(project);
        BufferedImage atlas = conversion.texture();
        JsonObject description = description(conversion.geometry());
        JsonObject uv = bone(conversion.geometry(), "body").getAsJsonArray("cubes").get(0).getAsJsonObject()
                .getAsJsonObject("uv");

        assertEquals(atlas.getWidth(), description.get("texture_width").getAsInt());
        assertEquals(atlas.getHeight(), description.get("texture_height").getAsInt());
        assertCovers(atlas, uv.getAsJsonObject("north"), RED, 4, 4);
        assertCovers(atlas, uv.getAsJsonObject("south"), BLUE, 2, 2);
        assertCovers(atlas, uv.getAsJsonObject("up"), BLUE, 2, 2);
        assertEquals(2, conversion.report().texturesUsed().size());
        assertEquals(1, conversion.report().texturesIgnored().size());
        assertTrue(conversion.report().texturesIgnored().getFirst().startsWith("unused"));
    }

    /** The face's rectangle in atlas pixels is {@code width} x {@code height} and every pixel in it is {@code argb}. */
    private static void assertCovers(BufferedImage atlas, JsonObject face, int argb, int width, int height) {
        double u = face.getAsJsonArray("uv").get(0).getAsDouble();
        double v = face.getAsJsonArray("uv").get(1).getAsDouble();
        double w = face.getAsJsonArray("uv_size").get(0).getAsDouble();
        double h = face.getAsJsonArray("uv_size").get(1).getAsDouble();
        int left = (int) Math.min(u, u + w);
        int top = (int) Math.min(v, v + h);
        assertEquals(width, (int) Math.abs(w), "face width in atlas pixels");
        assertEquals(height, (int) Math.abs(h), "face height in atlas pixels");
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                assertEquals(argb, atlas.getRGB(x, y), "atlas pixel " + x + "," + y);
            }
        }
    }

    @Test
    void meshesLocatorsAndNullObjectsAreSkippedWithAWarningThatNamesThem() throws IOException {
        JsonObject project = oneTextureProject();
        cube(project, "c1", "head", new double[] {0, 0, 0}, new double[] {8, 8, 8});
        for (String[] element : new String[][] {{"m1", "mesh", "blob"}, {"l1", "locator", "hand_point"},
                {"n1", "null_object", "ik_target"}}) {
            JsonObject json = new JsonObject();
            json.addProperty("uuid", element[0]);
            json.addProperty("type", element[1]);
            json.addProperty("name", element[2]);
            project.getAsJsonArray("elements").add(json);
        }
        project.getAsJsonArray("outliner").add(group("g1", "head", new double[3], null, uuid("c1"), uuid("m1"),
                uuid("l1"), uuid("n1")));

        BbmodelConverter.Conversion conversion = convert(project);

        assertEquals(1, conversion.report().cubes());
        assertEquals(Map.of("mesh", 1, "locator", 1, "null_object", 1), conversion.report().skippedElements());
        Map<String, List<String>> warnings = conversion.report().warnings();
        assertEquals(List.of("blob"), warnings.get("mesh elements skipped: GeckoLib cannot draw meshes"));
        assertTrue(warnings.values().stream().anyMatch(details -> details.contains("hand_point")));
        assertTrue(warnings.values().stream().anyMatch(details -> details.contains("ik_target")));
    }

    /**
     * Bedrock bone names must be unique. The first keeps its name; later ones get the first free number from 2
     * (OutlinerNode.createUniqueName), compared without case.
     */
    @Test
    void duplicateBoneNamesGetANumberAndAWarning() throws IOException {
        JsonObject project = oneTextureProject();
        cube(project, "c1", "a", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        project.getAsJsonArray("outliner").add(group("g1", "arm", new double[3], null, uuid("c1")));
        project.getAsJsonArray("outliner").add(group("g2", "arm", new double[3], null));
        project.getAsJsonArray("outliner").add(group("g3", "Arm", new double[3], null));

        BbmodelConverter.Conversion conversion = convert(project);

        assertEquals("arm", bone(conversion.geometry(), "arm").get("name").getAsString());
        bone(conversion.geometry(), "arm2");
        bone(conversion.geometry(), "Arm3");
        assertEquals(List.of("arm -> arm2", "Arm -> Arm3"), conversion.report().warnings()
                .get("duplicate bone name renamed, Bedrock bone names must be unique"));
    }

    /**
     * Elements at the outliner root go in a first bone named bb_main ("let group = new Group({name: 'bb_main'});
     * groups.splice(0, 0, group)"), and a cube with "export": false is left out.
     */
    @Test
    void rootElementsGoInABbMainBoneAndUnexportedCubesAreLeftOut() throws IOException {
        JsonObject project = oneTextureProject();
        cube(project, "c1", "loose", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        cube(project, "c2", "hidden", new double[] {0, 0, 0}, new double[] {1, 1, 1}).addProperty("export", false);
        project.getAsJsonArray("outliner").add(uuid("c1"));
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c2")));

        BbmodelConverter.Conversion conversion = convert(project);

        JsonObject geometry = conversion.geometry();
        JsonObject first = geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones")
                .get(0).getAsJsonObject();
        assertEquals("bb_main", first.get("name").getAsString());
        assertEquals(1, first.getAsJsonArray("cubes").size());
        assertEquals(1, conversion.report().cubes());
        assertEquals(Map.of("cube (export: false)", 1), conversion.report().skippedElements());
        assertNull(conversion.animations(), "no animations, no animation file");
    }

    /**
     * calculateVisibleBox for one cube from [-8, 0, -8] to [8, 32, 8], with Blockbench's "+8" offset on the box: the
     * radius is 16, so the width is ceil(32 / 16) = 2; y runs from floor(8 / 16) = 0 to ceil(40 / 16) = 3, widened by the
     * stored box [1, 1, 0] to -0.5, so the height is 3.5 and the offset 1.25.
     */
    @Test
    void visibleBoundsFollowBlockbenchsCalculation() throws IOException {
        JsonObject project = oneTextureProject();
        cube(project, "c1", "tall", new double[] {-8, 0, -8}, new double[] {8, 32, 8});
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));

        JsonObject description = description(convert(project).geometry());

        assertEquals(2, description.get("visible_bounds_width").getAsDouble());
        assertEquals(3.5, description.get("visible_bounds_height").getAsDouble());
        assertEquals(array(0, 1.25, 0), description.get("visible_bounds_offset"));
    }

    /**
     * bbmodel.js processCompatibility mirrors a box UV element with "shade": false when the project has meta.box_uv and
     * format_version is below 4.5: "if (element.shade === false) { element.mirror_uv = true; }". compileCube then writes
     * "if (cube.mirror_uv === !bone.mirror) template.mirror = cube.mirror_uv". The bone "body" is not mirrored, so in a
     * 4.4 file the unlit cube (shade false) says mirror: true and the lit cube (shade true) says nothing. In a 4.5 file the
     * element keeps its stored mirror_uv (false), so the unlit cube says nothing either.
     */
    @Test
    void shadeFalseMirrorsBoxUvCubesOnlyBeforeFormat45() throws IOException {
        JsonObject legacy = convert(shadedBoxUvProject("4.4")).geometry();
        assertEquals(array(0, 0), compiledCube(legacy, "body", 0).get("uv"));
        assertFalse(compiledCube(legacy, "body", 0).has("mirror"));
        assertEquals(array(16, 16), compiledCube(legacy, "body", 1).get("uv"));
        assertTrue(compiledCube(legacy, "body", 1).get("mirror").getAsBoolean());

        JsonObject current = convert(shadedBoxUvProject("4.5")).geometry();
        assertFalse(compiledCube(current, "body", 1).has("mirror"));
    }

    /** A box UV project in {@code formatVersion}: cube "lit" (shade true) and cube "unlit" (shade false) in bone "body". */
    private static JsonObject shadedBoxUvProject(String formatVersion) {
        JsonObject project = project(formatVersion, "free", true, 64, 64);
        texture(project, "skin", filled(64, 64, RED), 64, 64);
        JsonObject lit = cube(project, "c1", "lit", new double[] {0, 0, 0}, new double[] {4, 12, 4});
        lit.addProperty("box_uv", true);
        lit.addProperty("shade", true);
        lit.add("uv_offset", array(0, 0));
        JsonObject unlit = cube(project, "c2", "unlit", new double[] {0, 0, 0}, new double[] {4, 12, 4});
        unlit.addProperty("box_uv", true);
        unlit.addProperty("shade", false);
        unlit.add("uv_offset", array(16, 16));
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1"), uuid("c2")));
        return project;
    }

    private static JsonObject compiledCube(JsonObject geometry, String boneName, int index) {
        return bone(geometry, boneName).getAsJsonArray("cubes").get(index).getAsJsonObject();
    }

    /**
     * Before format 3.2 bbmodel.js negates the Z rotation of every outliner group on load: "//Fix Z-axis inversion pre 3.2"
     * then "if (child.rotation) child.rotation[2] *= -1". compileGroup then writes "bone.rotation = g.rotation.slice();
     * bone.rotation[0] *= -1; bone.rotation[1] *= -1". So "arm" [10, 20, 30] loads as [10, 20, -30] and its bone is
     * [-10, -20, -30]. Its child "hand" [0, 0, 40] loads as [0, 0, -40] and its bone is [0, 0, -40]. From 3.2 on nothing
     * is negated: "arm" is [-10, -20, 30] and "hand" is [0, 0, 40].
     */
    @Test
    void groupZRotationIsNegatedOnlyBeforeFormat32() throws IOException {
        JsonObject before = convert(groupRotationProject("3.1")).geometry();
        assertEquals(array(-10, -20, -30), bone(before, "arm").get("rotation"));
        assertEquals(array(0, 0, -40), bone(before, "hand").get("rotation"));

        JsonObject since = convert(groupRotationProject("3.2")).geometry();
        assertEquals(array(-10, -20, 30), bone(since, "arm").get("rotation"));
        assertEquals(array(0, 0, 40), bone(since, "hand").get("rotation"));
    }

    private static JsonObject groupRotationProject(String formatVersion) {
        JsonObject project = project(formatVersion, "free", false, 16, 16);
        texture(project, "skin", filled(16, 16, RED), 16, 16);
        cube(project, "c1", "palm", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        project.getAsJsonArray("outliner").add(group("g1", "arm", new double[3], new double[] {10, 20, 30},
                group("g2", "hand", new double[3], new double[] {0, 0, 40}, uuid("c1"))));
        return project;
    }

    /**
     * A box UV cube stays box UV in an atlas when all its faces are on one unscaled texture, and then its six faces are
     * checked against that texture the way the per-face rectangles are. Blockbench lays a cube's faces out around
     * uv_offset (TextureGenerator, the face_list of the box UV template, with the size from
     * "cube.size(undefined, Format.box_uv_float_size != true)"):
     *   north: from [size[2], size[2]], size [size[0], size[1]]
     *   east:  from [0, size[2]], size [size[2], size[1]]
     *   south: from [size[2] * 2 + size[0], size[2]], size [size[0], size[1]]
     *   west:  from [size[2] + size[0], size[2]], size [size[2], size[1]]
     *   up:    from [size[2] + size[0], size[2]], size [-size[0], -size[2]]
     *   down:  from [size[2] + size[0] * 2, 0], size [-size[0], size[2]]
     * Each corner is at uv_offset + from, and the far corner adds the size. Size [4, 6, 2] at offset [8, 4] gives these x
     * ranges: north 10-14, east 8-10, south 16-20, west 14-16, up 10-14, down 14-18. In the 16-wide texture "skin", south
     * and down reach past the edge. West ends exactly on the edge, which is not outside.
     */
    @Test
    void boxUvCubeThatStaysBoxUvInAnAtlasIsCheckedAgainstItsTexture() throws IOException {
        JsonObject stitched = project("4.10", "free", true, 64, 64);
        texture(stitched, "skin", filled(16, 16, RED), 16, 16);
        texture(stitched, "other", filled(8, 8, BLUE), 8, 8);
        addBoxUvTorso(stitched);
        JsonObject marker = cube(stitched, "c2", "marker", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        for (String faceName : GeometryCompiler.FACES) {
            marker.getAsJsonObject("faces").add(faceName, face(1, 0, 0, 8, 8));
        }
        stitched.getAsJsonArray("outliner").add(group("g2", "marker", new double[3], null, uuid("c2")));

        BbmodelConverter.Conversion conversion = convert(stitched);

        assertTrue(compiledCube(conversion.geometry(), "body", 0).get("uv").isJsonArray(), "the cube stays box UV");
        assertEquals(List.of("torso south (skin)", "torso down (skin)"), conversion.report().warnings().get(OUTSIDE));
        assertFalse(conversion.report().hasWarning("box UV cube written as per-face UV"));

        JsonObject alone = project("4.10", "free", true, 64, 64);
        texture(alone, "skin", filled(16, 16, RED), 16, 16);
        addBoxUvTorso(alone);
        assertFalse(convert(alone).report().hasWarning(OUTSIDE), "with one texture there is no atlas to show another texture");
    }

    /** Adds a box UV cube "torso", size [4, 6, 2] at uv_offset [8, 4], to a bone "body", on the texture every face uses. */
    private static void addBoxUvTorso(JsonObject project) {
        JsonObject torso = cube(project, "c1", "torso", new double[] {0, 0, 0}, new double[] {4, 6, 2});
        torso.addProperty("box_uv", true);
        torso.add("uv_offset", array(8, 4));
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));
    }

    /**
     * In a single-texture format bbmodel.js load gives every face that is not null the default texture: "} else if
     * (default_texture && copy.faces && copy.faces[face].texture !== null && !Format.single_texture_default) {
     * copy.faces[face].texture = default_texture.uuid }". So a face that stores index 0 and a face that stores no texture
     * are both drawn with the default. modded_entity does not set single_texture_default, so Texture.getDefault ignores
     * use_as_default there and the default is the first texture ("red"), even though "blue" is marked. "blue" is
     * ignored, and no face warns that it has no texture.
     */
    @Test
    void singleTextureFormatDrawsEveryFaceWithTheDefaultTexture() throws IOException {
        JsonObject project = project("4.10", "modded_entity", false, 16, 16);
        texture(project, "red", filled(16, 16, RED), 16, 16);
        texture(project, "blue", filled(16, 16, BLUE), 16, 16).addProperty("use_as_default", true);
        JsonObject block = cube(project, "c1", "block", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        block.getAsJsonObject("faces").getAsJsonObject("up").remove("texture");
        block.getAsJsonObject("faces").getAsJsonObject("down").remove("texture");
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));

        BbmodelConverter.Conversion conversion = convert(project);
        JsonObject uv = compiledCube(conversion.geometry(), "body", 0).getAsJsonObject("uv");

        assertEquals(6, uv.size(), "all six faces are drawn");
        assertEquals(1, conversion.report().texturesUsed().size());
        assertTrue(conversion.report().texturesUsed().getFirst().startsWith("red ("));
        assertEquals(1, conversion.report().texturesIgnored().size());
        assertTrue(conversion.report().texturesIgnored().getFirst().startsWith("blue ("));
        assertFalse(conversion.report().hasWarning("without a texture"));
        assertFalse(conversion.report().hasWarning("has no texture"));
    }

    /**
     * bedrock_block sets single_texture_default, so Texture.getDefault returns the texture marked use_as_default
     * ("blue"), and faces that store no texture draw with it. "red", which no face names, is ignored.
     */
    @Test
    void bedrockBlockTakesTheTextureMarkedUseAsDefault() throws IOException {
        JsonObject project = project("4.10", "bedrock_block", false, 16, 16);
        texture(project, "red", filled(16, 16, RED), 16, 16);
        texture(project, "blue", filled(16, 16, BLUE), 16, 16).addProperty("use_as_default", true);
        JsonObject block = cube(project, "c1", "block", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        for (String face : List.of("north", "east", "south", "west", "up", "down")) {
            block.getAsJsonObject("faces").getAsJsonObject(face).remove("texture");
        }
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));

        BbmodelConverter.Conversion conversion = convert(project);

        assertEquals(1, conversion.report().texturesUsed().size());
        assertTrue(conversion.report().texturesUsed().getFirst().startsWith("blue ("));
        assertTrue(conversion.report().texturesIgnored().getFirst().startsWith("red ("));
    }

    /**
     * Blockbench counts a texture's frames only when Format.animated_textures is set (textures.js frameCount, quoted in
     * TextureAtlas). A 16x32 texture on a 16x16 UV space is 2 frames in "free", so the first 16x16 frame is kept and the
     * texture is 16 tall. "modded_entity" does not set the flag, so the whole 16x32 image is kept, and the 16x16 UV space
     * maps onto all of it.
     */
    @Test
    void animatedTexturesKeepTheirFirstFrameOnlyInFormatsThatAnimateThem() throws IOException {
        JsonObject free = project("4.10", "free", false, 16, 16);
        texture(free, "flipbook", filled(16, 32, RED), 16, 16);
        addUnitCube(free);
        BbmodelConverter.Conversion animated = convert(free);
        assertEquals(16, animated.texture().getHeight());
        assertEquals(16, description(animated.geometry()).get("texture_height").getAsInt());
        assertEquals(List.of("flipbook (2 frames)"), animated.report().warnings().get(FIRST_FRAME));

        JsonObject entity = project("4.10", "modded_entity", false, 16, 16);
        texture(entity, "flipbook", filled(16, 32, RED), 16, 16);
        addUnitCube(entity);
        BbmodelConverter.Conversion whole = convert(entity);
        assertEquals(32, whole.texture().getHeight());
        assertEquals(16, description(whole.geometry()).get("texture_height").getAsInt());
        assertFalse(whole.report().hasWarning("animated texture"));
    }

    /** Adds one unit cube "block" on the texture every face uses, in a bone "body". */
    private static void addUnitCube(JsonObject project) {
        cube(project, "c1", "block", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        project.getAsJsonArray("outliner").add(group("g1", "body", new double[3], null, uuid("c1")));
    }
}
