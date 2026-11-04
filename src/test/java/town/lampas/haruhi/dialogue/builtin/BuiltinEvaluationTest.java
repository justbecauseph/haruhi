package town.lampas.haruhi.dialogue.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.FlagNamespace;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueCondition;
import town.lampas.haruhi.dialogue.api.DialogueContext;
import town.lampas.haruhi.dialogue.api.DialogueFlags;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;
import town.lampas.haruhi.storage.FlagKey;

/**
 * Runs the parsed built-in conditions and actions against a fake context. Expected results follow AGENTS.md and the
 * {@link DialogueFlags} contract: an unset flag reads as 0, 0 is not set, and any other value is set.
 */
class BuiltinEvaluationTest {
    private static final String NPC = "haruhi:test/npc";
    private static final FlagKey MET = new FlagKey(NPC, "met");
    private static final FlagKey COUNT = new FlagKey(NPC, "count");
    private static final ParseContext CONTEXT = new ParseContext(
            new ContentBundle(ContentBundle.SCHEMA,
                    Map.of(NPC, new FlagNamespace(NPC, Set.of("met", "count"))),
                    Map.of(), Map.of()),
            Identifier.fromNamespaceAndPath("haruhi", "test/graph"),
            "start");

    static {
        TestBuiltins.ensureRegistered();
    }

    /** A flag store with the contract's reads: 0 for an unset flag, and set only for a value other than 0. */
    private static final class Flags implements DialogueFlags {
        private final Map<FlagKey, Integer> values = new HashMap<>();

        @Override
        public int get(FlagKey key) {
            return values.getOrDefault(key, 0);
        }

        @Override
        public boolean isSet(FlagKey key) {
            return get(key) != 0;
        }

        @Override
        public void set(FlagKey key, int value) {
            values.put(key, value);
        }

        @Override
        public void add(FlagKey key, int delta) {
            values.merge(key, delta, Integer::sum);
        }

        @Override
        public void clear(FlagKey key) {
            values.remove(key);
        }
    }

    /** The context a condition or action sees. The built-ins under test read only flags and visits. */
    private static final class Context implements DialogueContext {
        private final Flags flags = new Flags();
        private int visits;

        @Override
        public ServerPlayer player() {
            throw new UnsupportedOperationException("not used by the flag and visit built-ins");
        }

        @Override
        public Optional<Entity> interlocutor() {
            return Optional.empty();
        }

        @Override
        public Identifier graphId() {
            return CONTEXT.graphId();
        }

        @Override
        public String nodeId() {
            return CONTEXT.nodeId();
        }

        @Override
        public ContentBundle content() {
            return CONTEXT.bundle();
        }

        @Override
        public DialogueFlags flags() {
            return flags;
        }

        @Override
        public int visits() {
            return visits;
        }

        @Override
        public void goTo(String nodeId) {}

        @Override
        public void end() {}

        @Override
        public void openGraph(Identifier graphId) {}
    }

    private static DialogueCondition condition(String type, String args) {
        return DialogueRegistries.condition(Identifier.parse(type)).orElseThrow()
                .parse(JsonParser.parseString(args).getAsJsonObject(), CONTEXT).result().orElseThrow();
    }

    private static DialogueAction action(String type, String args) {
        return DialogueRegistries.action(Identifier.parse(type)).orElseThrow()
                .parse(JsonParser.parseString(args).getAsJsonObject(), CONTEXT).result().orElseThrow();
    }

    private static DialogueCondition flagTest(String op, int value) {
        return condition("haruhi:flag", "{\"namespace\":\"" + NPC + "\",\"flag\":\"count\",\"op\":\"" + op
                + "\",\"value\":" + value + "}");
    }

    private static DialogueCondition flagSet(String flag, boolean wanted) {
        return condition("haruhi:flag", "{\"namespace\":\"" + NPC + "\",\"flag\":\"" + flag + "\",\"op\":\""
                + (wanted ? "set" : "unset") + "\"}");
    }

    /** Checks the six comparison ops of a stored count against the argument 1, in the order eq, ne, gt, gte, lt, lte. */
    private static void assertComparisons(int stored, boolean... expected) {
        Context context = new Context();
        context.flags.set(COUNT, stored);
        String[] ops = {"eq", "ne", "gt", "gte", "lt", "lte"};
        for (int i = 0; i < ops.length; i++) {
            assertEquals(expected[i], flagTest(ops[i], 1).test(context), "stored " + stored + " " + ops[i] + " 1");
        }
    }

