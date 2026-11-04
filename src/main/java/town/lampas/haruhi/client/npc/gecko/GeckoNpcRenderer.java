package town.lampas.haruhi.client.npc.gecko;

import com.geckolib.renderer.GeoReplacedEntityRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.client.npc.NpcLookAssets;
import town.lampas.haruhi.npc.NpcEntity;

/**
 * The NPC renderer when GeckoLib is loaded. Vanilla registers one renderer per entity type, but an NPC's animations file is
 * per entity, and a GeoReplacedEntityRenderer draws with one animatable. So this renders each NPC through a
 * {@link GeoReplacedEntityRenderer} chosen by its animations id, one per id that is in use, and records the choice in the
 * render state for {@link #submit}.
 */
public final class GeckoNpcRenderer extends EntityRenderer<NpcEntity, LivingEntityRenderState> {
    private final EntityRendererProvider.Context context;
    private final Map<Identifier, GeoReplacedEntityRenderer<NpcAnimatable, NpcEntity, EntityRenderState>> renderers =
            new HashMap<>();

    public GeckoNpcRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.context = context;
        this.shadowRadius = 0.5F;
    }

    @Override
    public LivingEntityRenderState createRenderState() {
        return new LivingEntityRenderState();
    }

    @Override
    public void extractRenderState(NpcEntity entity, LivingEntityRenderState state, float partialTick) {
        Identifier animations = animationsFor(entity);
        renderers.computeIfAbsent(animations, this::createRenderer).extractRenderState(entity, state, partialTick);
        ((GeoRenderState) state).addGeckolibData(NpcRenderData.ANIMATIONS, animations);
    }

    @Override
    public void submit(LivingEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        Identifier animations = ((GeoRenderState) state)
                .getOrDefaultGeckolibData(NpcRenderData.ANIMATIONS, NpcLookAssets.DEFAULT_ANIMATIONS);
        renderers.computeIfAbsent(animations, this::createRenderer).submit(state, poseStack, collector, camera);
    }

    /** Plays a named animation on one NPC, through the animatable for the animations file that NPC uses. */
    void playAnimation(NpcEntity entity, String animation) {
        renderers.computeIfAbsent(animationsFor(entity), this::createRenderer)
                .getAnimatable()
                .triggerAnim(entity, null, animation);
    }

    private static Identifier animationsFor(NpcEntity entity) {
        return NpcLookAssets.resolveAnimations(entity.look().animations(), NpcLookAssets::resourceExists);
    }

    private GeoReplacedEntityRenderer<NpcAnimatable, NpcEntity, EntityRenderState> createRenderer(Identifier animations) {
        return new GeoReplacedEntityRenderer<>(context, new NpcGeoModel(), new NpcAnimatable(animations));
    }
}
