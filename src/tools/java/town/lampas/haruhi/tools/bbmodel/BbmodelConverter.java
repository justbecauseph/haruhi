package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Converts a Blockbench project into the three GeckoLib assets a Haruhi NPC profile names: a Bedrock geometry file, a
 * Bedrock animation file (when the project has animations) and one texture.
 */
public final class BbmodelConverter {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");

    private BbmodelConverter() {}

    /** The converted assets and what the conversion reported. */
    public record Conversion(JsonObject geometry, JsonObject animations, BufferedImage texture,
                             ConversionReport report) {
        /** The geometry file as written. */
        public String geometryJson() {
            return JsonText.write(geometry);
        }

        /** The animation file as written, or null when there is none. */
        public String animationsJson() {
            return animations == null ? null : JsonText.write(animations);
        }
    }

    /** Where {@link #write} puts each file, under a resource pack or mod resources root. */
    public record Output(Path geometry, Path animations, Path texture) {
        static Output of(Path root, String namespace, String name) {
            Path assets = root.resolve("assets").resolve(namespace);
            return new Output(assets.resolve("geckolib/models/" + name + ".geo.json"),
                    assets.resolve("geckolib/animations/" + name + ".animation.json"),
                    assets.resolve("textures/entity/" + name + ".png"));
        }
    }

    /** Reads and converts a .bbmodel file. Textures not embedded in it are looked up next to it. */
    public static Conversion convert(Path bbmodel, String name) throws IOException {
        String text = Files.readString(bbmodel, StandardCharsets.UTF_8);
        if (text.startsWith("<lz>")) {
            throw new IOException("the file is a compressed Blockbench project; save it uncompressed and convert again");
        }
        JsonElement json;
        try {
            json = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw new IOException("not a JSON file: " + e.getMessage(), e);
        }
        if (!json.isJsonObject()) {
            throw new IOException("not a Blockbench project: the file is not a JSON object");
        }
        return convert(json.getAsJsonObject(), name, bbmodel.toAbsolutePath().getParent());
    }

    /** Converts a parsed project. {@code sourceDirectory} may be null when every texture is embedded. */
    public static Conversion convert(JsonObject bbmodel, String name, Path sourceDirectory) throws IOException {
        if (!PATH.matcher(name).matches()) {
            throw new IllegalArgumentException("name must be a resource path ([a-z0-9_.-] and /): " + name);
        }
        ConversionReport report = new ConversionReport();
        BbProject project;
        try {
            project = new BbProject(bbmodel, report);
        } catch (RuntimeException e) {
            throw new IOException("cannot read the project: " + e.getMessage(), e);
        }
        report.formatVersion = project.formatVersion;
        report.modelFormat = project.modelFormat;
        GeometryCompiler.Result geometry = GeometryCompiler.compile(project, name, sourceDirectory, report);
        JsonObject animations = AnimationCompiler.compile(project, geometry, report);
        return new Conversion(geometry.geometry(), animations, geometry.atlas().image(), report);
    }

    /** Writes the conversion's files for {@code namespace:name} under {@code root}, creating directories. */
    public static Output write(Conversion conversion, Path root, String namespace, String name) throws IOException {
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("namespace must be [a-z0-9_.-]: " + namespace);
        }
        if (!PATH.matcher(name).matches()) {
            throw new IllegalArgumentException("name must be a resource path ([a-z0-9_.-] and /): " + name);
        }
        Output output = Output.of(root, namespace, name);
        Files.createDirectories(output.geometry().getParent());
        Files.writeString(output.geometry(), conversion.geometryJson(), StandardCharsets.UTF_8);
        if (conversion.animations() != null) {
            Files.createDirectories(output.animations().getParent());
            Files.writeString(output.animations(), conversion.animationsJson(), StandardCharsets.UTF_8);
        } else if (Files.exists(output.animations())) {
            conversion.report().warn("an animation file from an earlier conversion is still there; it was left alone",
                    output.animations().toString());
        }
        Files.createDirectories(output.texture().getParent());
        if (!ImageIO.write(conversion.texture(), "png", output.texture().toFile())) {
            throw new IOException("no PNG writer for the texture");
        }
        return output;
    }
}
