package town.lampas.haruhi.network;

import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One choice as the client shows it. {@code index} is the position the client sends back; the server maps it to the
 * choice again and re-checks it, so the client never names an action.
 */
public record ChoiceView(int index, Component text, boolean enabled, Optional<Component> unavailableReason) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ChoiceView> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChoiceView::index,
            ComponentSerialization.STREAM_CODEC, ChoiceView::text,
            ByteBufCodecs.BOOL, ChoiceView::enabled,
            ComponentSerialization.OPTIONAL_STREAM_CODEC, ChoiceView::unavailableReason,
            ChoiceView::new);
}
