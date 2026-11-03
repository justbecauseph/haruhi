package town.lampas.haruhi.content.model;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * What an NPC is: its name, look and default conversation.
 *
 * @param model a GeckoLib model resource; empty means the built-in default humanoid model
 * @param texture the model's texture, or the skin texture when {@code model} is empty
 * @param animations a GeckoLib animation resource; empty means the model's own or the default humanoid set
 * @param portrait a 2D portrait texture the visual-novel screen shows instead of the 3D model, unless a node overrides it
 * @param dialogue the graph opened when a player interacts with the NPC; empty means the NPC does not talk
 * @param scale render and hitbox scale, 1 by default
 */
public record NpcProfile(
        Identifier id,
        Component name,
        Optional<Identifier> model,
        Identifier texture,
        Optional<Identifier> animations,
        Optional<Identifier> portrait,
        Optional<Identifier> dialogue,
        float scale) {}
