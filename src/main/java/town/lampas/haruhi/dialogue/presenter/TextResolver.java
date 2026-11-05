package town.lampas.haruhi.dialogue.presenter;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ResolutionContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.dialogue.runtime.VisibleChoice;

/**
 * Resolves the text of a node for the player it is shown to, as vanilla resolves sign text and {@code tellraw}: a
 * {@code score} component shows a score ({@code "name": "@s"} is the player), a {@code selector} component lists the
 * entities it matches, and an {@code nbt} component shows the data it names.
 *
 * <p>Only text from the content bundle is resolved: node text, static choice text, {@code unavailable_message} and the
 * speaker's name. Bundles are written by staff, which is why resolution may run at gamemaster permission, like a sign.
 * The entries of a choice provider come from mod code at run time and may carry player-made text (an item's custom
 * name, say), so they are sent as the provider gave them.
 *
 * <p>Text is resolved only when it is sent. The session keeps the text as written, so the check that a pick still names
 * the entry that was shown is not affected by a score that changed in between. Each part of a text resolves on its own:
 * a part that fails (a score whose name matches more than one entity, say) is sent as written, and the other parts are
 * still resolved. A score that does not resolve shows as nothing.
 */
final class TextResolver {
    /** Leaves text as written, for building payloads without a player (unit tests). */
    static final TextResolver AS_WRITTEN = new TextResolver(UnaryOperator.identity());

    private final UnaryOperator<Component> resolver;
    /** Text resolved by this instance, so a node shown again in the same tick is not resolved again. */
    private final Map<Component, Component> resolved = new IdentityHashMap<>();

    private TextResolver(UnaryOperator<Component> resolver) {
        this.resolver = resolver;
    }

    /** A resolver that applies {@code resolver} to bundle text, for tests. */
    static TextResolver of(UnaryOperator<Component> resolver) {
        return new TextResolver(resolver);
    }

    /** Resolves as the player, at gamemaster permission, with command output suppressed. */
    static TextResolver forPlayer(ServerPlayer player) {
        CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(LevelBasedPermissionSet.GAMEMASTER)
                .withSuppressedOutput();
        ResolutionContext context = ResolutionContext.create(source);
        return new TextResolver(text -> resolveParts(context, text));
    }

    /** Bundle text, resolved. */
    Component resolve(Component text) {
        if (this == AS_WRITTEN) {
            return text;
        }
        return resolved.computeIfAbsent(text, resolver);
    }

    Optional<Component> resolve(Optional<Component> text) {
        return text.map(this::resolve);
    }

    /** A choice's text: resolved for a static choice from the bundle, as given for a provider's entry. */
    Component choiceText(VisibleChoice choice) {
        return isProvided(choice) ? choice.text() : resolve(choice.text());
    }

    /** A choice's unavailable reason: resolved for a static choice, as given for a provider's entry. */
    Optional<Component> choiceReason(VisibleChoice choice) {
        return isProvided(choice) ? choice.unavailableReason() : resolve(choice.unavailableReason());
    }

    private static boolean isProvided(VisibleChoice choice) {
        return choice.providedIndex() >= 0;
    }

    /**
     * Resolves the component's own contents, then each sibling on its own. Vanilla's resolver stops at the first part
     * that fails, which would leave every other part of the text unresolved too.
     */
    private static Component resolveParts(ResolutionContext context, Component text) {
        MutableComponent self = MutableComponent.create(text.getContents()).setStyle(text.getStyle());
        MutableComponent result;
        try {
            result = ComponentUtils.resolve(context, self);
        } catch (CommandSyntaxException | RuntimeException e) {
            Haruhi.LOGGER.debug("Part of dialogue text {} did not resolve; sending it as written", self, e);
            result = self;
        }
        for (Component sibling : text.getSiblings()) {
            result.append(resolveParts(context, sibling));
        }
        return result;
    }
}
