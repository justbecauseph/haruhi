package town.lampas.haruhi.tools.bbmodel;

import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import javax.imageio.ImageIO;

/**
 * The one texture a GeckoLib model draws with, made from the bbmodel textures its faces use, and where each of those
 * textures' UV space lands in it.
 *
 * <p>Face UVs in a bbmodel are in the UV space of the face's texture: its {@code uv_width} x {@code uv_height} in formats
 * with per-texture UV sizes (Blockbench 4.9+, project.ts {@code getUVWidth}), else the project resolution. With one
 * texture whose UV size is whole, the model keeps that UV space as its texture_width/height, which is what Blockbench's
 * Bedrock export writes (bedrock.js compile: {@code texture_width: Project.texture_width}). With several textures they are
 * stitched into one atlas in pixels, and each face's UV is mapped from its texture's UV space into atlas pixels.
 */
final class TextureAtlas {
    /** Where one source texture's UV space lands: {@code atlas = offset + uv * scale}. */
    record Placement(String name, double offsetX, double offsetY, double scaleX, double scaleY, double uvWidth,
                     double uvHeight) {
        double u(double u) {
            return offsetX + u * scaleX;
        }

        double v(double v) {
            return offsetY + v * scaleY;
        }

        /** True if UVs land unchanged, so a box UV offset stays valid after adding the offset. */
        boolean unscaled() {
            return scaleX == 1 && scaleY == 1;
        }
    }

    private final Map<Integer, Placement> placements;
    private final BufferedImage image;
    private final int width;
    private final int height;
    private final boolean stitched;

    private TextureAtlas(Map<Integer, Placement> placements, BufferedImage image, int width, int height,
                         boolean stitched) {
        this.placements = placements;
        this.image = image;
        this.width = width;
        this.height = height;
        this.stitched = stitched;
    }

    Placement placement(int texture) {
        return placements.get(texture);
    }

    BufferedImage image() {
        return image;
    }

    /** texture_width of the geometry. */
    int width() {
        return width;
    }

    /** texture_height of the geometry. */
    int height() {
        return height;
    }

    boolean stitched() {
        return stitched;
    }

    /** One used source texture: its first animation frame and its UV size. */
    private record Source(int index, String name, BufferedImage frame, double uvWidth, double uvHeight) {}

    static TextureAtlas build(BbProject project, SortedSet<Integer> used, Path sourceDirectory, ConversionReport report)
            throws IOException {
        for (int i = 0; i < project.textures.size(); i++) {
            if (!used.contains(i)) {
                JsonObject texture = project.textures.get(i);
                report.texturesIgnored.add(textureName(texture, i) + " (no face uses it)");
            }
        }
        if (used.isEmpty()) {
            throw new IOException("no exported cube face uses a texture, so there is nothing to draw");
        }
        List<Source> sources = new ArrayList<>();
        for (int index : used) {
            sources.add(load(project, index, sourceDirectory, report));
        }
        if (sources.size() == 1) {
            Source only = sources.getFirst();
            report.texturesUsed.add(describe(only));
            if (isWhole(only.uvWidth) && isWhole(only.uvHeight)) {
                Map<Integer, Placement> placements = Map.of(only.index,
                        new Placement(only.name, 0, 0, 1, 1, only.uvWidth, only.uvHeight));
                report.textureLayout = "(" + only.name + ", in its own UV space)";
                return new TextureAtlas(placements, only.frame, (int) only.uvWidth, (int) only.uvHeight, false);
            }
            report.warn("texture UV size is not whole, so UVs were mapped to its pixels", only.name);
        }
        return stitch(sources, report);
    }

    private static TextureAtlas stitch(List<Source> sources, ConversionReport report) {
        List<Source> byHeight = new ArrayList<>(sources);
        byHeight.sort(Comparator.comparingInt((Source s) -> s.frame.getHeight()).reversed());
        Layout best = null;
        int widest = byHeight.stream().mapToInt(s -> s.frame.getWidth()).max().orElse(1);
        int total = byHeight.stream().mapToInt(s -> s.frame.getWidth()).sum();
        for (int limit = widest; limit <= total; limit++) {
            Layout layout = shelves(byHeight, limit);
            if (best == null || layout.better(best)) {
                best = layout;
            }
        }
        BufferedImage atlas = new BufferedImage(best.width, best.height, BufferedImage.TYPE_INT_ARGB);
        Map<Integer, Placement> placements = new LinkedHashMap<>();
        for (Source source : sources) {
            int[] at = best.positions.get(source.index);
            int w = source.frame.getWidth();
            int h = source.frame.getHeight();
            atlas.setRGB(at[0], at[1], w, h, source.frame.getRGB(0, 0, w, h, null, 0, w), 0, w);
            placements.put(source.index, new Placement(source.name, at[0], at[1],
                    source.frame.getWidth() / source.uvWidth, source.frame.getHeight() / source.uvHeight,
                    source.uvWidth, source.uvHeight));
            report.texturesUsed.add(describe(source) + " at " + at[0] + "," + at[1]);
        }
        report.textureLayout = sources.size() == 1 ? "(" + sources.getFirst().name + ", in pixels)"
                : "(atlas of " + sources.size() + " textures)";
        return new TextureAtlas(placements, atlas, best.width, best.height, true);
    }

