package town.lampas.haruhi.client.npc;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.npc.NpcLook;

/** The per-frame state of a {@link VanillaNpcRenderer} NPC: the humanoid pose plus the skin to draw. */
public final class VanillaNpcRenderState extends HumanoidRenderState {
    /** The skin texture, resolved from the look for this frame. */
    public Identifier texture = NpcLook.DEFAULT_TEXTURE;
}
