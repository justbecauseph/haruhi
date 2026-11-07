package town.lampas.haruhi.content.model;

import java.util.List;

/**
 * One branch of a router node. Weights are positive. A branch can be picked only when all of its {@code onlyIf}
 * conditions hold (no conditions: always); its chance is its weight over the sum of the weights of the branches that
 * can be picked.
 */
public record WeightedNext(String node, int weight, List<ConditionSpec> onlyIf) {

    public WeightedNext {
        onlyIf = List.copyOf(onlyIf);
    }

    /** A branch with no conditions. */
    public WeightedNext(String node, int weight) {
        this(node, weight, List.of());
    }
}
