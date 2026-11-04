package town.lampas.haruhi.dialogue.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.FlagNamespace;
import town.lampas.haruhi.dialogue.DialogueConfig;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * Argument checks of every built-in. Each parse is called the way the content validator calls it: with the arguments
 * without {@code type}, and a parse context that declares the flags the bundle uses.
 */
class BuiltinParseTest {
    private static final String WHERE = "haruhi:test/graph#start: ";
    private static final String NPC = "haruhi:test/npc";
    private static final ParseContext CONTEXT = new ParseContext(
            new ContentBundle(ContentBundle.SCHEMA,
                    Map.of(NPC, new FlagNamespace(NPC, Set.of("met", "count"))),
                    Map.of(), Map.of()),
            Identifier.fromNamespaceAndPath("haruhi", "test/graph"),
            "start");

    static {
        TestBuiltins.ensureRegistered();
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static DataResult<DialogueCondition> condition(String type, String args) {
        return DialogueRegistries.condition(Identifier.parse(type)).orElseThrow().parse(json(args), CONTEXT);
    }

    private static DataResult<DialogueAction> action(String type, String args) {
        return DialogueRegistries.action(Identifier.parse(type)).orElseThrow().parse(json(args), CONTEXT);
    }

    private static String error(DataResult<?> result) {
        assertTrue(result.error().isPresent(), "expected a parse error");
        return result.error().get().message();
    }

    private static void assertOk(DataResult<?> result) {
        assertTrue(result.result().isPresent(), () -> "expected success, got: "
                + result.error().map(e -> e.message()).orElse("?"));
    }

    @Test
    void aFlagSetNeedsNoValueAndAFlagComparisonNeedsOne() {
        assertOk(condition("haruhi:flag", "{\"namespace\":\"" + NPC + "\",\"flag\":\"met\",\"op\":\"set\"}"));
        assertTrue(error(condition("haruhi:flag",
                "{\"namespace\":\"" + NPC + "\",\"flag\":\"count\",\"op\":\"gte\"}")).contains("'value' is required"));
        assertTrue(error(condition("haruhi:flag",
                "{\"namespace\":\"" + NPC + "\",\"flag\":\"met\",\"op\":\"set\",\"value\":1}"))
                .contains("'value' is not used by op 'set'"));
    }

    @Test
    void anUnknownFlagOpIsRefused() {
        String message = error(condition("haruhi:flag",
                "{\"namespace\":\"" + NPC + "\",\"flag\":\"met\",\"op\":\"contains\",\"value\":1}"));
        assertTrue(message.contains("'op' must be one of"), message);
    }

    @Test
    void anUndeclaredFlagIsRefusedWithTheWherePrefix() {
        String message = error(condition("haruhi:flag",
                "{\"namespace\":\"" + NPC + "\",\"flag\":\"secret\",\"op\":\"set\"}"));
        assertTrue(message.startsWith(WHERE), message);
        assertTrue(message.contains("not declared"), message);
    }

    @Test
    void anUndeclaredNamespaceIsRefused() {
        String message = error(condition("haruhi:flag",
                "{\"namespace\":\"haruhi:test/other\",\"flag\":\"met\",\"op\":\"set\"}"));
        assertTrue(message.contains("not declared in namespace 'haruhi:test/other'"), message);
    }

    @Test
    void anArgumentNoBuiltinTakesIsRefused() {
        String message = error(condition("haruhi:flag",
                "{\"namespace\":\"" + NPC + "\",\"flag\":\"met\",\"op\":\"set\",\"flags\":1}"));
        assertTrue(message.contains("no argument named 'flags'"), message);
    }

    @Test
    void aComparisonValueMustBeAWholeNumber() {
        assertTrue(error(condition("haruhi:flag",
                "{\"namespace\":\"" + NPC + "\",\"flag\":\"count\",\"op\":\"eq\",\"value\":1.5}"))
                .contains("whole number"));
    }

    @Test
    void visitsTakeComparisonOpsOnly() {
        assertTrue(error(condition("haruhi:visits", "{\"op\":\"set\",\"value\":1}")).contains("'op' must be one of"));
        assertOk(condition("haruhi:visits", "{\"op\":\"gte\",\"value\":2}"));
        assertTrue(error(condition("haruhi:visits", "{\"op\":\"gte\"}")).contains("'value' is required"));
    }

    @Test
    void allOfAndAnyOfNeedAtLeastOneNestedCondition() {
        assertTrue(error(condition("haruhi:all_of", "{\"conditions\":[]}")).contains("must not be empty"));
        assertTrue(error(condition("haruhi:any_of", "{\"conditions\":[]}")).contains("must not be empty"));
    }

    @Test
    void nestedSpecsResolveThroughTheRegistry() {
        assertOk(condition("haruhi:all_of", "{\"conditions\":[{\"type\":\"haruhi:flag\",\"namespace\":\"" + NPC
                + "\",\"flag\":\"met\",\"op\":\"set\"}]}"));
        String message = error(condition("haruhi:any_of", "{\"conditions\":[{\"type\":\"haruhi:nope\"}]}"));
        assertTrue(message.contains("unknown condition type haruhi:nope"), message);
    }

    @Test
    void aNestedErrorNamesTheWhereOnce() {
        String message = error(condition("haruhi:any_of", "{\"conditions\":[{\"type\":\"haruhi:flag\",\"namespace\":\""
                + NPC + "\",\"flag\":\"secret\",\"op\":\"set\"}]}"));
        assertTrue(message.startsWith(WHERE), message);
        assertEquals(1, message.split(java.util.regex.Pattern.quote(WHERE), -1).length - 1, message);
    }

    @Test
    void notNeedsItsCondition() {
        assertTrue(error(condition("haruhi:not", "{}")).contains("'condition' is required"));
        assertOk(condition("haruhi:not", "{\"condition\":{\"type\":\"haruhi:flag\",\"namespace\":\"" + NPC
                + "\",\"flag\":\"met\",\"op\":\"unset\"}}"));
    }

    @Test
    void predicateNeedsAnId() {
        assertTrue(error(condition("haruhi:predicate", "{\"predicate\":\"Not An Id\"}")).contains("not an id"));
        assertOk(condition("haruhi:predicate", "{\"predicate\":\"haruhi:test/check\"}"));
    }

    @Test
    void hasItemCountsAtLeastOneAndAcceptsTags() {
        assertTrue(error(condition("haruhi:has_item", "{\"item\":\"minecraft:stone\",\"count\":0}"))
                .contains("at least 1"));
        assertOk(condition("haruhi:has_item", "{\"item\":\"#minecraft:logs\"}"));
        assertTrue(error(condition("haruhi:has_item", "{\"item\":\"Bad Item!\"}")).contains("not an item id"));
    }

    @Test
    void advancementCriterionCannotBeEmpty() {
        assertTrue(error(condition("haruhi:advancement",
                "{\"advancement\":\"minecraft:story/root\",\"criterion\":\"\"}")).contains("non-empty"));
        assertOk(condition("haruhi:advancement", "{\"advancement\":\"minecraft:story/root\"}"));
    }

    @Test
    void aScoreboardTagHasNoSpaces() {
        assertTrue(error(condition("haruhi:tag", "{\"tag\":\"two words\"}")).contains("whitespace"));
        assertOk(condition("haruhi:tag", "{\"tag\":\"guild_member\"}"));
    }

    @Test
    void permissionLevelIsZeroToFour() {
        assertTrue(error(condition("haruhi:permission", "{\"level\":5}")).contains("from 0 to 4"));
        assertOk(condition("haruhi:permission", "{\"level\":4}"));
    }

    @Test
    void timeBoundsAreDayTicks() {
        assertTrue(error(condition("haruhi:time", "{\"from\":24000,\"to\":100}")).contains("from 0 to 23999"));
        assertOk(condition("haruhi:time", "{\"from\":22000,\"to\":1000}"));
    }

    @Test
    void aTimeRangeThatWrapsPastMidnightIncludesBothSides() {
        assertTrue(WorldConditions.inTimeRange(23500, 22000, 1000));
        assertTrue(WorldConditions.inTimeRange(500, 22000, 1000));
        assertFalse(WorldConditions.inTimeRange(12000, 22000, 1000));
        assertTrue(WorldConditions.inTimeRange(12000, 1000, 22000));
        assertFalse(WorldConditions.inTimeRange(500, 1000, 22000));
    }

    @Test
    void weatherIsClearRainOrThunder() {
        assertTrue(error(condition("haruhi:weather", "{\"weather\":\"snow\"}")).contains("clear, rain or thunder"));
        assertOk(condition("haruhi:weather", "{\"weather\":\"thunder\"}"));
    }

    @Test
    void flagSetAndAddRejectAnUndeclaredFlag() {
        assertTrue(error(action("haruhi:flag/set", "{\"namespace\":\"" + NPC + "\",\"flag\":\"secret\"}"))
                .startsWith(WHERE));
        assertTrue(error(action("haruhi:flag/add", "{\"namespace\":\"" + NPC + "\",\"flag\":\"secret\"}"))
                .startsWith(WHERE));
    }

    @Test
    void flagAddDeltaMustBeWhole() {
        assertTrue(error(action("haruhi:flag/add", "{\"namespace\":\"" + NPC + "\",\"flag\":\"count\",\"delta\":1.5}"))
                .contains("whole number"));
    }

    @Test
    void flagClearNeedsItsFlag() {
        assertTrue(error(action("haruhi:flag/clear", "{\"namespace\":\"" + NPC + "\"}")).contains("'flag' is required"));
    }

    @Test
    void gotoNeedsANodeAndEndTakesNothing() {
        assertTrue(error(action("haruhi:goto", "{\"node\":\"\"}")).contains("non-empty"));
        assertTrue(error(action("haruhi:end", "{\"node\":\"x\"}")).contains("no argument named 'node'"));
        assertOk(action("haruhi:end", "{}"));
    }

    @Test
    void openNeedsAGraphId() {
        assertTrue(error(action("haruhi:open", "{\"graph\":\"Bad Id\"}")).contains("not an id"));
        assertOk(action("haruhi:open", "{\"graph\":\"haruhi:test/other\"}"));
    }

    @Test
    void soundVolumeIsNotNegativeAndPitchIsAboveZero() {
        assertTrue(error(action("haruhi:sound", "{\"sound\":\"minecraft:block.note_block.pling\",\"volume\":-1}"))
                .contains("not be negative"));
        assertTrue(error(action("haruhi:sound", "{\"sound\":\"minecraft:block.note_block.pling\",\"pitch\":0}"))
                .contains("above zero"));
        assertOk(action("haruhi:sound", "{\"sound\":\"minecraft:block.note_block.pling\",\"volume\":0.5}"));
    }

    @Test
    void giveNeedsItsOnceFlagAndAcceptsOnlyDeclaredFlags() {
        assertTrue(error(action("haruhi:give", "{\"loot_table\":\"minecraft:gameplay/fishing\"}"))
                .contains("'once' is required"));
        assertTrue(error(action("haruhi:give", "{\"loot_table\":\"minecraft:gameplay/fishing\","
                + "\"once\":{\"namespace\":\"" + NPC + "\",\"flag\":\"secret\"}}")).startsWith(WHERE));
        assertTrue(error(action("haruhi:give", "{\"loot_table\":\"minecraft:gameplay/fishing\","
                + "\"once\":{\"namespace\":\"" + NPC + "\",\"flag\":\"met\",\"extra\":1}}"))
                .contains("no argument named 'extra'"));
        assertOk(action("haruhi:give", "{\"loot_table\":\"minecraft:gameplay/fishing\","
                + "\"once\":{\"namespace\":\"" + NPC + "\",\"flag\":\"met\"}}"));
    }

    @Test
    void aCommandNeedsItsFirstWordOnTheAllowlist() {
        DialogueConfig empty = new DialogueConfig(2, List.of());
        assertTrue(error(CommandAction.parser(empty).parse(json("{\"command\":\"give @s apple\"}"), CONTEXT))
                .contains("allowlist"));

        DialogueConfig allowed = new DialogueConfig(2, List.of("give"));
        assertOk(CommandAction.parser(allowed).parse(json("{\"command\":\"/give @s apple\"}"), CONTEXT));
        assertTrue(error(CommandAction.parser(allowed).parse(json("{\"command\":\"kill @e\"}"), CONTEXT))
                .contains("'kill'"));
    }

    @Test
    void aCommandWithNoWordIsRefused() {
        DialogueConfig allowed = new DialogueConfig(2, List.of("give"));
        assertTrue(error(CommandAction.parser(allowed).parse(json("{\"command\":\"   \"}"), CONTEXT))
                .contains("no command"));
    }

    @Test
    void animationNeedsAName() {
        assertTrue(error(action("haruhi:animation", "{}")).contains("'animation' is required"));
        assertOk(action("haruhi:animation", "{\"animation\":\"talk\"}"));
    }

    @Test
    void messageNeedsText() {
        assertTrue(error(action("haruhi:message", "{}")).contains("'text' is required"));
    }

    @Test
    void theRegistryHoldsEveryBuiltIn() {
        for (String id : List.of("haruhi:all_of", "haruhi:any_of", "haruhi:not", "haruhi:flag", "haruhi:visits",
                "haruhi:predicate", "haruhi:has_item", "haruhi:advancement", "haruhi:tag", "haruhi:permission",
                "haruhi:time", "haruhi:weather")) {
            assertTrue(DialogueRegistries.condition(Identifier.parse(id)).isPresent(), id);
        }
        for (String id : List.of("haruhi:flag/set", "haruhi:flag/add", "haruhi:flag/clear", "haruhi:goto",
                "haruhi:end", "haruhi:open", "haruhi:animation", "haruhi:sound", "haruhi:message", "haruhi:give",
                "haruhi:command")) {
            assertTrue(DialogueRegistries.action(Identifier.parse(id)).isPresent(), id);
        }
    }
}
