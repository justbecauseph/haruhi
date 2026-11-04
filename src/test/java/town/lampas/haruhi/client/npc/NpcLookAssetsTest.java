package town.lampas.haruhi.client.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.npc.NpcLook;

class NpcLookAssetsTest {
    private static final Identifier GREETER = Identifier.fromNamespaceAndPath("haruhi", "example/greeter");
    private static final Identifier SKIN = Identifier.fromNamespaceAndPath("haruhi", "textures/npc/greeter.png");

    @Test
    void modelFileIsTheGeckoLibPathForTheId() {
        assertEquals("haruhi:geckolib/models/example/greeter.geo.json",
                NpcLookAssets.modelFile(GREETER).toString());
    }

    @Test
    void animationsFileIsTheGeckoLibPathForTheId() {
        assertEquals("haruhi:geckolib/animations/example/greeter.animation.json",
                NpcLookAssets.animationsFile(GREETER).toString());
    }

    @Test
    void noRequestedModelUsesTheDefaultHumanoid() {
        assertEquals(NpcLookAssets.DEFAULT_MODEL, NpcLookAssets.resolveModel(Optional.empty(), file -> true));
    }

    @Test
    void aModelWhoseFileIsMissingFallsBackToTheDefaultHumanoid() {
        assertEquals(NpcLookAssets.DEFAULT_MODEL,
                NpcLookAssets.resolveModel(Optional.of(GREETER), file -> false));
    }

    @Test
    void aModelWhoseFileExistsIsUsed() {
        Identifier file = NpcLookAssets.modelFile(GREETER);
        assertEquals(GREETER, NpcLookAssets.resolveModel(Optional.of(GREETER), file::equals));
    }

    @Test
    void animationsFallBackToTheDefaultWhenTheFileIsMissing() {
        assertEquals(NpcLookAssets.DEFAULT_ANIMATIONS,
                NpcLookAssets.resolveAnimations(Optional.of(GREETER), file -> false));
        Identifier file = NpcLookAssets.animationsFile(GREETER);
        assertEquals(GREETER, NpcLookAssets.resolveAnimations(Optional.of(GREETER), file::equals));
    }

    @Test
    void aMissingTextureFallsBackToSteve() {
        assertEquals(NpcLook.DEFAULT_TEXTURE, NpcLookAssets.resolveTexture(SKIN, id -> false));
        assertEquals(SKIN, NpcLookAssets.resolveTexture(SKIN, id -> true));
    }
}
