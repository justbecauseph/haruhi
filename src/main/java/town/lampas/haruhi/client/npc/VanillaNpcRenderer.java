package town.lampas.haruhi.client.npc;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * The NPC renderer used without GeckoLib: the vanilla wide player model, with the NPC's texture as its skin. A model id
 * cannot be drawn without GeckoLib, so every NPC is drawn as the default humanoid.
 */
public final class VanillaNpcRenderer extends LivingEntityRenderer<NpcEntity, VanillaNpcRenderState, HumanoidModel<VanillaNpcRenderState>> {

    public VanillaNpcRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public VanillaNpcRenderState createRenderState() {
        return new VanillaNpcRenderState();
    }

    @Override
    public void extractRenderState(NpcEntity entity, VanillaNpcRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        HumanoidMobRenderer.extractHumanoidRenderState(entity, state, partialTick, this.itemModelResolver);
        state.texture = NpcLookAssets.resolveTexture(entity.look().texture(), NpcLookAssets::resourceExists);
        placeNameTag(state);
    }

    /**
     * Moves a shown name tag from the hitbox top to {@link NameTagPlacement#GAP} above the model's top. The model is the
     * vanilla wide player model ({@code PlayerModel.createMesh}), whose hat layer is {@link NameTagPlacement#HUMANOID_TOP}
     * above its feet. {@code LivingEntityRenderer.submit} scales it by {@code state.scale} only: this renderer adds no
     * scale of its own, and its {@code -1.501} offset lifts the model a thousandth of a block, which is left out.
     */
    static void placeNameTag(VanillaNpcRenderState state) {
        if (state.nameTagAttachment != null) {
            state.nameTagAttachment =
                    NameTagPlacement.above(state.nameTagAttachment, NameTagPlacement.HUMANOID_TOP, state.scale);
        }
    }

    @Override
    public Identifier getTextureLocation(VanillaNpcRenderState state) {
        return state.texture;
    }
}
