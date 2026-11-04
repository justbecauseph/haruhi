package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.model.GeoModel;
import com.geckolib.renderer.base.GeoRenderState;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.client.dialogue.DialogueClient;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.npc.NpcEntity;
import town.lampas.haruhi.npc.NpcLook;

/**
 * The GeckoLib model for NPCs. The model and texture differ per entity, so they are read from the render state that
 * {@link #addAdditionalStateData} fills from the entity's look. The animations file is fixed per {@link NpcAnimatable}.
 */
final class NpcGeoModel extends GeoModel<NpcAnimatable> {

    @Override
    public Identifier getModelResource(GeoRenderState state) {
        return state.getOrDefaultGeckolibData(NpcRenderData.MODEL, NpcLookAssets.DEFAULT_MODEL);
    }

    @Override
    public Identifier getTextureResource(GeoRenderState state) {
        return state.getOrDefaultGeckolibData(NpcRenderData.TEXTURE, NpcLook.DEFAULT_TEXTURE);
    }

    @Override
    public Identifier getAnimationResource(NpcAnimatable animatable) {
        return animatable.animations();
    }

    /** A missing animation file or animation name falls back to the default humanoid's. */
    @Override
    public Identifier[] getAnimationResourceFallbacks(NpcAnimatable animatable) {
        return new Identifier[] {NpcLookAssets.DEFAULT_ANIMATIONS};
    }

    @Override
    public void addAdditionalStateData(NpcAnimatable animatable, Object object, GeoRenderState state) {
        if (!(object instanceof NpcEntity npc)) {
            return;
        }
        NpcLook look = npc.look();
        state.addGeckolibData(NpcRenderData.MODEL, NpcLookAssets.resolveModel(look.model(), BakedAssets::hasModel));
        state.addGeckolibData(NpcRenderData.TEXTURE,
                NpcLookAssets.resolveTexture(look.texture(), NpcLookAssets::resourceExists));
        state.addGeckolibData(NpcRenderData.SPEAKING, DialogueClient.isSpeaking(npc.getId()));
    }
}
