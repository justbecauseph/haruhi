package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static town.lampas.haruhi.content.ContentTestSupport.EXTENSION;
import static town.lampas.haruhi.content.ContentTestSupport.GRAPH_REF_EXTENSION;
import static town.lampas.haruhi.content.ContentTestSupport.MUTATING_EXTENSION;
import static town.lampas.haruhi.content.ContentTestSupport.SILENT_EXTENSION;
import static town.lampas.haruhi.content.ContentTestSupport.THROWING_EXTENSION;
import static town.lampas.haruhi.content.ContentTestSupport.WRONG_CLASS_EXTENSION;
import static town.lampas.haruhi.content.ContentTestSupport.baseBundle;
import static town.lampas.haruhi.content.ContentTestSupport.bundleOf;
import static town.lampas.haruhi.content.ContentTestSupport.element;
import static town.lampas.haruhi.content.ContentTestSupport.id;
import static town.lampas.haruhi.content.ContentTestSupport.node;
import static town.lampas.haruhi.content.ContentTestSupport.problems;
import static town.lampas.haruhi.content.ContentTestSupport.validate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.ContentTestSupport.TestSection;
import town.lampas.haruhi.content.api.ContentExtensions;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * The bundle's {@code extensions} object: each section goes through the extension registered under its id, an
 * unregistered id is refused, problems from a section are reported with the rest, and condition parsers see the parsed
 * sections.
 */
class BundleExtensionsTest {

    @BeforeAll
    static void boot() {
        ContentTestSupport.boot();
    }

    private static JsonObject withExtensions(String extensionsJson) {
        JsonObject bundle = baseBundle();
        bundle.add("extensions", element(extensionsJson));
        return bundle;
    }

    @Test
    void registeredSectionIsParsedIntoTheBundle() {
        DataResult<ValidatedBundle> result =
                validate(withExtensions("{\"" + EXTENSION + "\": {\"ids\": [\"a\", \"b\"]}}"));
        assertTrue(result.error().isEmpty(), () -> String.join("\n", problems(result)));

        ContentBundle bundle = bundleOf(result);
        assertEquals(Optional.of(new TestSection(List.of("a", "b"))),
                bundle.extension(ContentTestSupport.extensionKey));
    }

    @Test
    void bundleWithoutTheSectionHasNoValue() {
        ContentBundle bundle = bundleOf(validate(baseBundle()));
        assertEquals(Optional.empty(), bundle.extension(ContentTestSupport.extensionKey));
    }

    @Test
    void unregisteredExtensionIsRefused() {
        List<String> problems = problems(validate(withExtensions("{\"haruhi_test:nobody\": {}}")));
        assertEquals(List.of("bundle: extension haruhi_test:nobody is not registered by any installed mod"), problems);
    }

    @Test
    void extensionsMustBeAnObject() {
        JsonObject bundle = baseBundle();
        bundle.add("extensions", new JsonArray());
        assertEquals(List.of("bundle: extensions must be an object"), problems(validate(bundle)));
    }

    @Test
    void invalidIdKeyIsRefused() {
        List<String> problems = problems(validate(withExtensions("{\"Not An Id\": {}}")));
        assertEquals(List.of("bundle: extensions key 'Not An Id' is not a valid id"), problems);
    }

    @Test
    void twoKeysForOneIdAreRefused() {
        List<String> problems = problems(validate(withExtensions("{\"minecraft:other\": {}, \"other\": {}}")));
        assertTrue(problems.contains("bundle: extensions keys 'minecraft:other' and 'other' both name minecraft:other"),
                () -> String.join("\n", problems));
    }

    @Test
    void everyProblemInASectionIsReportedWithTheRest() {
        JsonObject bundle = withExtensions("{\"" + EXTENSION + "\": {\"ids\": [\"a\", 3, \"\"], \"idz\": []}}");
        node(bundle, "bye").remove("text");

        List<String> problems = problems(validate(bundle));
        assertTrue(problems.contains("extension haruhi_test:ext: unknown key 'idz'"), () -> String.join("\n", problems));
        assertTrue(problems.contains("extension haruhi_test:ext, ids entry 2: must be a non-empty string"),
                () -> String.join("\n", problems));
        assertTrue(problems.contains("extension haruhi_test:ext, ids entry 3: must be a non-empty string"),
                () -> String.join("\n", problems));
        assertTrue(problems.stream().anyMatch(line -> line.startsWith("haruhi_test:gate#bye:")),
                () -> "the graph problem is missing:\n" + String.join("\n", problems));
    }

