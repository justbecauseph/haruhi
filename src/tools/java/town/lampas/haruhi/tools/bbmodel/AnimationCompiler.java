package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bedrock animations (format_version 1.8.0) from the bbmodel's animations, following Blockbench's Bedrock animation
 * codec: {@code compileAnimation} in js/formats/bedrock/bedrock_animation.js and {@code Keyframe.compileBedrockKeyframe}
 * in js/animations/keyframe.js. Quoted lines are from those two files unless another is named.
 *
 * <p>Signs. Blockbench 5 keeps keyframes in its own axes and flips them on export ({@code flipArray}: position X, and
 * rotation X and Y, through {@code invertMolang}). bbmodel.js {@code processCompatibility} flips those same values when
 * it loads a file older than format 5.0, because such files store them with Bedrock's signs. So a file before 5.0 exports
 * its values unchanged, and a 5.0 file exports them flipped.
 */
final class AnimationCompiler {
    private static final Set<String> CHANNELS = Set.of("rotation", "position", "scale");
    private static final List<String> CHANNEL_ORDER = List.of("rotation", "position", "scale");

    private final BbProject project;
    private final ConversionReport report;
    private final Map<String, String> boneNames;
    private final Map<String, String> exportedBonesByLowerName = new LinkedHashMap<>();
    private final boolean flip;

    private AnimationCompiler(BbProject project, GeometryCompiler.Result geometry, ConversionReport report) {
        this.project = project;
        this.report = report;
        this.boneNames = geometry.boneNames();
        for (String bone : geometry.exportedBones()) {
            exportedBonesByLowerName.put(bone.toLowerCase(Locale.ROOT), bone);
        }
        this.flip = !project.keyframesUseBedrockSigns();
    }

    /** The animation file, or null when the model has no animation to write. */
    static JsonObject compile(BbProject project, GeometryCompiler.Result geometry, ConversionReport report) {
        return new AnimationCompiler(project, geometry, report).run();
    }

    private JsonObject run() {
        if (project.animations.isEmpty()) {
            return null;
        }
        JsonObject animations = new JsonObject();
        for (JsonObject animation : project.animations) {
            String name = BbProject.string(animation, "name", "animation");
            if (animations.has(name)) {
                report.warn("two animations have the same name; the later one replaces the earlier", name);
            }
            animations.add(name, compileAnimation(name, animation));
        }
        report.animationsWritten.addAll(animations.keySet());
        JsonObject file = new JsonObject();
        // compileFile: "format_version: '1.8.0'"
        file.addProperty("format_version", "1.8.0");
        file.add("animations", animations);
        return file;
    }

    private record Keyframe(String channel, double time, String interpolation, JsonArray dataPoints) {}

