package town.lampas.haruhi.network;

import io.netty.buffer.ByteBuf;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import town.lampas.haruhi.Haruhi;

/**
 * Server to client: toggles or explicitly sets the client's staff audit overlay ({@code /haruhi audit}).
 */
public record AuditTogglePayload(Optional<Boolean> enabled) implements CustomPacketPayload {
    public static final Type<AuditTogglePayload> TYPE = new Type<>(Haruhi.id("audit_toggle"));
    public static final StreamCodec<ByteBuf, AuditTogglePayload> STREAM_CODEC =
            ByteBufCodecs.optional(ByteBufCodecs.BOOL).map(AuditTogglePayload::new, AuditTogglePayload::enabled);

    public AuditTogglePayload {
        Objects.requireNonNull(enabled, "enabled");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
