package town.lampas.haruhi.content.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ParamsRedactionTest {
    private static final Object PARAMS = JsonParser.parseString("{\"token\":\"secret\"}");

    @Test
    void theParamsOfAHaruhiCallAreReplaced() {
        Object[] arguments = {"haruhi:content/validate", PARAMS, "Params passed by-name, but expected param [params]"};

        Object[] redacted = ParamsRedaction.redact(arguments);

        assertArrayEquals(new Object[] {"haruhi:content/validate", ParamsRedaction.REDACTED, arguments[2]}, redacted);
        assertSame(PARAMS, arguments[1], "the caller's array is not changed");
    }

    @Test
    void otherCallsAreLoggedAsVanillaLogsThem() {
        Object[] arguments = {"minecraft:players/kick", PARAMS, "message"};

        assertSame(arguments, ParamsRedaction.redact(arguments));
    }

    @Test
    void unexpectedArgumentsAreLeftAlone() {
        Object[] none = {};
        Object[] notAName = {42, PARAMS};

        assertSame(none, ParamsRedaction.redact(none));
        assertSame(notAName, ParamsRedaction.redact(notAName));
    }
}