    /** A shelf packing: textures in rows no wider than {@code limit}, tallest first. */
    private record Layout(int width, int height, Map<Integer, int[]> positions) {
        boolean better(Layout other) {
            long area = (long) width * height;
            long otherArea = (long) other.width * other.height;
            if (area != otherArea) {
                return area < otherArea;
            }
            int squareness = Math.abs(width - height);
            int otherSquareness = Math.abs(other.width - other.height);
            if (squareness != otherSquareness) {
                return squareness < otherSquareness;
            }
            return width < other.width;
        }
    }

    private static Layout shelves(List<Source> byHeight, int limit) {
        Map<Integer, int[]> positions = new LinkedHashMap<>();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        int width = 0;
        for (Source source : byHeight) {
            int w = source.frame.getWidth();
            if (x > 0 && x + w > limit) {
                y += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            positions.put(source.index, new int[] {x, y});
            x += w;
            width = Math.max(width, x);
            rowHeight = Math.max(rowHeight, source.frame.getHeight());
        }
        return new Layout(width, y + rowHeight, positions);
    }

    private static Source load(BbProject project, int index, Path sourceDirectory, ConversionReport report)
            throws IOException {
        JsonObject texture = project.textures.get(index);
        String name = textureName(texture, index);
        BufferedImage image = decode(texture, sourceDirectory, name);
        double uvWidth = project.resolutionWidth;
        double uvHeight = project.resolutionHeight;
        if (project.perTextureUvSize && texture.has("uv_width") && texture.has("uv_height")) {
            uvWidth = BbProject.number(texture, "uv_width", uvWidth);
            uvHeight = BbProject.number(texture, "uv_height", uvHeight);
        }
        // textures.js frameCount: a texture taller than its UV aspect is a flipbook; faces map onto its first frame.
        double ratio = (double) image.getWidth() / image.getHeight();
        int frames = 1;
        if (ratio != uvWidth / uvHeight) {
            int counted = (int) Math.ceil((uvWidth / uvHeight) / ratio - 0.05);
            if (counted > 1) {
                frames = counted;
            }
        }
        BufferedImage frame = image;
        if (frames > 1) {
            int frameHeight = image.getHeight() / frames;
            frame = image.getSubimage(0, 0, image.getWidth(), frameHeight);
            report.warn("animated texture: only its first frame is used, GeckoLib needs a .png.mcmeta to animate it",
                    name + " (" + frames + " frames)");
        }
        String mode = BbProject.string(texture, "render_mode", "default");
        if (!mode.equals("default") && !mode.equals("normal")) {
            report.warn("texture render_mode is not carried over; GeckoLib draws it as an ordinary texture",
                    name + " (" + mode + ")");
        }
        return new Source(index, name, frame, uvWidth, uvHeight);
    }

    /**
     * The texture's image: its embedded {@code data:} URL source, else its {@code relative_path} next to the bbmodel,
     * else its absolute {@code path}. Blockbench's bbmodel.js load prefers an existing file over the embedded copy; this
     * reads the embedded copy first, so the output does not depend on files on the author's machine.
     */
    private static BufferedImage decode(JsonObject texture, Path sourceDirectory, String name) throws IOException {
        String source = BbProject.string(texture, "source", "");
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma < 0 || !source.substring(0, comma).endsWith(";base64")) {
                throw new IOException("texture " + name + " has a data URL that is not base64");
            }
            byte[] bytes = Base64.getMimeDecoder().decode(source.substring(comma + 1));
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IOException("texture " + name + " has an embedded image that is not a readable PNG");
            }
            return image;
        }
        List<Path> candidates = new ArrayList<>();
        String relative = BbProject.string(texture, "relative_path", "");
        if (!relative.isEmpty() && sourceDirectory != null) {
            candidates.add(sourceDirectory.resolve(relative.replaceFirst("^[/\\\\]+", "")));
        }
        String path = BbProject.string(texture, "path", "");
        if (!path.isEmpty()) {
            candidates.add(Path.of(path));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                BufferedImage image = ImageIO.read(candidate.toFile());
                if (image != null) {
                    return image;
                }
            }
        }
        throw new IOException("texture " + name + " has no embedded image and no readable file at " + candidates);
    }

    static String textureName(JsonObject texture, int index) {
        String name = BbProject.string(texture, "name", "");
        return name.isEmpty() ? "texture " + index : name;
    }

    private static String describe(Source source) {
        String uv = JsonText.number(source.uvWidth) + "x" + JsonText.number(source.uvHeight);
        String pixels = source.frame.getWidth() + "x" + source.frame.getHeight();
        return source.name + " (" + pixels + (uv.equals(pixels) ? "" : ", UV " + uv) + ")";
    }

    private static boolean isWhole(double value) {
        return value > 0 && value == Math.rint(value);
    }
}
