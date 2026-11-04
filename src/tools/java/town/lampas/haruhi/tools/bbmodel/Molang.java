package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keyframe values as Blockbench exports them, its sign inversion of Molang expressions, and a check of an expression
 * against what GeckoLib 5.5.5's {@code MathParser} accepts.
 */
final class Molang {
    /** util/molang.ts: {@code let string_num_regex = /^-?\d+(\.\d+f?)?$/;} */
    private static final Pattern STRING_NUMBER = Pattern.compile("^-?\\d+(\\.\\d+f?)?$");
    /** The numbers JavaScript's {@code isNaN(string)} accepts, apart from hex and Infinity. */
    private static final Pattern JS_DECIMAL = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");
    private static final Pattern RETURN = Pattern.compile("return (.+?)(;|$)");
    private static final String BRACKET_OPEN = "{([";
    private static final String BRACKET_CLOSE = "})]";

    /** GeckoLib MathParser.EXPRESSION_FORMAT: compileMolang throws for an expression that does not match it. */
    private static final Pattern GECKOLIB_EXPRESSION = Pattern.compile("^[\\w\\s_+-/*%^&|<>=!?:;.,(){}]+$");
    private static final Pattern FUNCTION_CALL = Pattern.compile("([a-z_][a-z0-9_]*(?:\\.[a-z0-9_]+)+)\\(");
    private static final Pattern QUERY = Pattern.compile("(?<![\\w.])(?:query|q)\\.([a-z0-9_]+)(?![\\w(])");
    /** The functions GeckoLib 5.5.5 MathParser registers (its FUNCTION_FACTORIES), read with javap. */
    private static final Set<String> GECKOLIB_FUNCTIONS = Set.of("math.abs", "math.acos", "math.asin", "math.atan",
            "math.atan2", "math.ceil", "math.clamp", "math.cos", "math.die_roll", "math.die_roll_integer", "math.exp",
            "math.floor", "math.hermite_blend", "math.lerp", "math.lerprotate", "math.ln", "math.max", "math.min",
            "math.min_angle", "math.mod", "math.pow", "math.random", "math.random_integer", "math.round", "math.sin",
            "math.sqrt", "math.to_deg", "math.to_rad", "math.trunc");
    /** The queries GeckoLib 5.5.5 MolangQueries defines, read with javap. */
    private static final Set<String> GECKOLIB_QUERIES = Set.of("actor_count", "anim_time", "block_state", "blocking",
            "body_x_rotation", "body_y_rotation", "can_climb", "can_fly", "can_swim", "can_walk", "cardinal_facing",
            "cardinal_facing_2d", "cardinal_player_facing", "controller_speed", "day", "death_ticks",
            "distance_from_camera", "equipment_count", "frame_alpha", "get_actor_info_id", "ground_speed", "has_cape",
            "has_collision", "has_gravity", "has_head_gear", "has_owner", "has_player_rider", "has_rider",
            "head_x_rotation", "head_y_rotation", "health", "hurt_time", "invulnerable_ticks", "is_alive", "is_angry",
            "is_baby", "is_breathing", "is_enchanted", "is_fire_immune", "is_first_person", "is_in_contact_with_water",
            "is_in_lava", "is_in_water", "is_in_water_or_rain", "is_invisible", "is_leashed", "is_moving", "is_on_fire",
            "is_on_ground", "is_riding", "is_saddled", "is_silent", "is_sleeping", "is_sneaking", "is_sprinting",
            "is_stackable", "is_swimming", "is_using_item", "is_wall_climbing", "item_max_use_duration", "life_time",
            "limb_swing", "limb_swing_amount", "main_hand_item_max_duration", "main_hand_item_use_duration",
            "max_durability", "max_health", "moon_brightness", "moon_phase", "movement_direction", "player_level",
            "remaining_durability", "rider_body_x_rotation", "rider_body_y_rotation", "rider_head_x_rotation",
            "rider_head_y_rotation", "scale", "sleep_rotation", "time_of_day", "time_stamp", "vertical_speed",
            "yaw_speed");

    private Molang() {}

