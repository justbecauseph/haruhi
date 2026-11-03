package town.lampas.haruhi.network;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.Haruhi;

/**
 * Server to client: show this node in the visual-novel screen. Sent only to clients that registered the channel;
 * others get the vanilla dialog presenter.
 *
 * @param sessionId the server's session id; the client echoes it so stale screens are ignored
 * @param speakerEntityId the entity to render as the 3D portrait, when it is loaded on the client
 * @param portrait a 2D portrait texture that replaces the 3D render
 * @param choices empty means a single continue control
 */
public record DialogueNodePayload(
        int sessionId,
        Identifier graphId,
        String nodeId,
        OptionalInt speakerEntityId,
        Component speakerName,
        Component text,
        Optional<String> animation,
        Optional<Identifier> portrait,
        List<ChoiceView> choices) implements CustomPacketPayload {

    public static final Type<DialogueNodePayload> TYPE = new Type<>(Haruhi.id("dialogue_node"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Optional<String>> OPTIONAL_STRING =
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8);
    private static final StreamCodec<RegistryFriendlyByteBuf, Optional<Identifier>> OPTIONAL_ID =
            ByteBufCodecs.optional(Identifier.STREAM_CODEC);
    private static final StreamCodec<RegistryFriendlyByteBuf, List<ChoiceView>> CHOICES =
            ChoiceView.STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, DialogueNodePayload> STREAM_CODEC =
            StreamCodec.of(DialogueNodePayload::write, DialogueNodePayload::read);

    public DialogueNodePayload {
        choices = List.copyOf(choices);
    }

    private static void write(RegistryFriendlyByteBuf buf, DialogueNodePayload payload) {
        ByteBufCodecs.VAR_INT.encode(buf, payload.sessionId);
        Identifier.STREAM_CODEC.encode(buf, payload.graphId);
        ByteBufCodecs.STRING_UTF8.encode(buf, payload.nodeId);
        ByteBufCodecs.OPTIONAL_VAR_INT.encode(buf, payload.speakerEntityId);
        ComponentSerialization.STREAM_CODEC.encode(buf, payload.speakerName);
        ComponentSerialization.STREAM_CODEC.encode(buf, payload.text);
        OPTIONAL_STRING.encode(buf, payload.animation);
        OPTIONAL_ID.encode(buf, payload.portrait);
        CHOICES.encode(buf, payload.choices);
    }

    private static DialogueNodePayload read(RegistryFriendlyByteBuf buf) {
        return new DialogueNodePayload(
                ByteBufCodecs.VAR_INT.decode(buf),
                Identifier.STREAM_CODEC.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.OPTIONAL_VAR_INT.decode(buf),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                OPTIONAL_STRING.decode(buf),
                OPTIONAL_ID.decode(buf),
                CHOICES.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
