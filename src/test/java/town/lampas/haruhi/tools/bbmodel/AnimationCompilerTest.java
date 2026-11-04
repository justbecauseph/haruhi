package town.lampas.haruhi.tools.bbmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.array;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.cube;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.filled;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.group;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.project;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.texture;
import static town.lampas.haruhi.tools.bbmodel.TestProjects.uuid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * The animation file the converter writes. Expected values are worked out by hand from Blockbench's Bedrock animation
 * codec (compileAnimation in js/formats/bedrock/bedrock_animation.js, Keyframe.compileBedrockKeyframe in
 * js/animations/keyframe.js) and from its bbmodel loader (processCompatibility in js/formats/bbmodel.js), which flips
 * position X and rotation X and Y of files before format 5.0 on load, before the export flips them back.
 */
class AnimationCompilerTest {
    /** A project of {@code version} with one cube in bone "arm" (group g1) and a second bone also named "arm" (g2). */
    private static JsonObject rig(String version) {
        JsonObject project = project(version, "free", false, 16, 16);
        texture(project, "skin", filled(16, 16, 0xFFFFFFFF), 16, 16);
        cube(project, "c1", "a", new double[] {0, 0, 0}, new double[] {1, 1, 1});
        project.getAsJsonArray("outliner").add(group("g1", "arm", new double[3], null, uuid("c1")));
        project.getAsJsonArray("outliner").add(group("g2", "arm", new double[3], null));
        project.add("animations", new JsonArray());
        return project;
    }

    private static JsonObject animation(JsonObject project, String name, String loop, double length) {
        JsonObject animation = new JsonObject();
        animation.addProperty("name", name);
        animation.addProperty("loop", loop);
        animation.addProperty("length", length);
        animation.addProperty("snapping", 20);
        animation.add("animators", new JsonObject());
        project.getAsJsonArray("animations").add(animation);
        return animation;
    }

    private static JsonArray keyframes(JsonObject animation, String group) {
        JsonObject animator = new JsonObject();
        animator.addProperty("name", "arm");
        animator.addProperty("type", "bone");
        JsonArray keyframes = new JsonArray();
        animator.add("keyframes", keyframes);
        animation.getAsJsonObject("animators").add(group, animator);
        return keyframes;
    }

    private static JsonObject keyframe(JsonArray keyframes, String channel, double time, String interpolation,
                                       Object x, Object y, Object z) {
        JsonObject point = new JsonObject();
        point.add("x", value(x));
        point.add("y", value(y));
        point.add("z", value(z));
        JsonArray points = new JsonArray();
        points.add(point);
        JsonObject keyframe = new JsonObject();
        keyframe.addProperty("channel", channel);
        keyframe.addProperty("time", time);
        keyframe.addProperty("interpolation", interpolation);
        keyframe.add("data_points", points);
        keyframes.add(keyframe);
        return keyframe;
    }

    private static JsonPrimitive value(Object value) {
        return value instanceof Number number ? new JsonPrimitive(number) : new JsonPrimitive(value.toString());
    }

    private static JsonObject compiled(JsonObject project, String animation) throws IOException {
        JsonObject file = BbmodelConverter.convert(project, "test", null).animations();
        assertEquals("1.8.0", file.get("format_version").getAsString());
        return file.getAsJsonObject("animations").getAsJsonObject(animation);
    }

    /**
     * A 4.10 file stores Bedrock's signs, so values come out unchanged. Two rotation keyframes make a timeline keyed by
     * timecode ("0.0", "0.5"); one position keyframe is written as its value alone ("if (timecodes.length === 1 &&
     * ... data_points.length == 1 && interpolation != 'catmullrom')"); loop "loop" becomes true and the length is kept.
     * The animator is keyed by the uuid of the second "arm" group, which the geometry renamed arm2.
     */
    @Test
    void rotationAndPositionKeyframesOfAnOldFileKeepTheirSigns() throws IOException {
        JsonObject project = rig("4.10");
        JsonArray keyframes = keyframes(animation(project, "wave", "loop", 1), "g2");
        keyframe(keyframes, "rotation", 0.5, "linear", 10, -20, 30);
        keyframe(keyframes, "rotation", 0, "linear", 0, 0, 0);
        keyframe(keyframes, "position", 0.25, "linear", "1", "2", "3");

        JsonObject wave = compiled(project, "wave");

        assertTrue(wave.get("loop").getAsBoolean());
        assertEquals(1, wave.get("animation_length").getAsDouble());
        JsonObject bone = wave.getAsJsonObject("bones").getAsJsonObject("arm2");
        JsonObject rotation = bone.getAsJsonObject("rotation");
        assertEquals(java.util.List.of("0.0", "0.5"), java.util.List.copyOf(rotation.keySet()));
        assertEquals(array(0, 0, 0), rotation.get("0.0"));
        assertEquals(array(10, -20, 30), rotation.get("0.5"));
        assertEquals(array(1, 2, 3), bone.get("position"), "numeric strings become numbers (exportMolang)");
    }

