package town.lampas.haruhi.npc;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.NpcProfile;

/**
 * The part of an NPC's profile that the client needs to draw it, synced from the server to every client that tracks the
 * entity. The name and scale travel separately: the name as the vanilla custom name, and the scale as the vanilla
 * {@code scale} attribute, which drives both the hitbox and the render.
 *
 * <p>The look travels as three vanilla string entries of the entity's synced data, one per field (see
 * {@link #fromSynced}), so it needs no custom entity data serializer. A custom serializer would have to be registered
 * and have its numeric id kept in sync between server and client; vanilla serializer ids are fixed.
 *
 * @param model a GeckoLib model id; empty means the built-in default humanoid model
 * @param texture the model's texture, or the skin texture when {@code model} is empty
 * @param animations a GeckoLib animation id; empty means the model's own animations (the animations file with the model's
 *     id) when there is one, else the default humanoid animations
 */
public record NpcLook(Optional<Identifier> model, Identifier texture, Optional<Identifier> animations) {

    /** The Steve skin, drawn for an NPC whose profile is missing from the active content. */
    public static final Identifier DEFAULT_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png");

    public static final NpcLook DEFAULT = new NpcLook(Optional.empty(), DEFAULT_TEXTURE, Optional.empty());

    /** The look a profile gives its NPC. */
    public static NpcLook of(NpcProfile profile) {
        return new NpcLook(profile.model(), profile.texture(), profile.animations());
    }

    /** The synced form of {@link #model}: the id, or the empty string for the default humanoid model. */
    String syncedModel() {
        return synced(model);
    }

    /** The synced form of {@link #texture}. */
    String syncedTexture() {
        return texture.toString();
    }

    /** The synced form of {@link #animations}: the id, or the empty string when the profile names none. */
    String syncedAnimations() {
        return synced(animations);
    }

    /**
     * Rebuilds a look from its synced strings. An empty or malformed model or animations id means the default one, and
     * an empty or malformed texture means {@link #DEFAULT_TEXTURE}.
     */
    static NpcLook fromSynced(String model, String texture, String animations) {
        return new NpcLook(parse(model), parse(texture).orElse(DEFAULT_TEXTURE), parse(animations));
    }

    private static String synced(Optional<Identifier> id) {
        return id.map(Identifier::toString).orElse("");
    }

    /** Checks for the empty string first: {@link Identifier#tryParse} reads it as {@code minecraft:}. */
    private static Optional<Identifier> parse(String synced) {
        return synced.isEmpty() ? Optional.empty() : Optional.ofNullable(Identifier.tryParse(synced));
    }
}
