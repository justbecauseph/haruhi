package town.lampas.haruhi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

class AuditTogglePayloadTest {

    @Test
    void roundTripsToggleAndExplicitStates() {
        for (Optional<Boolean> value : List.of(Optional.<Boolean>empty(), Optional.of(true), Optional.of(false))) {
            AuditTogglePayload original = new AuditTogglePayload(value);
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            AuditTogglePayload.STREAM_CODEC.encode(buf, original);
            AuditTogglePayload decoded = AuditTogglePayload.STREAM_CODEC.decode(buf);
            assertEquals(original, decoded);
            assertEquals(0, buf.readableBytes());
        }
    }
}