    @Test
    void parserThatThrowsIsAProblem() {
        List<String> problems = problems(validate(withExtensions("{\"" + THROWING_EXTENSION + "\": {}}")));
        assertEquals(List.of("extension haruhi_test:throws: parsing failed with java.lang.IllegalStateException: boom"),
                problems);
    }

    @Test
    void parserThatReturnsNothingWithoutAProblemIsRefused() {
        List<String> problems = problems(validate(withExtensions("{\"" + SILENT_EXTENSION + "\": {}}")));
        assertEquals(List.of("extension haruhi_test:silent: is invalid"), problems);
    }

    @Test
    void parserThatReturnsTheWrongClassIsRefused() {
        List<String> problems = problems(validate(withExtensions("{\"" + WRONG_CLASS_EXTENSION + "\": {}}")));
        assertEquals(List.of("extension haruhi_test:wrong_class: parser returned a java.lang.String, not a "
                + TestSection.class.getName()), problems);
    }

    @Test
    void parserThatChangesItsSectionDoesNotChangeTheStoredRevision() {
        JsonObject bundle = withExtensions("{\"" + MUTATING_EXTENSION + "\": {\"ids\": [\"kept\"]}}");
        String expected = BundleDigest.canonicalJson(bundle);

        ValidatedBundle validated = validate(bundle).result().orElseThrow();
        assertEquals(expected, validated.canonicalJson());
        assertEquals(BundleDigest.sha256Hex(expected), validated.digest());
    }

    @Test
    void declaredGraphsIncludeGraphsThatFailedToParse() {
        JsonObject bundle = withExtensions("{\"" + GRAPH_REF_EXTENSION + "\": {\"graph\": \"haruhi_test:gate\"}}");
        ContentTestSupport.graph(bundle).remove("start");

        List<String> problems = problems(validate(bundle));
        assertTrue(problems.stream().noneMatch(line -> line.startsWith("extension ")),
                () -> "the extension reported a declared graph as missing:\n" + String.join("\n", problems));
        assertTrue(problems.stream().anyMatch(line -> line.startsWith("haruhi_test:gate")),
                () -> "the graph problem is missing:\n" + String.join("\n", problems));
    }

    @Test
    void conditionParsersSeeTheSection() {
        JsonObject known = withExtensions("{\"" + EXTENSION + "\": {\"ids\": [\"gate_pass\"]}}");
        onlyIf(known).add(element("{\"type\": \"haruhi_test:ext_id\", \"id\": \"gate_pass\"}"));
        DataResult<ValidatedBundle> accepted = validate(known);
        assertTrue(accepted.error().isEmpty(), () -> String.join("\n", problems(accepted)));

        JsonObject unknown = withExtensions("{\"" + EXTENSION + "\": {\"ids\": [\"gate_pass\"]}}");
        onlyIf(unknown).add(element("{\"type\": \"haruhi_test:ext_id\", \"id\": \"forged\"}"));
        assertEquals(List.of("haruhi_test:gate#hello: id forged is not in haruhi_test:ext"),
                problems(validate(unknown)));
    }

    @Test
    void digestCoversExtensions() {
        String first = validate(withExtensions("{\"" + EXTENSION + "\": {\"ids\": [\"a\"]}}"))
                .result().orElseThrow().digest();
        String second = validate(withExtensions("{\"" + EXTENSION + "\": {\"ids\": [\"b\"]}}"))
                .result().orElseThrow().digest();
        assertNotEquals(first, second);
    }

    @Test
    void registeringAnIdTwiceThrows() {
        assertThrows(IllegalStateException.class, () ->
                ContentExtensions.register(id(EXTENSION), TestSection.class, (section, context) -> Optional.empty()));
    }

    private static JsonArray onlyIf(JsonObject bundle) {
        return node(bundle, "hello").getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonArray("only_if");
    }
}
