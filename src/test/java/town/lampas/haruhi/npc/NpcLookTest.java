package town.lampas.haruhi.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import town.lampas.haruhi.content.model.NpcProfile;

class NpcLookTest {

    @Test
    void theDefaultLookIsTheSteveSkinWithTheDefaultHumanoid() {
        assertEquals(Optional.empty(), NpcLook.DEFAULT.model());
        assertEquals(Optional.empty(), NpcLook.DEFAULT.animations());
        assertEquals("minecraft:textures/entity/player/wide/steve.png", NpcLook.DEFAULT.texture().toString());
    }

    @Test
    void aProfileGivesItsModelTextureAndAnimations() {
        Identifier model = Identifier.fromNamespaceAndPath("haruhi", "example/greeter");
        Identifier texture = Identifier.fromNamespaceAndPath("haruhi", "textures/npc/greeter.png");
        Identifier animations = Identifier.fromNamespaceAndPath("haruhi", "example/greeter");
        NpcProfile profile = new NpcProfile(
                Identifier.fromNamespaceAndPath("haruhi", "example/greeter"),
                Component.literal("Greeter"),
                Optional.of(model),
                texture,
                Optional.of(animations),
                Optional.empty(),
                Optional.empty(),
                1.5F);

        NpcLook look = NpcLook.of(profile);

        assertEquals(Optional.of(model), look.model());
        assertEquals(texture, look.texture());
        assertEquals(Optional.of(animations), look.animations());
        assertNotEquals(NpcLook.DEFAULT, look);
    }
}
