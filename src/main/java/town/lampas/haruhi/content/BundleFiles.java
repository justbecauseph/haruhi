package town.lampas.haruhi.content;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.Util;

/**
 * Bundle files on disk: {@code config/haruhi/bundle.json}, read at startup, and the files under
 * {@code config/haruhi/bundles/} that {@code /haruhi content} names. Reads run on the I/O pool, not the server thread.
 */
final class BundleFiles {
    private BundleFiles() {}

    static Path bundlesDirectory(Path configDir) {
        return configDir.resolve("haruhi").resolve("bundles");
    }

    static Path startupFile(Path configDir) {
        return configDir.resolve("haruhi").resolve("bundle.json");
    }

    /**
     * The regular file {@code name} names under the bundles directory. Rejects any path that leaves that directory,
     * after normalising {@code ..} and following symbolic links.
     *
     * @throws IllegalArgumentException when the path escapes the directory or names no regular file
     * @throws IOException when the file system cannot resolve the path
     */
    static Path resolve(Path configDir, String name) throws IOException {
        Path root = bundlesDirectory(configDir).toAbsolutePath().normalize();
        Path file = root.resolve(name.strip()).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new IllegalArgumentException("A bundle must be a file under " + root);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("There is no bundle file " + name.strip() + " under " + root);
        }
        if (!file.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalArgumentException("The bundle " + name.strip() + " links outside " + root);
        }
        return file;
    }

    /** The text of a bundle file under the bundles directory. Fails with the reason from {@link #resolve}. */
    static CompletableFuture<String> read(Path configDir, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readString(resolve(configDir, name), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool());
    }

    /** The text of {@code file}, or empty when it is not a regular file. */
    static CompletableFuture<Optional<String>> readIfRegularFile(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.isRegularFile(file)
                        ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                        : Optional.<String>empty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, Util.ioPool());
    }
}
