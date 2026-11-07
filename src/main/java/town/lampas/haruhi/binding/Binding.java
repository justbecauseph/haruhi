package town.lampas.haruhi.binding;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/**
 * A conversation attached to an entity: the graph a player's use opens, and the NPC profile that speaks for the entity,
 * if any. Saved on the entity as a Fabric data attachment.
 *
 * @param speaker an NPC profile whose name and portrait the entity's lines show; without one, the entity's own name
 */
public record Binding(Identifier graph, Optional<Identifier> speaker) {
    public static final Codec<Binding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("graph").forGetter(Binding::graph),
            Identifier.CODEC.optionalFieldOf("speaker").forGetter(Binding::speaker)
    ).apply(instance, Binding::new));

    public Binding {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(speaker, "speaker");
    }
}
