package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/** Small synthetic Blockbench projects for the converter tests, built as a .bbmodel file stores them. */
final class TestProjects {
    private TestProjects() {}

    /** A project of {@code format} (as meta.format_version) with the given resolution and no content. */
    static JsonObject project(String formatVersion, String modelFormat, boolean boxUv, int width, int height) {
        JsonObject meta = new JsonObject();
        meta.addProperty("format_version", formatVersion);
        meta.addProperty("model_format", modelFormat);
        meta.addProperty("box_uv", boxUv);
        JsonObject resolution = new JsonObject();
        resolution.addProperty("width", width);
        resolution.addProperty("height", height);
        JsonObject project = new JsonObject();
        project.add("meta", meta);
        project.add("resolution", resolution);
        project.add("elements", new JsonArray());
        project.add("outliner", new JsonArray());
        project.add("textures", new JsonArray());
        return project;
    }

    /** A texture of {@code pixels} filled with {@code argb}, embedded as a data URL, with its UV size. */
    static JsonObject texture(JsonObject project, String name, BufferedImage pixels, int uvWidth, int uvHeight) {
        JsonObject texture = new JsonObject();
        texture.addProperty("name", name);
        texture.addProperty("uuid", name + "-uuid");
        texture.addProperty("width", pixels.getWidth());
        texture.addProperty("height", pixels.getHeight());
        texture.addProperty("uv_width", uvWidth);
        texture.addProperty("uv_height", uvHeight);
        texture.addProperty("source", dataUrl(pixels));
        project.getAsJsonArray("textures").add(texture);
        return texture;
    }

    static BufferedImage filled(int width, int height, int argb) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    /** A legacy inline outliner group (format before 5.0). Children are element uuids or other groups. */
    static JsonObject group(String uuid, String name, double[] origin, double[] rotation, JsonElement... children) {
        JsonObject group = new JsonObject();
        group.addProperty("name", name);
        group.addProperty("uuid", uuid);
        group.add("origin", array(origin));
        if (rotation != null) {
            group.add("rotation", array(rotation));
        }
        group.addProperty("export", true);
        JsonArray list = new JsonArray();
        for (JsonElement child : children) {
            list.add(child);
        }
        group.add("children", list);
        return group;
    }

    /** A per-face cube whose six faces all use texture 0 with the UV [0, 0, 1, 1]; tests change the faces they need. */
    static JsonObject cube(JsonObject project, String uuid, String name, double[] from, double[] to) {
        JsonObject cube = new JsonObject();
        cube.addProperty("name", name);
        cube.addProperty("uuid", uuid);
        cube.addProperty("type", "cube");
        cube.addProperty("box_uv", false);
        cube.add("from", array(from));
        cube.add("to", array(to));
        cube.add("origin", array(0, 0, 0));
        JsonObject faces = new JsonObject();
        for (String face : GeometryCompiler.FACES) {
            faces.add(face, face(0, 0, 0, 1, 1));
        }
        cube.add("faces", faces);
        project.getAsJsonArray("elements").add(cube);
        return cube;
    }

    static JsonObject face(int texture, double u1, double v1, double u2, double v2) {
        JsonObject face = new JsonObject();
        face.add("uv", array(u1, v1, u2, v2));
        face.addProperty("texture", texture);
        return face;
    }

    static JsonArray array(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    static JsonElement uuid(String uuid) {
        return JsonParser.parseString("\"" + uuid + "\"");
    }

    static String dataUrl(BufferedImage image) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The first geometry's bone named {@code name}. */
    static JsonObject bone(JsonObject geometry, String name) {
        for (JsonElement bone : geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject()
                .getAsJsonArray("bones")) {
            if (bone.getAsJsonObject().get("name").getAsString().equals(name)) {
                return bone.getAsJsonObject();
            }
        }
        throw new AssertionError("no bone " + name + " in " + geometry);
    }

    static JsonObject description(JsonObject geometry) {
        return geometry.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonObject("description");
    }

    static JsonArray json(double... values) {
        return array(values);
    }
}
