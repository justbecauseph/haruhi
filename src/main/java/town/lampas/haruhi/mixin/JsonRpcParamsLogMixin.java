package town.lampas.haruhi.mixin;

import net.minecraft.server.jsonrpc.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import town.lampas.haruhi.content.remote.ParamsRedaction;

/**
 * Owned by the content component. Vanilla's management server logs a call whose params do not fit the method's envelope
 * at DEBUG, with the params, and Fabric writes DEBUG to {@code logs/debug.log}. A {@code haruhi:content/validate} or
 * {@code import} call carries a fetch token, so the params of a {@code haruhi:} call are replaced in that one line.
 */
@Mixin(Connection.class)
abstract class JsonRpcParamsLogMixin {

    @ModifyArg(method = "handleIncomingRequest",
            at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;debug(Ljava/lang/String;[Ljava/lang/Object;)V"),
            index = 1)
    private Object[] haruhi$redactParams(Object[] arguments) {
        return ParamsRedaction.redact(arguments);
    }
}
