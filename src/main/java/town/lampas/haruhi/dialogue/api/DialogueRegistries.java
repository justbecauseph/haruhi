package town.lampas.haruhi.dialogue.api;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * Registered condition, action and choice provider types. Haruhi registers its built-ins under the {@code haruhi}
 * namespace during init; other mods register theirs in their own namespace during their init, before any content is
 * validated. Registering the same id twice throws.
 */
public final class DialogueRegistries {
    private static final Map<Identifier, ConditionType> CONDITIONS = new ConcurrentHashMap<>();
    private static final Map<Identifier, ActionType> ACTIONS = new ConcurrentHashMap<>();
    private static final Map<Identifier, ChoiceProviderType> PROVIDERS = new ConcurrentHashMap<>();

    private DialogueRegistries() {}

    public static void registerCondition(Identifier id, ConditionType type) {
        put(CONDITIONS, id, type, "condition");
    }

    public static void registerAction(Identifier id, ActionType type) {
        put(ACTIONS, id, type, "action");
    }

    public static void registerProvider(Identifier id, ChoiceProviderType type) {
        put(PROVIDERS, id, type, "choice provider");
    }

    public static Optional<ConditionType> condition(Identifier id) {
        return Optional.ofNullable(CONDITIONS.get(id));
    }

    public static Optional<ActionType> action(Identifier id) {
        return Optional.ofNullable(ACTIONS.get(id));
    }

    public static Optional<ChoiceProviderType> provider(Identifier id) {
        return Optional.ofNullable(PROVIDERS.get(id));
    }

    private static <T> void put(Map<Identifier, T> map, Identifier id, T value, String kind) {
        if (map.putIfAbsent(id, value) != null) {
            throw new IllegalStateException("Dialogue " + kind + " " + id + " is already registered");
        }
    }
}
