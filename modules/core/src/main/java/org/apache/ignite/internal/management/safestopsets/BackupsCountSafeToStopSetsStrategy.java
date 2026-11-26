/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.management.safestopsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;

/**
 * Default strategy that derives the answer from the cache group configuration: the number of copies of a
 * partition is bounded by {@code min(backups + 1, |affinityNodes|)}, so a group of no more than one copy less
 * cannot hold all copies of any partition at once.
 * <p>
 * The partition owners are read only when the actual ownership is not yet equal to the affinity assignment
 * (rebalance in flight, a node was drained, {@code rebalanceMode = NONE}, loss policy {@code IGNORE}): in
 * that state the answer is built from the actual layout and verified semantically. When both the
 * configuration-level conditions and the state-level condition hold, the answer is derived from the
 * configuration only; the verification marks it {@code structure-only} instead of claiming a semantic proof.
 */
public class BackupsCountSafeToStopSetsStrategy implements SafeToStopSetsStrategy {
    /** Strategy name, the value of the {@code --strategy} command line option. */
    public static final String NAME = "backups-count";

    /**
     * @return {@value #NAME}.
     */
    @Override public String name() {
        return NAME;
    }

    /**
     * @param ctx Snapshot of the cluster state.
     * @return Partition of the snapshot nodes into groups of no more than one copy less than the smallest
     *      group's copy bound, or singletons if no two nodes may be stopped together.
     * @throws IllegalArgumentException If the number of copies cannot be derived from the configuration at
     *      all: the affinity of a group is not {@code RendezvousAffinityFunction} or copy filters are set
     *      without {@code excludeNeighbors}. Such a configuration is fixed by the operator, not by reading the
     *      layout, so the command fails closed and suggests a strategy that computes the actual layout.
     */
    @Override public List<List<UUID>> split(SafeToStopSetsContext ctx) {
        Map<String, String> blockers = ctx.structureOnlyBlockers();

        if (!blockers.isEmpty())
            throw new IllegalArgumentException(structureOnlyFailure(blockers));

        int maxGroupSize = SafeToStopSetsSupport.maxSafeGroupSize(ctx);

        if (!ctx.ownershipMatchesAffinity())
            return splitByOwners(ctx);

        if (maxGroupSize <= 1)
            return singletons(ctx.nodes());

        return roundRobin(ctx.nodes(), maxGroupSize);
    }

    /**
     * Deals the snapshot nodes round-robin over the groups: the number of groups is the ceiling of
     * {@code nodes / maxGroupSize}, so no group exceeds the bound, the sizes differ by at most one, and the
     * result is deterministic because the nodes are ordered by consistent ID.
     * <p>
     * The bound is clamped by the cluster size before the number of groups is computed: a snapshot without a
     * single user cache group has no copy bound at all, so
     * {@link SafeToStopSetsSupport#maxSafeGroupSize(SafeToStopSetsContext)} returns its "no limit" value, and
     * {@code nodes + bound - 1} would overflow into a negative group count. One group over all nodes is the
     * correct answer there: nothing is lost by stopping them together.
     *
     * @param nodes Snapshot nodes, ordered by consistent ID.
     * @param maxGroupSize Maximum size of a group, at least {@code 2}.
     * @return Groups of node IDs, each of size no more than {@code maxGroupSize}.
     */
    private static List<List<UUID>> roundRobin(List<ClusterNode> nodes, int maxGroupSize) {
        int bound = Math.max(1, Math.min(maxGroupSize, nodes.size()));

        int grps = (int)((nodes.size() + (long)bound - 1) / bound);

        List<List<UUID>> res = new ArrayList<>(grps);

        for (int i = 0; i < grps; i++)
            res.add(new ArrayList<>());

        for (int i = 0; i < nodes.size(); i++)
            res.get(i % grps).add(nodes.get(i).id());

        return res;
    }

    /**
     * @param nodes Snapshot nodes, ordered by consistent ID.
     * @return A group of one node per snapshot node: no two nodes may be stopped together.
     */
    private static List<List<UUID>> singletons(List<ClusterNode> nodes) {
        List<List<UUID>> res = new ArrayList<>(nodes.size());

        for (ClusterNode node : nodes)
            res.add(Collections.singletonList(node.id()));

        return res;
    }

    /**
     * The state guard: the actual ownership is not equal to the affinity assignment yet. The layout is read
     * once and cached in the snapshot, so the engine and the verification reuse the same material. The
     * answer is built from the actual layout: hypergraph, greedy pass in consistent ID order, local search.
     * The size bound is still the configuration bound, which is an upper bound on the group size that the
     * configuration guarantees; the hypergraph constraint enforces the actual layout.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Partition of the snapshot nodes into safe groups built from the actual layout.
     */
    private static List<List<UUID>> splitByOwners(SafeToStopSetsContext ctx) {
        ctx.partitionOwners();

        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        int maxGroupSize = SafeToStopSetsSupport.maxSafeGroupSize(ctx);

        if (maxGroupSize < 2)
            return SafeToStopSetsSupport.singletons(ctx.nodes());

        List<List<UUID>> result = SafeToStopSetsSupport.greedy(hg, ctx.nodes(), maxGroupSize);

        return SafeToStopSetsSupport.repair(result, hg, ctx.nodes(), maxGroupSize);
    }

    /**
     * @param blockers Group name to reason mapping of the snapshot.
     * @return Failure message with the offending groups, the snapshot's reasons and the strategy hint.
     */
    private static String structureOnlyFailure(Map<String, String> blockers) {
        StringBuilder sb = new StringBuilder(
            "The number of copies cannot be derived from the configuration of these groups:");

        for (Map.Entry<String, String> blocker : blockers.entrySet())
            sb.append("\n  ").append(blocker.getKey()).append(": ").append(blocker.getValue());

        return sb.append("\nUse a strategy that computes the actual layout instead (see --strategy).").toString();
    }
}
