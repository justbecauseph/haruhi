package town.lampas.haruhi.client.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.npc.NpcLook;

class NpcLookAssetsTest {
    private static final Identifier GREETER = Identifier.fromNamespaceAndPath("haruhi", "example/greeter");
    private static final Identifier OTHER = Identifier.fromNamespaceAndPath("haruhi", "example/other");
    private static final Identifier SKIN = Identifier.fromNamespaceAndPath("haruhi", "textures/npc/greeter.png");

    @Test
    void noRequestedModelUsesTheDefaultHumanoid() {
        assertEquals(NpcLookAssets.DEFAULT_MODEL, NpcLookAssets.resolveModel(Optional.empty(), id -> true));
    }

    /** The predicate is asked about the model id, which is the key GeckoLib bakes a model under. */
    @Test
    void aModelGeckoLibHasBakedIsUsed() {
        assertEquals(GREETER, NpcLookAssets.resolveModel(Optional.of(GREETER), GREETER::equals));
    }

    @Test
    void aModelGeckoLibHasNotBakedFallsBackToTheDefaultHumanoid() {
        assertEquals(NpcLookAssets.DEFAULT_MODEL, NpcLookAssets.resolveModel(Optional.of(GREETER), id -> false));
    }

    /** A profile that names no animations plays the model's own animations file, which shares the model's id. */
    @Test
    void noRequestedAnimationsUsesTheModelsOwnAnimationsWhenThereAreSome() {
        assertEquals(GREETER, NpcLookAssets.resolveAnimations(Optional.empty(), GREETER, GREETER::equals));
    }

    @Test
    void noRequestedAnimationsWithoutTheModelsOwnFallsBackToTheDefaultHumanoid() {
        assertEquals(NpcLookAssets.DEFAULT_ANIMATIONS,
                NpcLookAssets.resolveAnimations(Optional.empty(), GREETER, id -> false));
    }

    @Test
    void requestedAnimationsAreUsedWhenTheyExist() {
        assertEquals(OTHER, NpcLookAssets.resolveAnimations(Optional.of(OTHER), GREETER, OTHER::equals));
    }

    /** A named animations file that is missing falls back to the default humanoid, not to the model's own. */
    @Test
    void missingRequestedAnimationsFallBackToTheDefaultHumanoidNotToTheModelsOwn() {
        assertEquals(NpcLookAssets.DEFAULT_ANIMATIONS,
                NpcLookAssets.resolveAnimations(Optional.of(OTHER), GREETER, GREETER::equals));
    }

    @Test
    void aMissingTextureFallsBackToSteve() {
        assertEquals(NpcLook.DEFAULT_TEXTURE, NpcLookAssets.resolveTexture(SKIN, id -> false));
        assertEquals(SKIN, NpcLookAssets.resolveTexture(SKIN, id -> true));
    }
}
