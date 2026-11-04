package town.lampas.haruhi.npc;

import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.NpcProfile;

/**
 * The part of an NPC's profile that the client needs to draw it, synced from the server to every client that tracks the
 * entity. The name and scale travel separately: the name as the vanilla custom name, and the scale as the vanilla
 * {@code scale} attribute, which drives both the hitbox and the render.
 *
 * @param model a GeckoLib model id; empty means the built-in default humanoid model
 * @param texture the model's texture, or the skin texture when {@code model} is empty
 * @param animations a GeckoLib animation id; empty means the default humanoid animations
 */
public record NpcLook(Optional<Identifier> model, Identifier texture, Optional<Identifier> animations) {

    /** The Steve skin, drawn for an NPC whose profile is missing from the active content. */
    public static final Identifier DEFAULT_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");

    public static final NpcLook DEFAULT = new NpcLook(Optional.empty(), DEFAULT_TEXTURE, Optional.empty());

    public static final StreamCodec<ByteBuf, NpcLook> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), NpcLook::model,
            Identifier.STREAM_CODEC, NpcLook::texture,
            ByteBufCodecs.optional(Identifier.STREAM_CODEC), NpcLook::animations,
            NpcLook::new);

    /** The synced-data serializer. Records compare by value, so an unchanged look is not sent again. */
    public static final EntityDataSerializer<NpcLook> SERIALIZER = EntityDataSerializer.forValueType(STREAM_CODEC);

    /** The look a profile gives its NPC. */
    public static NpcLook of(NpcProfile profile) {
        return new NpcLook(profile.model(), profile.texture(), profile.animations());
    }
}