    @Test
    void flagComparisonsCompareTheStoredValueWithTheArgument() {
        assertComparisons(0, false, true, false, false, true, true);
        assertComparisons(1, true, false, false, true, false, true);
        assertComparisons(2, false, true, true, true, false, false);
    }

    @Test
    void setAndUnsetTreatZeroAsNotSetAndAnyOtherValueAsSet() {
        Context context = new Context();
        assertFalse(flagSet("met", true).test(context), "an unset flag is not set");
        assertTrue(flagSet("met", false).test(context), "an unset flag is unset");

        context.flags.set(MET, 0);
        assertFalse(flagSet("met", true).test(context), "a stored 0 is not set");
        assertTrue(flagSet("met", false).test(context));

        context.flags.set(MET, 3);
        assertTrue(flagSet("met", true).test(context), "a stored 3 is set");
        assertFalse(flagSet("met", false).test(context));
    }

    @Test
    void visitComparisonsUseTheCountOfTheSession() {
        Context context = new Context();
        context.visits = 2;
        DialogueCondition gte2 = condition("haruhi:visits", "{\"op\":\"gte\",\"value\":2}");
        DialogueCondition gt2 = condition("haruhi:visits", "{\"op\":\"gt\",\"value\":2}");
        DialogueCondition eq2 = condition("haruhi:visits", "{\"op\":\"eq\",\"value\":2}");
        DialogueCondition lt2 = condition("haruhi:visits", "{\"op\":\"lt\",\"value\":2}");
        assertTrue(gte2.test(context));
        assertFalse(gt2.test(context));
        assertTrue(eq2.test(context));
        assertFalse(lt2.test(context));
    }

    @Test
    void allOfAnyOfAndNotCombineTheirParts() {
        Context context = new Context();
        context.flags.set(MET, 1);
        DialogueCondition metSet = flagSet("met", true);
        DialogueCondition countSet = flagSet("count", true);
        DialogueCondition allOfBoth = condition("haruhi:all_of", "{\"conditions\":["
                + "{\"type\":\"haruhi:flag\",\"namespace\":\"" + NPC + "\",\"flag\":\"met\",\"op\":\"set\"},"
                + "{\"type\":\"haruhi:flag\",\"namespace\":\"" + NPC + "\",\"flag\":\"count\",\"op\":\"set\"}]}");
        DialogueCondition anyOfBoth = condition("haruhi:any_of", "{\"conditions\":["
                + "{\"type\":\"haruhi:flag\",\"namespace\":\"" + NPC + "\",\"flag\":\"met\",\"op\":\"set\"},"
                + "{\"type\":\"haruhi:flag\",\"namespace\":\"" + NPC + "\",\"flag\":\"count\",\"op\":\"set\"}]}");
        DialogueCondition notMet = condition("haruhi:not", "{\"condition\":{\"type\":\"haruhi:flag\",\"namespace\":\""
                + NPC + "\",\"flag\":\"met\",\"op\":\"set\"}}");

        assertTrue(metSet.test(context));
        assertFalse(countSet.test(context));
        assertFalse(allOfBoth.test(context), "all_of needs both, and count is unset");
        assertTrue(anyOfBoth.test(context), "any_of needs one, and met is set");
        assertFalse(notMet.test(context), "not inverts a set flag");
    }

    @Test
    void flagSetWritesOneWhenNoValueIsGiven() {
        Context context = new Context();
        action("haruhi:flag/set", "{\"namespace\":\"" + NPC + "\",\"flag\":\"met\"}").run(context);
        assertEquals(1, context.flags.get(MET));
    }

    @Test
    void flagAddAddsOneWhenNoDeltaIsGiven() {
        Context context = new Context();
        DialogueAction add = action("haruhi:flag/add", "{\"namespace\":\"" + NPC + "\",\"flag\":\"count\"}");
        add.run(context);
        add.run(context);
        assertEquals(2, context.flags.get(COUNT));
    }

    @Test
    void flagClearLeavesTheFlagUnset() {
        Context context = new Context();
        context.flags.set(MET, 5);
        action("haruhi:flag/clear", "{\"namespace\":\"" + NPC + "\",\"flag\":\"met\"}").run(context);
        assertEquals(0, context.flags.get(MET));
        assertFalse(context.flags.isSet(MET));
    }
}
