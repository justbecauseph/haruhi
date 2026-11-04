package town.lampas.haruhi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code docs/bundle.schema.json}, the JSON Schema of the content bundle that editors use, loaded for the tests that
 * check it against the Java validator. Gradle runs the tests in the project directory, so the path is relative to it.
 */
public final class BundleSchema {
    public static final Path FILE = Path.of("docs", "bundle.schema.json");
    private static final String META_SCHEMA = "https://json-schema.org/draft/2020-12/schema";

    private final String text;
    private final JsonObject json;
    private final Schema schema;

    private BundleSchema(String text) {
        this.text = text;
        this.json = JsonParser.parseString(text).getAsJsonObject();
        this.schema = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(text, InputFormat.JSON);
    }

    public static BundleSchema load() {
        return new BundleSchema(read(FILE));
    }

    public static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The schema's errors for a bundle, one line each; empty when the schema accepts it. */
    public List<String> errors(JsonElement bundle) {
        return schema.validate(bundle.toString(), InputFormat.JSON).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }

    /** The errors of the schema file itself against the draft 2020-12 meta-schema, which the library ships. */
    public List<String> metaSchemaErrors() {
        Schema meta = SchemaRegistry.withDialect(Dialects.getDraft202012())
                .getSchema(SchemaLocation.of(META_SCHEMA));
        return meta.validate(text, InputFormat.JSON).stream()
                .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                .toList();
    }

    /** The parsed schema document. */
    public JsonObject json() {
        return json;
    }

    /** A definition under {@code $defs}. */
    public JsonObject definition(String name) {
        JsonObject definition = json.getAsJsonObject("$defs").getAsJsonObject(name);
        if (definition == null) {
            throw new IllegalArgumentException("bundle.schema.json has no $defs/" + name);
        }
        return definition;
    }

    /** The names under {@code properties} of an object schema. */
    public static Set<String> propertyNames(JsonObject objectSchema) {
        return new TreeSet<>(objectSchema.getAsJsonObject("properties").keySet());
    }
}