    /**
     * A 5.0 file stores Blockbench's own signs; the export's flipArray negates position X and rotation X and Y, with
     * invertMolang for an expression. Loop "hold" becomes "hold_on_last_frame".
     */
    @Test
    void aFormatFiveFileIsFlippedOnExportAndMolangStaysAString() throws IOException {
        JsonObject project = rig("5.0");
        JsonArray keyframes = keyframes(animation(project, "pose", "hold", 0.5), "g1");
        keyframe(keyframes, "rotation", 0, "linear", 0, 0, 0);
        keyframe(keyframes, "rotation", 0.5, "linear", "math.sin(query.anim_time * 90) * 10", -20, 30);
        keyframe(keyframes, "position", 0, "linear", 1, 2, 3);
        keyframe(keyframes, "scale", 0, "linear", 2, 2, 2);

        JsonObject pose = compiled(project, "pose");

        assertEquals("hold_on_last_frame", pose.get("loop").getAsString());
        JsonObject bone = pose.getAsJsonObject("bones").getAsJsonObject("arm");
        JsonArray rotated = bone.getAsJsonObject("rotation").getAsJsonArray("0.5");
        assertEquals("-math.sin(query.anim_time * 90) * 10", rotated.get(0).getAsString());
        assertEquals(20, rotated.get(1).getAsDouble());
        assertEquals(30, rotated.get(2).getAsDouble());
        assertEquals(array(-1, 2, 3), bone.get("position"));
        assertEquals(2, bone.get("scale").getAsDouble(), "a uniform single scale is written as one number");
    }

    /**
     * catmullrom: "{pre: include_pre ? ... : undefined, post: ..., lerp_mode: 'catmullrom'}" with include_pre when the
     * previous keyframe is not catmullrom. After a step keyframe: "{pre: flipArray(previous.getArray(1)), post:
     * flipArray(this.getArray())}", which holds the step value until the next keyframe. Loop "once" is written false.
     */
    @Test
    void catmullromAndStepKeyframesUseBedrockPreAndPost() throws IOException {
        JsonObject project = rig("4.10");
        JsonArray keyframes = keyframes(animation(project, "bob", "once", 1), "g1");
        keyframe(keyframes, "position", 0, "step", 0, 0, 0);
        keyframe(keyframes, "position", 0.5, "linear", 0, 1, 0);
        keyframe(keyframes, "position", 1, "catmullrom", 0, 2, 0);

        JsonObject bob = compiled(project, "bob");

        assertFalse(bob.get("loop").getAsBoolean());
        JsonObject position = bob.getAsJsonObject("bones").getAsJsonObject("arm").getAsJsonObject("position");
        assertEquals(array(0, 0, 0), position.get("0.0"));
        assertEquals(array(0, 0, 0), position.getAsJsonObject("0.5").get("pre"));
        assertEquals(array(0, 1, 0), position.getAsJsonObject("0.5").get("post"));
        JsonObject last = position.getAsJsonObject("1.0");
        assertEquals(array(0, 2, 0), last.get("pre"));
        assertEquals(array(0, 2, 0), last.get("post"));
        assertEquals("catmullrom", last.get("lerp_mode").getAsString());
    }

    @Test
    void bezierKeyframesAreWrittenLinearWithAWarningAndEffectsAreSkipped() throws IOException {
        JsonObject project = rig("4.10");
        JsonObject animation = animation(project, "fancy", "loop", 1);
        JsonArray keyframes = keyframes(animation, "g1");
        keyframe(keyframes, "rotation", 0, "bezier", 0, 0, 0);
        keyframe(keyframes, "rotation", 1, "bezier", 90, 0, 0);
        JsonObject effects = new JsonObject();
        effects.addProperty("type", "effect");
        JsonArray effectKeyframes = new JsonArray();
        JsonObject sound = new JsonObject();
        sound.addProperty("channel", "sound");
        sound.addProperty("time", 0);
        effectKeyframes.add(sound);
        effects.add("keyframes", effectKeyframes);
        animation.getAsJsonObject("animators").add("effects", effects);

        BbmodelConverter.Conversion conversion = BbmodelConverter.convert(project, "test", null);
        JsonObject fancy = conversion.animations().getAsJsonObject("animations").getAsJsonObject("fancy");

        JsonObject rotation = fancy.getAsJsonObject("bones").getAsJsonObject("arm").getAsJsonObject("rotation");
        assertEquals(array(0, 0, 0), rotation.get("0.0"));
        assertEquals(array(90, 0, 0), rotation.get("1.0"));
        assertTrue(conversion.report().hasWarning("bezier keyframes written as linear"));
        assertTrue(conversion.report().hasWarning("effect keyframes (sound, particle, timeline) skipped"));
        assertEquals(java.util.List.of("fancy"), conversion.report().animationsWritten());
    }

    /** Keyframe times snap to the animation's frames per second and are written with at most 4 decimals. */
    @Test
    void timecodesSnapToTheAnimationsFrames() {
        assertEquals("0.0", AnimationCompiler.timecode(0, 24));
        assertEquals("0.0417", AnimationCompiler.timecode(0.04, 24));
        assertEquals("1.5", AnimationCompiler.timecode(1.5, 24));
        assertEquals("2.0", AnimationCompiler.timecode(2, 20));
    }
}
