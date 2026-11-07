package town.lampas.haruhi.binding;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import town.lampas.haruhi.content.model.ContentBundle;

/**
 * What keeps a binding from working as it was made, for {@code /haruhi bindings}. A binding whose graph the active
 * revision lacks opens nothing; one whose speaker profile it lacks shows the block's or entity's own name; a block
 * binding whose block has changed applies to nothing until the block is back.
 */
final class BindingProblems {
    static final String MISSING_GRAPH = "missing graph";
    static final String MISSING_SPEAKER = "missing speaker profile";

    private BindingProblems() {}

    /** The problems of an entity binding against the active content, in a fixed order; empty when it works. */
    static List<String> of(Binding binding, ContentBundle content) {
        List<String> problems = new ArrayList<>(2);
        if (!content.dialogues().containsKey(binding.graph())) {
            problems.add(MISSING_GRAPH);
        }
        if (binding.speaker().isPresent() && !content.npcs().containsKey(binding.speaker().get())) {
            problems.add(MISSING_SPEAKER);
        }
        return problems;
    }

    /**
     * The problems of a block binding: those of {@link #of(Binding, ContentBundle)}, then a changed block.
     *
     * @param blockNow the id of the block at the position now
     */
    static List<String> of(BlockBinding binding, ContentBundle content, Identifier blockNow) {
        List<String> problems = new ArrayList<>(of(binding.binding(), content));
        if (!binding.block().equals(blockNow)) {
            problems.add("block is now " + blockNow);
        }
        return problems;
    }

    /** The problems as a suffix for a listing line: {@code " [a, b]"}, or nothing. */
    static String suffix(List<String> problems) {
        return problems.isEmpty() ? "" : " [" + String.join(", ", problems) + "]";
    }

    /** {@code ", speaker <id>"}, or nothing. */
    static String speakerSuffix(Optional<Identifier> speaker) {
        return speaker.map(id -> ", speaker " + id).orElse("");
    }
}
