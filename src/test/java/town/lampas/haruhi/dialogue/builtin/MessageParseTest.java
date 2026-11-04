package town.lampas.haruhi.dialogue.builtin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.dialogue.api.DialogueAction;
import town.lampas.haruhi.dialogue.api.DialogueRegistries;
import town.lampas.haruhi.dialogue.api.ParseContext;

/**
 * {@code haruhi:message} parses a text component through the component codec, which needs Minecraft bootstrapped.
 */
class MessageParseTest {
    private static final ParseContext CONTEXT = new ParseContext(ContentBundle.EMPTY,
            Identifier.fromNamespaceAndPath("haruhi", "test/graph"), "start");

    @BeforeAll
    static void setUp() {
        TestBuiltins.ensureBootstrapped();
        TestBuiltins.ensureRegistered();
    }

    @Test
    void aPlainStringIsATextComponent() {
        DataResult<DialogueAction> parsed = parse("{\"text\":\"Welcome\"}");
        assertTrue(parsed.result().isPresent(), () -> parsed.error().map(e -> e.message()).orElse("?"));
    }

    @Test
    void aMessageWithoutTextIsRefused() {
        DataResult<DialogueAction> parsed = parse("{}");
        assertTrue(parsed.error().isPresent(), "expected a parse error");
        assertTrue(parsed.error().get().message().contains("'text' is required"), parsed.error().get().message());
    }

    private static DataResult<DialogueAction> parse(String json) {
        return DialogueRegistries.action(Identifier.parse("haruhi:message")).orElseThrow()
                .parse(JsonParser.parseString(json).getAsJsonObject(), CONTEXT);
    }
}
