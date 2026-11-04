package town.lampas.haruhi.tools.bbmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonPrimitive;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Blockbench's invertMolang (js/util/molang.ts), with results traced by hand through its loop: the sign of each top-level
 * term flips, a leading minus is dropped, and text inside brackets is left alone. And the GeckoLib 5.5.5 checks.
 */
class MolangTest {
    @Test
    void invertingAnExpressionFlipsEachTopLevelTerm() {
        // "m" starts a term while invert is set, so a "-" goes before it; the bracket and "* 10" are copied.
        assertEquals("-math.sin(query.anim_time * 90) * 10", Molang.invert("math.sin(query.anim_time * 90) * 10"));
        // The leading "-" is dropped and the "+" becomes "-".
        assertEquals("12.1-Math.sin(q.anim_time*180)*0.2", Molang.invert("-12.1+Math.sin(q.anim_time*180)*0.2"));
        // A "-" right after "*" belongs to the factor, so it is kept.
        assertEquals("-2*-q.x", Molang.invert("2*-q.x"));
        // Inverting twice gives the original back.
        assertEquals("-12.1+Math.sin(q.anim_time*180)*0.2",
                Molang.invert(Molang.invert("-12.1+Math.sin(q.anim_time*180)*0.2")));
    }

    @Test
    void invertingANumberOrAStatementListFollowsBlockbench() {
        assertEquals("0", Molang.invert("0"));
        assertEquals("-1.5", Molang.invert("1.5f"));
        // processMolangReturn: only the expression after the last ";" is the value; its leading space is kept.
        assertEquals("v.a = 2; -v.a", Molang.invert("v.a = 2; v.a"));
        assertEquals(new JsonPrimitive(-3.0), Molang.invert(new JsonPrimitive(3)));
    }

    @Test
    void exportValueTurnsNumericStringsIntoNumbersAndKeepsMolang() {
        assertEquals(new JsonPrimitive(2.5), Molang.exportValue(new JsonPrimitive("2.5")));
        assertEquals(new JsonPrimitive(0), Molang.exportValue(new JsonPrimitive("")));
        assertEquals(new JsonPrimitive("q.anim_time*2"), Molang.exportValue(new JsonPrimitive("q.anim_time\n*2")));
    }

    @Test
    void geckoLibProblemsNameUnknownFunctionsQueriesAndCharacters() {
        assertEquals(List.of(), Molang.geckoLibProblems("Math.sin(query.anim_time * 180) * 2"));
        assertEquals(List.of("GeckoLib has no Molang function math.noise"),
                Molang.geckoLibProblems("math.noise(q.anim_time)"));
        List<String> query = Molang.geckoLibProblems("q.modified_distance_moved * 2");
        assertEquals(1, query.size());
        assertTrue(query.getFirst().contains("query.modified_distance_moved"));
        assertTrue(Molang.geckoLibProblems("'text'").getFirst().contains("rejects characters"));
    }
}
