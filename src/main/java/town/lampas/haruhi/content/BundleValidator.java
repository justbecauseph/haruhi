package town.lampas.haruhi.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.util.StrictJsonParser;

/**
 * Parses bundle JSON and validates the whole bundle. Every problem goes into one error, one per line, so a single pass
 * shows everything an author has to fix.
 */
final class BundleValidator {
    private BundleValidator() {}

    static DataResult<ValidatedBundle> validate(String json, DynamicOps<JsonElement> ops) {
        JsonElement root;
        try {
            root = StrictJsonParser.parse(json);
        } catch (JsonParseException e) {
            return DataResult.error(() -> "bundle: not valid JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) {
            return DataResult.error(() -> "bundle: must be a JSON object");
        }

        Problems problems = new Problems();
        BundleReader.Read read = BundleReader.read(root.getAsJsonObject(), ops, problems);
        BundleChecks.checkSpecs(read.bundle(), problems);
        BundleChecks.checkReachability(read.bundle(), read.completeGraphs(), problems);
        if (!problems.isEmpty()) {
            return DataResult.error(problems::text);
        }

        String canonical = BundleDigest.canonicalJson(root);
        return DataResult.success(new ValidatedBundle(read.bundle(), canonical, BundleDigest.sha256Hex(canonical)));
    }
}
