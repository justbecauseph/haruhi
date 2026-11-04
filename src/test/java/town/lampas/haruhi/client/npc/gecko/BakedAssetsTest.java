package town.lampas.haruhi.client.npc.gecko;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.npc.NpcLook;

/**
 * The model and animations an NPC draws with, after GeckoLib has baked the mod's assets and the fixture pack in
 * {@code src/test/resources/town/lampas/haruhi/client/npc/gecko/pack}. Each test runs its {@code check} method in the
 * {@link GeckoLibSandbox}, where GeckoLib's caches hold what it baked from those files.
 *
 * <p>AGENTS.md: the client falls back to the default model when a model is missing. The NpcProfile contract: a profile
 * that names no animations plays the model's own animations, else the default humanoid set.
 *
 * <p>GeckoLib logs an "Error loading resource" with a stack trace for each broken fixture file when the sandbox starts.
 * Those errors are expected.
 */
class BakedAssetsTest {
    private static final Identifier FOX = fixture("fox");
    private static final Identifier BROKEN = fixture("broken");
    private static final Identifier PLAIN = fixture("plain");
    private static final Identifier MISSING = fixture("missing");

    @Test
    void theDefaultHumanoidModelAndAnimationsBake() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkTheDefaultHumanoidModelAndAnimationsBake");
    }

    @Test
    void aModelGeckoLibBakedIsDrawn() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkAModelGeckoLibBakedIsDrawn");
    }

    @Test
    void aModelFileThatFailsToBakeFallsBackToTheDefaultHumanoid() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkAModelFileThatFailsToBakeFallsBackToTheDefaultHumanoid");
    }

    @Test
    void aModelGeckoLibBakesFromAPlainJsonFileIsDrawn() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkAModelGeckoLibBakesFromAPlainJsonFileIsDrawn");
    }

    @Test
    void aMissingModelFallsBackToTheDefaultHumanoid() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkAMissingModelFallsBackToTheDefaultHumanoid");
    }

    @Test
    void aLookWithoutAnimationsPlaysItsModelsOwnAnimations() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkALookWithoutAnimationsPlaysItsModelsOwnAnimations");
    }

    @Test
    void aLookWithoutAnimationsWhoseModelHasNonePlaysTheHumanoidSet() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkALookWithoutAnimationsWhoseModelHasNonePlaysTheHumanoidSet");
    }

    @Test
    void anAnimationsFileThatFailsToBakeFallsBackToTheHumanoidSet() throws Throwable {
        GeckoLibSandbox.run(BakedAssetsTest.class, "checkAnAnimationsFileThatFailsToBakeFallsBackToTheHumanoidSet");
    }

    private static void checkTheDefaultHumanoidModelAndAnimationsBake() {
        assertEquals(NpcLookAssets.DEFAULT_MODEL, BakedAssets.model(NpcLook.DEFAULT));
        assertEquals(NpcLookAssets.DEFAULT_ANIMATIONS, BakedAssets.animations(NpcLook.DEFAULT));
        assertTrue(BakedAssets.hasModel(NpcLookAssets.DEFAULT_MODEL), "GeckoLib did not bake the humanoid model");
        assertTrue(BakedAssets.hasAnimations(NpcLookAssets.DEFAULT_ANIMATIONS),
                "GeckoLib did not bake the humanoid animations");
    }

    private static void checkAModelGeckoLibBakedIsDrawn() {
        assertEquals(FOX, BakedAssets.model(look(FOX, null)));
    }

    /** The file is in the pack, so a check for the file would accept it; GeckoLib could not bake it. */
    private static void checkAModelFileThatFailsToBakeFallsBackToTheDefaultHumanoid() {
        assertFileInPack("geckolib/models/fixture/broken.geo.json");
        assertEquals(NpcLookAssets.DEFAULT_MODEL, BakedAssets.model(look(BROKEN, null)));
        assertEquals(NpcLookAssets.DEFAULT_ANIMATIONS, BakedAssets.animations(look(BROKEN, null)),
                "the animations of a model that fell back");
    }

    /** GeckoLib strips a bare {@code .json} suffix as well as {@code .geo.json}, so {@code plain.json} bakes as plain. */
    private static void checkAModelGeckoLibBakesFromAPlainJsonFileIsDrawn() {
        assertFileInPack("geckolib/models/fixture/plain.json");
        assertEquals(PLAIN, BakedAssets.model(look(PLAIN, null)));
    }

    private static void checkAMissingModelFallsBackToTheDefaultHumanoid() {
        assertEquals(NpcLookAssets.DEFAULT_MODEL, BakedAssets.model(look(MISSING, null)));
    }

    private static void checkALookWithoutAnimationsPlaysItsModelsOwnAnimations() {
        assertEquals(FOX, BakedAssets.animations(look(FOX, null)));
    }

    private static void checkALookWithoutAnimationsWhoseModelHasNonePlaysTheHumanoidSet() {
        assertEquals(NpcLookAssets.DEFAULT_ANIMATIONS, BakedAssets.animations(look(PLAIN, null)));
    }

    /** A named animations file that failed to bake falls back to the humanoid set, not to the model's own. */
    private static void checkAnAnimationsFileThatFailsToBakeFallsBackToTheHumanoidSet() {
        assertFileInPack("geckolib/animations/fixture/broken.animation.json");
        assertEquals(NpcLookAssets.DEFAULT_ANIMATIONS, BakedAssets.animations(look(FOX, BROKEN)));
    }

    private static void assertFileInPack(String path) {
        Identifier file = Identifier.fromNamespaceAndPath("haruhi", path);
        assertTrue(GeckoLibSandboxClient.resources().getResource(file).isPresent(), file + " is not in the fixture pack");
    }

    private static NpcLook look(Identifier model, @Nullable Identifier animations) {
        return new NpcLook(Optional.of(model), NpcLook.DEFAULT_TEXTURE, Optional.ofNullable(animations));
    }

    private static Identifier fixture(String name) {
        return Identifier.fromNamespaceAndPath("haruhi", "fixture/" + name);
    }
}