    /**
     * A keyframe axis value as Blockbench writes it: a number, or a Molang string with its line breaks removed.
     * keyframe.js {@code get(axis)}: {@code if (!data_point || !data_point[axis]) return this.transform ? 0 : '';} then
     * util.js {@code exportMolang}: an empty value is 0, a numeric string becomes its number, other strings drop "\n".
     */
    static JsonPrimitive exportValue(JsonElement raw) {
        if (raw == null || raw.isJsonNull()) {
            return new JsonPrimitive(0);
        }
        JsonPrimitive primitive = raw.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            return new JsonPrimitive(primitive.getAsDouble());
        }
        if (primitive.isBoolean()) {
            return new JsonPrimitive(0);
        }
        String text = primitive.getAsString();
        if (text.isEmpty()) {
            return new JsonPrimitive(0);
        }
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            // isNaN("  ") is false and parseFloat("  ") is NaN, which exportMolang turns into 0.
            return new JsonPrimitive(0);
        }
        if (JS_DECIMAL.matcher(trimmed).matches()) {
            return new JsonPrimitive(Double.parseDouble(trimmed));
        }
        return new JsonPrimitive(text.replace("\n", ""));
    }

    /**
     * The negated value: a number's negative, or Blockbench's {@code invertMolang} of an expression (util/molang.ts),
     * which flips the sign of each top-level term.
     */
    static JsonPrimitive invert(JsonPrimitive value) {
        if (value.isNumber()) {
            return new JsonPrimitive(-value.getAsDouble());
        }
        return new JsonPrimitive(invert(value.getAsString()));
    }

    /** A port of util/molang.ts {@code invertMolang(molang: string)}. */
    static String invert(String molang) {
        if (molang.isEmpty() || molang.equals("0")) {
            return molang;
        }
        if (STRING_NUMBER.matcher(molang).matches()) {
            double value = Double.parseDouble(molang.endsWith("f") ? molang.substring(0, molang.length() - 1) : molang);
            return JsonText.number(-value);
        }
        return processMolangReturn(molang, Molang::invertExpression);
    }

    /** A port of util/molang.ts {@code processMolangReturn}: applies {@code callback} to the returned expression. */
    static String processMolangReturn(String molang, UnaryOperator<String> callback) {
        if (molang.contains("return ")) {
            Matcher matcher = RETURN.matcher(molang);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement("return " + callback.apply(matcher.group(1)) + matcher.group(2)));
            }
            matcher.appendTail(out);
            return out.toString();
        }
        molang = molang.replaceAll(";+$", "");
        int lastSemicolon = molang.lastIndexOf(';');
        if (lastSemicolon == -1) {
            if (molang.contains("=")) {
                return molang + ';' + callback.apply("");
            }
            return callback.apply(molang);
        }
        String before = molang.substring(0, lastSemicolon);
        String after = molang.substring(lastSemicolon + 1);
        if (after.contains("=")) {
            return molang + ';' + callback.apply("");
        }
        return before + ';' + callback.apply(after);
    }

    /** The callback in util/molang.ts {@code invertMolang}, character for character. */
    private static String invertExpression(String expression) {
        boolean invert = true;
        int bracketDepth = 0;
        Character lastOperator = null;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (bracketDepth == 0) {
                Character operator = null;
                boolean hadInput = true;
                boolean afterMulOrDiv = lastOperator != null && (lastOperator == '*' || lastOperator == '/');
                if (c == '-' && !afterMulOrDiv) {
                    if (!invert && lastOperator == null) {
                        result.append('+');
                    }
                    invert = false;
                    continue;
                } else if (c == ' ' || c == '\n') {
                    hadInput = false;
                } else if (c == '+' && !afterMulOrDiv) {
                    result.append('-');
                    invert = false;
                    continue;
                } else if (c == '?' || c == ':') {
                    invert = true;
                    operator = c;
                } else if (invert) {
                    result.append('-');
                    invert = false;
                } else if ("+-*/&|".indexOf(c) >= 0) {
                    operator = c;
                }
                if (hadInput) {
                    lastOperator = operator;
                }
            }
            if (BRACKET_OPEN.indexOf(c) >= 0) {
                bracketDepth++;
            } else if (BRACKET_CLOSE.indexOf(c) >= 0) {
                bracketDepth--;
            }
            result.append(c);
        }
        return result.toString();
    }

    /**
     * What GeckoLib 5.5.5 would do wrong with {@code molang}: characters its parser rejects (the animation file then
     * fails to load), functions it does not have, and queries it does not define (they read as unset variables).
     * GeckoLib removes whitespace and lower-cases an expression before parsing it, so {@code Math.sin} is fine.
     */
    static List<String> geckoLibProblems(String molang) {
        List<String> problems = new ArrayList<>();
        if (!GECKOLIB_EXPRESSION.matcher(molang).matches()) {
            problems.add("GeckoLib's Molang parser rejects characters in \"" + molang + "\"");
            return problems;
        }
        String normalized = molang.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
        Matcher functions = FUNCTION_CALL.matcher(normalized);
        while (functions.find()) {
            String function = functions.group(1);
            if (!GECKOLIB_FUNCTIONS.contains(function)) {
                problems.add("GeckoLib has no Molang function " + function);
            }
        }
        Matcher queries = QUERY.matcher(normalized);
        while (queries.find()) {
            if (!GECKOLIB_QUERIES.contains(queries.group(1))) {
                problems.add("GeckoLib does not define query." + queries.group(1)
                        + ", so it reads as a variable nothing sets");
            }
        }
        return problems;
    }
}
