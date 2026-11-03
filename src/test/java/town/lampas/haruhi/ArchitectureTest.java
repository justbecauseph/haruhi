package town.lampas.haruhi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The server never loads GeckoLib (the Lampas pack ships it client side) and never has client classes, so no compiled
 * class outside {@code town/lampas/haruhi/client/} may reference {@code com/geckolib} or {@code net/minecraft/client}.
 * Reads the class files' constant pools as bytes, which is enough to find the internal names.
 */
class ArchitectureTest {
    private static final String CLIENT_PACKAGE = "town/lampas/haruhi/client/";
    private static final List<String> CLIENT_ONLY = List.of("com/geckolib/", "net/minecraft/client/");

    @Test
    void commonClassesDoNotReferenceClientOnlyCode() throws IOException {
        String dirs = System.getProperty("haruhi.mainClasses");
        assertTrue(dirs != null && !dirs.isBlank(), "haruhi.mainClasses is not set by the build");
        List<String> violations = new ArrayList<>();
        for (String dir : dirs.split(File.pathSeparator)) {
            Path root = Path.of(dir);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                    if (name.startsWith(CLIENT_PACKAGE)) {
                        continue;
                    }
                    String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                    for (String forbidden : CLIENT_ONLY) {
                        if (bytes.contains(forbidden)) {
                            violations.add(name + " references " + forbidden);
                        }
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(), "Common classes reference client-only code:\n" + String.join("\n", violations));
    }
}
