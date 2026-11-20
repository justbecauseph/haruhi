package town.lampas.haruhi.binding;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import town.lampas.haruhi.api.HaruhiServices;
import town.lampas.haruhi.content.model.ContentBundle;
import town.lampas.haruhi.content.model.DialogueGraph;
import town.lampas.haruhi.dialogue.presenter.TextResolver;

/**
 * Resolves the {@code speaker_name} override of a bound block or entity from the active content bundle. A binding
 * that names a loaded NPC profile keeps that profile as its speaker; otherwise the graph's {@code speaker_name} is
 * resolved for the viewer.
 */
public final class BindingNames {
    private BindingNames() {}

    /** Raw {@code speaker_name} override for {@code binding} under {@code content}, or empty when none applies. */
    public static Optional<Component> nameOverride(Binding binding, ContentBundle content) {
        if (binding.speaker().map(content.npcs()::get).isPresent()) {
            return Optional.empty();
        }
        DialogueGraph graph = content.dialogues().get(binding.graph());
        return graph == null ? Optional.empty() : graph.speakerName();
    }

    /** Resolved {@code speaker_name} override for a bound block (or either half of a two-part block) at {@code pos}. */
    public static Optional<Component> blockNameOverride(ServerLevel level, BlockPos pos, BlockState state,
            ServerPlayer viewer) {
        return BlockBindings.of(level)
                .covering(level, pos, state)
                .flatMap(found -> resolveOverride(found.binding().binding(), viewer));
    }

    /** Resolved {@code speaker_name} override for a bound entity, or empty when unbound or using an NPC profile. */
    public static Optional<Component> entityNameOverride(Entity entity, ServerPlayer viewer) {
        if (!BindingInteractions.isBindable(entity)) {
            return Optional.empty();
        }
        return EntityBindings.get(entity).flatMap(binding -> resolveOverride(binding, viewer));
    }

    private static Optional<Component> resolveOverride(Binding binding, ServerPlayer viewer) {
        return nameOverride(binding, HaruhiServices.content().active())
                .map(raw -> TextResolver.resolveFor(viewer, raw))
                .filter(resolved -> !resolved.getString().isEmpty());
    }
}
