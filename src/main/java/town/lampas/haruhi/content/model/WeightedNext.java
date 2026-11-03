package town.lampas.haruhi.content.model;

/** One branch of a router node. Weights are positive; a branch's chance is its weight over the sum. */
public record WeightedNext(String node, int weight) {}
