package town.lampas.haruhi.binding;

import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import town.lampas.haruhi.Haruhi;

/**
 * Entity bindings, held as the persistent Fabric data attachment {@code haruhi:binding} on the entity. The binding is
 * saved with the entity and moves with it, across dimensions too, and goes when the entity dies or is discarded. It is
 * not copied on a mob conversion (a villager struck by lightning, a zombie villager cured), and it is not synced to
 * clients. Server thread only.
 */
public final class EntityBindings {
    private static @Nullable AttachmentType<Binding> type;

    private EntityBindings() {}

    /** Registers the attachment type. Called once from {@link BindingModule#init}. */
    static void register() {
        type = AttachmentRegistry.create(Haruhi.id("binding"), builder -> builder.persistent(Binding.CODEC));
    }

    public static Optional<Binding> get(Entity entity) {
        return Optional.ofNullable(entity.getAttached(type()));
    }

    /** Binds the entity, replacing any binding it had, which is returned. */
    public static Optional<Binding> set(Entity entity, Binding binding) {
        return Optional.ofNullable(entity.setAttached(type(), Objects.requireNonNull(binding, "binding")));
    }

    /** Unbinds the entity, returning the binding it had. */
    public static Optional<Binding> remove(Entity entity) {
        return Optional.ofNullable(entity.removeAttached(type()));
    }

    static AttachmentType<Binding> type() {
        AttachmentType<Binding> registered = type;
        if (registered == null) {
            throw new IllegalStateException("Haruhi entity bindings are not registered");
        }
        return registered;
    }
}
