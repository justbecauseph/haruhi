package town.lampas.haruhi.content.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParamsTest {

    @Test
    void aMissingParamReadsAsAnEmptyObject() throws Params.Invalid {
        assertEquals(OptionalLong.empty(), Params.of(null).optionalWhole("limit", 1));
        assertEquals(Optional.empty(), Params.of(JsonParser.parseString("null")).string("before"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "5"})
    void aParamThatIsNotAnObjectIsInvalid(String json) {
        assertThrows(Params.Invalid.class, () -> Params.of(JsonParser.parseString(json)));
    }

    @Test
    void wholeNumbersAreReadFromTheirText() throws Params.Invalid {
        Params params = params("{ \"a\": 42, \"b\": 99999999999999999999999 }");

        assertEquals(42, params.whole("a", 1));
        assertEquals(Long.MAX_VALUE, params.whole("b", 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1e3", "-1", "\"5\"", "0", "true", "1e10000"})
    void anythingButAWholeNumberAtLeastTheMinimumIsInvalid(String value) {
        Params params = params("{ \"n\": " + value + " }");

        Params.Invalid invalid = assertThrows(Params.Invalid.class, () -> params.whole("n", 1));
        assertEquals("n", invalid.field());
    }

    @Test
    void aMissingRequiredFieldIsInvalidByName() {
        Params.Invalid invalid = assertThrows(Params.Invalid.class, () -> params("{}").uuid("request"));

        assertEquals("request", invalid.field());
    }

    @Test
    void uuidsAndShasAreCheckedByForm() throws Params.Invalid {
        UUID id = UUID.randomUUID();
        String sha = "AB".repeat(32);
        Params params = params("{ \"request\": \"" + id.toString().toUpperCase() + "\", \"sha256\": \"" + sha + "\" }");

        assertEquals(id, params.uuid("request"));
        assertEquals(sha.toLowerCase(), params.sha256("sha256"));
        assertThrows(Params.Invalid.class, () -> params("{ \"request\": \"1-1-1-1-1\" }").uuid("request"));
        assertThrows(Params.Invalid.class, () -> params("{ \"sha256\": \"abc\" }").sha256("sha256"));
    }

    @Test
    void aTokenWithSpacesOrControlCharactersIsInvalidAndNotQuoted() {
        JsonObject param = new JsonObject();
        param.addProperty("token", "secret value\r\nX-Injected: 1");
        Params params = params(param.toString());

        Params.Invalid invalid = assertThrows(Params.Invalid.class, () -> params.token("token"));
        assertFalse(invalid.getMessage().contains("secret"), invalid.getMessage());
    }

    @Test
    void namesMustBeShortPrintableAndNotBlank() throws Params.Invalid {
        assertEquals("portal:42 Sable", params("{ \"author\": \"portal:42 Sable\" }").name("author"));
        assertThrows(Params.Invalid.class, () -> params("{ \"author\": \"  \" }").name("author"));
        assertThrows(Params.Invalid.class, () -> params("{ \"author\": \"a\\nb\" }").name("author"));
        assertThrows(Params.Invalid.class,
                () -> params("{ \"author\": \"" + "a".repeat(Params.MAX_NAME_LENGTH + 1) + "\" }").name("author"));
    }

    private static Params params(String json) {
        JsonElement element = JsonParser.parseString(json);
        try {
            return Params.of(element);
        } catch (Params.Invalid e) {
            throw new AssertionError(e);
        }
    }
}
