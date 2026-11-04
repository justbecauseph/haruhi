package town.lampas.haruhi.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Path confinement for the bundle files that {@code /haruhi content import} and {@code validate} read. */
class BundleFilesTest {

    @TempDir
    Path configDir;

    @Test
    void resolvesARegularFileUnderTheBundlesDirectory() throws IOException {
        Path file = writeBundle("greeter.json");
        assertEquals(file.toAbsolutePath().normalize(), BundleFiles.resolve(configDir, "greeter.json"));
    }

    @Test
    void trimsTheNameBeforeResolving() throws IOException {
        Path file = writeBundle("greeter.json");
        assertEquals(file.toAbsolutePath().normalize(), BundleFiles.resolve(configDir, "  greeter.json  "));
    }

    @Test
    void rejectsParentTraversalOutOfTheDirectory() throws IOException {
        Files.createDirectories(configDir.resolve("haruhi"));
        Files.writeString(configDir.resolve("haruhi").resolve("bundle.json"), "{}");
        writeBundle("greeter.json");
        assertThrows(IllegalArgumentException.class, () -> BundleFiles.resolve(configDir, "../bundle.json"));
        assertThrows(IllegalArgumentException.class, () -> BundleFiles.resolve(configDir, "sub/../../bundle.json"));
    }

    @Test
    void rejectsAbsolutePathsOutsideTheDirectory() throws IOException {
        Path outside = configDir.resolve("outside.json");
        Files.writeString(outside, "{}");
        assertThrows(IllegalArgumentException.class,
                () -> BundleFiles.resolve(configDir, outside.toAbsolutePath().toString()));
    }

    @Test
    void rejectsAMissingFile() {
        assertThrows(IllegalArgumentException.class, () -> BundleFiles.resolve(configDir, "missing.json"));
    }

    @Test
    void rejectsADirectory() throws IOException {
        Files.createDirectories(BundleFiles.bundlesDirectory(configDir).resolve("nested"));
        assertThrows(IllegalArgumentException.class, () -> BundleFiles.resolve(configDir, "nested"));
    }

    @Test
    void rejectsASymbolicLinkThatLeavesTheDirectory() throws IOException {
        Path outside = configDir.resolve("outside.json");
        Files.writeString(outside, "{}");
        Path link = BundleFiles.bundlesDirectory(configDir).resolve("link.json");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("This platform does not allow creating symbolic links: " + e);
        }
        assertThrows(IllegalArgumentException.class, () -> BundleFiles.resolve(configDir, "link.json"));
    }

    private Path writeBundle(String name) throws IOException {
        Path directory = BundleFiles.bundlesDirectory(configDir);
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.writeString(file, "{\"schema\":1}");
        return file;
    }
}
