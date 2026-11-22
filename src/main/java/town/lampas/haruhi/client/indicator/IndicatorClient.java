package town.lampas.haruhi.client.indicator;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import town.lampas.haruhi.client.dialogue.DialogueClientConfig;
import town.lampas.haruhi.network.AuditTogglePayload;

/**
 * Client visual-indicator entrypoint: crosshair interaction prompts, proximity floating glyphs, optional ambient
 * glints, gold bound-block outlines, and the gamemaster staff audit overlay.
 */
public final class IndicatorClient {
    private static volatile IndicatorConfig config = IndicatorConfig.DEFAULT;

    private IndicatorClient() {}

    public static void init() {
        config = IndicatorConfig.load(
                FabricLoader.getInstance().getConfigDir().resolve(DialogueClientConfig.FILE_NAME));
        CrosshairPromptHud.init();
        IndicatorWorldRenderer.init();
        AmbientGlintTicker.init();
        StaffAudit.init();
        ClientPlayNetworking.registerGlobalReceiver(AuditTogglePayload.TYPE,
                (payload, context) -> context.client().execute(() -> StaffAudit.handleServerToggle(payload.enabled())));
    }

    public static IndicatorConfig config() {
        return config;
    }
}
