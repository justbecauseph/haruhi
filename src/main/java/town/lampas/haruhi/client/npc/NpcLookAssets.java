package town.lampas.haruhi.client.npc;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.Haruhi;
import town.lampas.haruhi.npc.NpcLook;

/**
 * Turns the ids in an NPC look into resources that exist. A model or animation id names a GeckoLib file, so the file path
 * is built the way GeckoLib reads it: {@code geckolib/models/<path>.geo.json} and
 * {@code geckolib/animations/<path>.animation.json} in the id's namespace. A missing file falls back to the default
 * humanoid, and a missing texture to the Steve skin, so a profile with a bad asset still renders.
 */
public final class NpcLookAssets {
    /** The built-in humanoid model; its file is {@code assets/haruhi/geckolib/models/humanoid.geo.json}. */
    public static final Identifier DEFAULT_MODEL = Haruhi.id("humanoid");
    /** The built-in humanoid animations; its file is {@code assets/haruhi/geckolib/animations/humanoid.animation.json}. */
    public static final Identifier DEFAULT_ANIMATIONS = Haruhi.id("humanoid");

    private static final String MODEL_DIRECTORY = "geckolib/models/";
    private static final String MODEL_SUFFIX = ".geo.json";
    private static final String ANIMATIONS_DIRECTORY = "geckolib/animations/";
    private static final String ANIMATIONS_SUFFIX = ".animation.json";

    private NpcLookAssets() {}

    /** The resource file GeckoLib reads for a model id. */
    public static Identifier modelFile(Identifier model) {
        return Identifier.fromNamespaceAndPath(model.getNamespace(), MODEL_DIRECTORY + model.getPath() + MODEL_SUFFIX);
    }

    /** The resource file GeckoLib reads for an animations id. */
    public static Identifier animationsFile(Identifier animations) {
        return Identifier.fromNamespaceAndPath(
                animations.getNamespace(), ANIMATIONS_DIRECTORY + animations.getPath() + ANIMATIONS_SUFFIX);
    }

    /** The requested model when its file exists, else the default humanoid. */
    public static Identifier resolveModel(Optional<Identifier> requested, Predicate<Identifier> fileExists) {
        return requested.filter(model -> fileExists.test(modelFile(model))).orElse(DEFAULT_MODEL);
    }

    /** The requested animations when their file exists, else the default humanoid animations. */
    public static Identifier resolveAnimations(Optional<Identifier> requested, Predicate<Identifier> fileExists) {
        return requested.filter(animations -> fileExists.test(animationsFile(animations))).orElse(DEFAULT_ANIMATIONS);
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
