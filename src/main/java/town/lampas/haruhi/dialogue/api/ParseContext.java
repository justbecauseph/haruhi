package town.lampas.haruhi.dialogue.api;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.api.ContentExtensionKey;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.FlagNamespace;
import town.lampas.haruhi.storage.FlagKey;

/**
 * Where a condition, action or provider is being parsed: the bundle being validated (not the active one) and the
 * node that uses it, for error messages and declaration checks.
 */
public record ParseContext(ContentBundle bundle, Identifier graphId, String nodeId) {

    /** True when the flag's namespace is declared in the bundle and lists the flag. */
    public boolean isFlagDeclared(FlagKey key) {
        FlagNamespace namespace = bundle.flagNamespaces().get(key.namespace());
        return namespace != null && namespace.flags().contains(key.flag());
    }

    /**
     * The bundle's parsed section of a registered extension, or empty when the bundle does not hold it. Extensions are
     * parsed before any condition, action or provider, so a parser can check ids against them.
     */
    public <T> Optional<T> extension(ContentExtensionKey<T> key) {
        return bundle.extension(key);
    }

    /** A prefix for error messages: {@code graph#node: }. */
    public String where() {
        return graphId + "#" + nodeId + ": ";
    }
}
