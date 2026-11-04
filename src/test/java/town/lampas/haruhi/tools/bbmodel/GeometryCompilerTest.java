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
}
