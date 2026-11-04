package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.constant.dataticket.DataTicket;
import net.minecraft.resources.Identifier;

/**
 * The GeckoLib render-state keys the NPC renderer and its animatables share. The renderer sets them for each entity on each
 * frame, from the entity's synced look and the dialogue screen.
 */
final class NpcRenderData {
    /** The animations id the entity's sub-renderer was chosen for. */
    static final DataTicket<Identifier> ANIMATIONS = DataTicket.create("haruhi_npc_animations", Identifier.class);
    /** The model id after falling back to the default humanoid when its file is missing. */
    static final DataTicket<Identifier> MODEL = DataTicket.create("haruhi_npc_model", Identifier.class);
    /** The texture after falling back to the Steve skin when it is missing. */
    static final DataTicket<Identifier> TEXTURE = DataTicket.create("haruhi_npc_texture", Identifier.class);
    /** Whether the entity is the speaker of the open dialogue screen, which makes its base animation talk. */
    static final DataTicket<Boolean> SPEAKING = DataTicket.create("haruhi_npc_speaking", Boolean.class);

    private NpcRenderData() {}
}
