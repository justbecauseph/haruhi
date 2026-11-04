package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/**
 * The canonical form and digest. The expected digests were computed outside the code with {@code sha256sum} over the
 * canonical text written here, so they check the implementation against an independent SHA-256.
 */
class BundleDigestTest {

    @Test
    void sha256MatchesAnIndependentComputation() {
        assertEquals("cdab067e9f3beb32d1252cfd63e492592fecbf591b0d08cadb24bb17f3864246",
                BundleDigest.sha256Hex("{\"a\":\"x\",\"b\":1}"));
    }

    @Test
    void canonicalFormSortsKeysAtEveryDepthAndKeepsArrayOrder() {
        String input = "{\"b\":\"é\",\"a\":{\"d\":1.0,\"c\":[2,1,{\"y\":true,\"x\":null}]}}";
        String canonical = BundleDigest.canonicalJson(JsonParser.parseString(input));
        assertEquals("{\"a\":{\"c\":[2,1,{\"x\":null,\"y\":true}],\"d\":1.0},\"b\":\"é\"}", canonical);
        assertEquals("4d9f2a95e976a1e48001b9c46f14c526ecfd48ed6ddcbe6abb0937b9fecda87c",
                BundleDigest.sha256Hex(canonical));
    }

    @Test
    void keyOrderAndWhitespaceDoNotChangeTheCanonicalForm() {
        String compact = "{\"a\":1,\"b\":[true,\"x\"]}";
        String spaced = """
                {
                    "b" : [ true , "x" ],
                    "a" : 1
                }
                """;
        assertEquals(BundleDigest.canonicalJson(JsonParser.parseString(compact)),
                BundleDigest.canonicalJson(JsonParser.parseString(spaced)));
    }

    @Test
    void arrayOrderChangesTheCanonicalForm() {
        assertNotEquals(
                BundleDigest.canonicalJson(JsonParser.parseString("[1,2]")),
                BundleDigest.canonicalJson(JsonParser.parseString("[2,1]")));
    }
}
