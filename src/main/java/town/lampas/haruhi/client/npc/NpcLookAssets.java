package town.lampas.haruhi.client.npc;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.npc.NpcLook;

/**
 * Turns the ids in an NPC look into assets that exist. A model or animations id names a GeckoLib file, which GeckoLib
 * bakes under that id. A file that fails to parse or bake is left out, so the caller asks GeckoLib whether an id is baked,
 * not whether its file is in a resource pack. A missing model or animations file falls back to the default humanoid, and a
 * missing texture to the Steve skin, so a profile with a bad asset still renders.
 */
public final class NpcLookAssets {
    /** The built-in humanoid model; its file is {@code assets/haruhi/geckolib/models/humanoid.geo.json}. */
    public static final Identifier DEFAULT_MODEL = Haruhi.id("humanoid");
    /** The built-in humanoid animations; its file is {@code assets/haruhi/geckolib/animations/humanoid.animation.json}. */
    public static final Identifier DEFAULT_ANIMATIONS = Haruhi.id("humanoid");

    private NpcLookAssets() {}

    /** The requested model when {@code isBaked} accepts its id, else the default humanoid. */
    public static Identifier resolveModel(Optional<Identifier> requested, Predicate<Identifier> isBaked) {
        return requested.filter(isBaked).orElse(DEFAULT_MODEL);
    }

    /**
     * The animations an NPC plays. A named animations id is used when {@code isBaked} accepts it, and a missing one falls
     * back to the default humanoid. An NPC that names none plays the animations file that has the model's id when there is
     * one, and the default humanoid otherwise.
     *
     * @param model the model the NPC draws, after {@link #resolveModel}
     */
    public static Identifier resolveAnimations(
            Optional<Identifier> requested, Identifier model, Predicate<Identifier> isBaked) {
        if (requested.isPresent()) {
            return requested.filter(isBaked).orElse(DEFAULT_ANIMATIONS);
        }
        return isBaked.test(model) ? model : DEFAULT_ANIMATIONS;
    }

    /** The requested texture when it exists, else the Steve skin. */
    public static Identifier resolveTexture(Identifier requested, Predicate<Identifier> exists) {
        return exists.test(requested) ? requested : NpcLook.DEFAULT_TEXTURE;
    }

    /** Whether a resource pack provides {@code id}. Client thread only. */
    public static boolean resourceExists(Identifier id) {
        return Minecraft.getInstance().getResourceManager().getResource(id).isPresent();
    }
}
