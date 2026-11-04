package town.lampas.haruhi.dialogue.builtin;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import town.lampas.haruhi.dialogue.DialogueConfig;

/**
 * Registers the built-ins once per JVM, because the registry refuses a second registration. Tests that parse text
 * components also need Minecraft bootstrapped, which happens here too.
 */
final class TestBuiltins {
    private static boolean registered;
    private static boolean bootstrapped;

    private TestBuiltins() {}

    static synchronized void ensureRegistered() {
        if (!registered) {
            Builtins.register(new DialogueConfig(2, List.of("give", "say")));
            registered = true;
        }
    }

    static synchronized void ensureBootstrapped() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }
}