    private JsonObject compileAnimation(String name, JsonObject animation) {
        String loop = BbProject.string(animation, "loop", "once");
        if (!loop.equals("once") && !loop.equals("loop") && !loop.equals("hold")) {
            loop = "once";
        }
        double length = BbProject.number(animation, "length", 0);
        // Animation: "this.snapping = Math.clamp(this.snapping, 10, 500)", from the animation_snap setting (24).
        double snapping = Math.clamp(BbProject.number(animation, "snapping", 24), 10, 500);
        JsonObject animators = animation.has("animators") && animation.get("animators").isJsonObject()
                ? animation.getAsJsonObject("animators") : new JsonObject();

        JsonObject tag = new JsonObject();
        // "if (animation.loop == 'hold') { ani_tag.loop = 'hold_on_last_frame'; }
        //  else if (animation.loop == 'loop' || animation.getMaxLength() == 0) { ani_tag.loop = true; }"
        // Blockbench leaves loop out for "once"; GeckoLib reads a missing loop as play once, and false says the same.
        if (loop.equals("hold")) {
            tag.addProperty("loop", "hold_on_last_frame");
        } else if (loop.equals("loop") || maxLength(length, animators) == 0) {
            tag.addProperty("loop", true);
            if (loop.equals("once")) {
                report.warn("animation set to play once has no length, so it is written to loop as Blockbench does",
                        name);
            }
        } else {
            tag.addProperty("loop", false);
        }
        // "if (animation.length) ani_tag.animation_length = Math.roundTo(animation.length, 4)"
        if (length != 0) {
            tag.addProperty("animation_length", round(length, 4));
        }
        // "if (animation.override) ani_tag.override_previous_animation = true", and the Molang animation properties.
        if (BbProject.bool(animation, "override", false)) {
            tag.addProperty("override_previous_animation", true);
            ignoredByGeckoLib("override_previous_animation", name);
        }
        for (String property : List.of("anim_time_update", "blend_weight", "start_delay", "loop_delay")) {
            String value = BbProject.string(animation, property, "");
            if (value.isEmpty() || (property.equals("loop_delay") && !tag.get("loop").equals(new JsonPrimitive(true))
                    && !tag.get("loop").equals(new JsonPrimitive("hold_on_last_frame")))) {
                continue;
            }
            tag.add(property, Molang.exportValue(new JsonPrimitive(value)));
            ignoredByGeckoLib(property, name);
        }

        JsonObject bones = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject animator = entry.getValue().getAsJsonObject();
            String type = BbProject.string(animator, "type", "bone");
            List<Keyframe> keyframes = keyframes(animator);
            boolean rotationGlobal = BbProject.bool(animator, "rotation_global", false);
            String animatorName = BbProject.string(animator, "name", entry.getKey());
            if (entry.getKey().equals("effects") || type.equals("effect")) {
                if (!keyframes.isEmpty()) {
                    List<String> channels = keyframes.stream().map(Keyframe::channel).distinct().toList();
                    report.warn("effect keyframes (sound, particle, timeline) skipped",
                            name + " " + String.join("/", channels));
                }
                continue;
            }
            if (!type.equals("bone")) {
                if (!keyframes.isEmpty()) {
                    report.warn("animator of a non-bone element skipped (only bones animate in GeckoLib)",
                            name + " " + animatorName + " (" + type + ")");
                }
                continue;
            }
            // "if (!animator.keyframes.length && !animator.rotation_global) continue;"
            if (keyframes.isEmpty() && !rotationGlobal) {
                continue;
            }
            String bone = boneFor(entry.getKey(), animatorName);
            if (bone == null) {
                report.warn("animator targets no exported bone; skipped", name + " " + animatorName);
                continue;
            }
            if (BbProject.bool(animator, "quaternion_interpolation", false)) {
                report.warn("quaternion rotation interpolation is not carried over; GeckoLib interpolates the angles",
                        name + " " + bone);
            }
            JsonObject boneTag = new JsonObject();
            // "if (animator.rotation_global) { bone_tag.relative_to = {rotation: 'entity'}; bone_tag.rotation = [0, 0, 0.01]; }"
            if (rotationGlobal) {
                JsonObject relative = new JsonObject();
                relative.addProperty("rotation", "entity");
                boneTag.add("relative_to", relative);
                boneTag.add("rotation", GeometryCompiler.array(0, 0, 0.01));
                ignoredByGeckoLib("relative_to (global rotation)", name + " " + bone);
            }
            for (String channel : CHANNEL_ORDER) {
                List<Keyframe> channelKeyframes = keyframes.stream().filter(k -> k.channel.equals(channel)).toList();
                if (!channelKeyframes.isEmpty()) {
                    boneTag.add(channel, compileChannel(name, bone, channel, channelKeyframes, snapping, rotationGlobal));
                }
            }
            if (bones.has(bone)) {
                report.warn("two animators target the same bone; the later one replaces the earlier", name + " " + bone);
            }
            bones.add(bone, boneTag);
        }
        // "if (Object.keys(ani_tag.bones).length == 0) delete ani_tag.bones;"
        if (!bones.isEmpty()) {
            tag.add("bones", bones);
        }
        return tag;
    }

    /**
     * The bone an animator moves. Animators are keyed by group uuid; "var group = animator.getGroup(); ...
     * ani_tag.bones[group ? group.name : animator.name]", and Animation.extend matches a key that is not a uuid to a group
     * by lower-case name.
     */
    private String boneFor(String key, String animatorName) {
        String bone = boneNames.get(key);
        if (bone != null) {
            return bone;
        }
        return exportedBonesByLowerName.get(animatorName.toLowerCase(Locale.ROOT));
    }

    private List<Keyframe> keyframes(JsonObject animator) {
        List<Keyframe> keyframes = new ArrayList<>();
        if (!animator.has("keyframes") || !animator.get("keyframes").isJsonArray()) {
            return keyframes;
        }
        String type = BbProject.string(animator, "type", "bone");
        for (JsonElement element : animator.getAsJsonArray("keyframes")) {
            JsonObject keyframe = element.getAsJsonObject();
            String channel = BbProject.string(keyframe, "channel", "rotation");
            if (type.equals("bone") && !CHANNELS.contains(channel)) {
                report.warn("keyframe channel GeckoLib has no equivalent for; skipped", channel);
                continue;
            }
            JsonArray points = keyframe.has("data_points") && keyframe.get("data_points").isJsonArray()
                    ? keyframe.getAsJsonArray("data_points") : new JsonArray();
            if (points.isEmpty()) {
                // Keyframe.extend: without data_points the axis values sit on the keyframe itself.
                JsonObject point = new JsonObject();
                for (String axis : List.of("x", "y", "z")) {
                    if (keyframe.has(axis)) {
                        point.add(axis, keyframe.get(axis));
                    }
                }
                points.add(point);
            }
            keyframes.add(new Keyframe(channel, BbProject.number(keyframe, "time", 0),
                    BbProject.string(keyframe, "interpolation", "linear"), points));
        }
        return keyframes;
    }

    private JsonElement compileChannel(String animation, String bone, String channel, List<Keyframe> unsorted,
                                       double snapping, boolean rotationGlobal) {
        // "let sorted_keyframes = animator[channel].slice().sort((a, b) => a.time - b.time);"
        List<Keyframe> sorted = new ArrayList<>(unsorted);
        sorted.sort(Comparator.comparingDouble(Keyframe::time));
        JsonObject timeline = new JsonObject();
        for (Keyframe keyframe : sorted) {
            if (keyframe.interpolation.equals("bezier")) {
                report.warn("bezier keyframes written as linear; Blockbench samples the curve, GeckoLib has no bezier",
                        animation + " " + bone + " " + channel + " " + timecode(keyframe.time, snapping));
            } else if (!List.of("linear", "catmullrom", "step").contains(keyframe.interpolation)) {
                report.warn("unknown keyframe interpolation written as linear", keyframe.interpolation);
            }
            String timecode = timecode(keyframe.time, snapping);
            JsonElement value = compileKeyframe(keyframe, sorted);
            // "if (animator.rotation_global && ... allEqual(0)) bone_tag[kf.channel][timecode][2] = 0.01"
            if (rotationGlobal && channel.equals("rotation") && value.isJsonArray() && allZero(value.getAsJsonArray())) {
                value.getAsJsonArray().set(2, new JsonPrimitive(0.01));
            }
            if (timeline.has(timecode)) {
                report.warn("keyframes on the same snapped time; the later one is kept",
                        animation + " " + bone + " " + channel + " " + timecode);
            }
            timeline.add(timecode, value);
            checkMolang(value, animation, bone);
        }
        // "if (timecodes.length === 1 && animator[channel][0].data_points.length == 1 &&
        //  animator[channel][0].interpolation != 'catmullrom') { bone_tag[channel] = bone_tag[channel][timecodes[0]]
        //  if (channel == 'scale' && ... allEqual(bone_tag[channel][0])) bone_tag[channel] = bone_tag[channel][0]; }"
        Keyframe first = unsorted.getFirst();
        if (timeline.size() == 1 && first.dataPoints.size() == 1 && !first.interpolation.equals("catmullrom")) {
            JsonElement only = timeline.entrySet().iterator().next().getValue();
            if (channel.equals("scale") && only.isJsonArray() && allEqual(only.getAsJsonArray())) {
                return only.getAsJsonArray().get(0);
            }
            return only;
        }
        return timeline;
    }

    /** Keyframe.compileBedrockKeyframe for a transform channel. */
    private JsonElement compileKeyframe(Keyframe keyframe, List<Keyframe> sorted) {
        Keyframe previous = previous(keyframe, sorted);
        if (keyframe.interpolation.equals("catmullrom")) {
            // "let include_pre = (!previous && this.time > 0) || (previous && previous.interpolation != 'catmullrom')"
            boolean includePre = (previous == null && keyframe.time > 0)
                    || (previous != null && !previous.interpolation.equals("catmullrom"));
            JsonObject value = new JsonObject();
            if (includePre) {
                value.add("pre", values(keyframe, 0));
            }
            value.add("post", values(keyframe, includePre ? 1 : 0));
            value.addProperty("lerp_mode", "catmullrom");
            return value;
        }
        if (keyframe.dataPoints.size() == 1) {
            // "if (previous && previous.interpolation == 'step') return {pre: flipArray(previous.getArray(1)),
            //  post: flipArray(this.getArray())}"
            if (previous != null && previous.interpolation.equals("step")) {
                JsonObject value = new JsonObject();
                value.add("pre", values(previous, 1));
                value.add("post", values(keyframe, 0));
                return value;
            }
            return values(keyframe, 0);
        }
        JsonObject value = new JsonObject();
        value.add("pre", values(keyframe, 0));
        value.add("post", values(keyframe, 1));
        return value;
    }

    /** Keyframe.getPreviousKeyframe: the latest keyframe of the channel strictly before this one. */
    private static Keyframe previous(Keyframe keyframe, List<Keyframe> sorted) {
        Keyframe previous = null;
        for (Keyframe other : sorted) {
            if (other.time < keyframe.time && (previous == null || other.time >= previous.time)) {
                previous = other;
            }
        }
        return previous;
    }

    /**
     * "flipArray(this.getArray(data_point))": getArray reads the point (clamped to the ones that exist) through
     * exportMolang, and flipArray negates position X and rotation X and Y for a 5.0 file (see the class comment).
     */
    private JsonArray values(Keyframe keyframe, int dataPoint) {
        int index = Math.clamp(dataPoint, 0, keyframe.dataPoints.size() - 1);
        JsonElement pointElement = keyframe.dataPoints.get(index);
        JsonObject point = pointElement.isJsonObject() ? pointElement.getAsJsonObject() : new JsonObject();
        JsonPrimitive x = Molang.exportValue(point.get("x"));
        JsonPrimitive y = Molang.exportValue(point.get("y"));
        JsonPrimitive z = Molang.exportValue(point.get("z"));
        if (flip) {
            if (keyframe.channel.equals("position") || keyframe.channel.equals("rotation")) {
                x = Molang.invert(x);
            }
            if (keyframe.channel.equals("rotation")) {
                y = Molang.invert(y);
            }
        }
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    /**
     * Animation.getMaxLength: the stored length or the latest keyframe of any animator, not counting a final catmullrom
     * keyframe.
     */
    private static double maxLength(double length, JsonObject animators) {
        double max = length;
        for (Map.Entry<String, JsonElement> entry : animators.entrySet()) {
            if (!entry.getValue().isJsonObject() || !entry.getValue().getAsJsonObject().has("keyframes")) {
                continue;
            }
            List<JsonObject> keyframes = new ArrayList<>();
            for (JsonElement keyframe : entry.getValue().getAsJsonObject().getAsJsonArray("keyframes")) {
                keyframes.add(keyframe.getAsJsonObject());
            }
            boolean catmullrom = keyframes.stream()
                    .anyMatch(k -> BbProject.string(k, "interpolation", "linear").equals("catmullrom"));
            if (catmullrom) {
                keyframes.sort(Comparator.comparingDouble(k -> BbProject.number(k, "time", 0)));
            }
            for (int i = 0; i < keyframes.size(); i++) {
                JsonObject keyframe = keyframes.get(i);
                if (BbProject.string(keyframe, "interpolation", "linear").equals("catmullrom")
                        && i == keyframes.size() - 1) {
                    continue;
                }
                max = Math.max(max, BbProject.number(keyframe, "time", 0));
            }
        }
        return max;
    }

    /**
     * Keyframe.getTimecodeString: "trimFloatNumber(Timeline.snapTime(this.time, animation))" with ".0" added to a whole
     * number. snapTime rounds to the animation's snapping (frames per second); trimFloatNumber writes 4 decimals at most.
     */
    static String timecode(double time, double snapping) {
        double fps = Math.clamp(snapping, 1, 120);
        double snapped = Math.max(Math.round(time * fps) / fps, 0);
        String text = BigDecimal.valueOf(snapped).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        if (text.equals("-0")) {
            text = "0";
        }
        return text.contains(".") ? text : text + ".0";
    }

    private static double round(double value, int places) {
        return BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP).doubleValue();
    }

    private void checkMolang(JsonElement value, String animation, String bone) {
        if (value.isJsonArray()) {
            for (JsonElement axis : value.getAsJsonArray()) {
                if (axis.isJsonPrimitive() && axis.getAsJsonPrimitive().isString()) {
                    for (String problem : Molang.geckoLibProblems(axis.getAsString())) {
                        report.warn(problem, animation + " " + bone);
                    }
                }
            }
        } else if (value.isJsonObject()) {
            for (String key : List.of("pre", "post")) {
                if (value.getAsJsonObject().has(key)) {
                    checkMolang(value.getAsJsonObject().get(key), animation, bone);
                }
            }
        }
    }

    private void ignoredByGeckoLib(String property, String where) {
        report.warn(property + " written as Blockbench does; GeckoLib 5.5.5 reads it but does not use it", where);
    }

    private static boolean allZero(JsonArray array) {
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber() || element.getAsDouble() != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allEqual(JsonArray array) {
        for (JsonElement element : array) {
            if (!element.equals(array.get(0))) {
                return false;
            }
        }
        return true;
    }
}
