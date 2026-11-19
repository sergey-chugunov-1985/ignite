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
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.lang.IgniteExperimental;

/**
 * The common building blocks of the safe-to-stop-sets calculation: the owner hypergraph, the greedy pass,
 * the local search and the verification. Static methods, no state, deterministic: the same snapshot always
 * produces the same result, on any node of the cluster, byte for byte.
 * <p>
 * Safety is not a property of any single block: the greedy pass and the local search only ever return safe
 * groups (they never create a group that contains an edge), and the verification re-checks the final result
 * against the actual layout. A strategy can only affect the quality of the answer, never its safety.
 */
@IgniteExperimental
public final class SafeToStopSetsSupport {
    private SafeToStopSetsSupport() {
        /** */
    }

    /**
     * Builds the owner hypergraph of the snapshot: one edge per distinct partition owner set, dominated
     * edges removed. The read is expensive, so the result is cached in the snapshot and shared by the
     * hypergraph, the greedy pass and the verification.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Hypergraph of the owner sets, edges are bit sets over {@link SafeToStopSetsContext#nodes()}.
     */
    public static SafeToStopSetsHypergraph hypergraph(SafeToStopSetsContext ctx) {
        return SafeToStopSetsHypergraph.of(ctx);
    }

    /**
     * Greedy construction of the maximal safe sets, in the given node order. The result is always safe: a
     * node is added to a group only when the group contains no edge (owner set) in its entirety after the
     * addition. The order of the nodes affects the quality of the packing (group sizes, number of
     * singletons), never the safety.
     * <p>
     * A node is added to the smallest group it fits; when no existing group fits, a new group is opened.
     * The groups are returned in creation order, the node IDs inside a group in processing order.
     *
     * @param hg Owner hypergraph.
     * @param order Nodes to split, in the order the strategy wants them processed.
     * @param maxGroupSize Maximum size of a group, at least {@code 1}; groups are never larger.
     * @return Partition of the nodes into safe groups, each of size no more than {@code maxGroupSize}.
     * @throws IllegalArgumentException If {@code maxGroupSize} is less than {@code 1}.
     */
    public static List<List<UUID>> greedy(
        SafeToStopSetsHypergraph hg,
        List<ClusterNode> order,
        int maxGroupSize
    ) {
        if (maxGroupSize < 1)
            throw new IllegalArgumentException("maxGroupSize must be >= 1, got " + maxGroupSize);

        List<BitSet> requireds = new ArrayList<>();
        List<List<Integer>> groups = new ArrayList<>();
        List<BitSet> groupBits = new ArrayList<>();

        for (ClusterNode node : order) {
            int idx = hg.indexOf(node.id());

            requireds.clear();

            for (BitSet edge : hg.edges()) {
                if (!edge.get(idx))
                    continue;

                BitSet required = (BitSet) edge.clone();

                required.clear(idx);
                requireds.add(required);
            }

            int best = -1;
            int bestSize = Integer.MAX_VALUE;

            for (int i = 0; i < groups.size(); i++) {
                if (groupBits.get(i).cardinality() >= maxGroupSize)
                    continue;

                if (conflicts(requireds, groupBits.get(i)))
                    continue;

                if (groupBits.get(i).cardinality() < bestSize) {
                    bestSize = groupBits.get(i).cardinality();
                    best = i;
                }
            }

            if (best >= 0) {
                groupBits.get(best).set(idx);
            }
            else {
                BitSet bits = new BitSet(hg.nodes().size());

                bits.set(idx);
                groupBits.add(bits);
                groups.add(new ArrayList<>());
            }

            groups.get(best >= 0 ? best : groups.size() - 1).add(idx);
        }

        List<List<UUID>> res = new ArrayList<>(groups.size());

        for (List<Integer> group : groups) {
            List<UUID> ids = new ArrayList<>(group.size());

            for (int idx : group)
                ids.add(hg.nodes().get(idx).id());

            res.add(ids);
        }

        return res;
    }

    /**
     * Structural and semantic verification of a group list, in a short-circuiting order: ready, stable,
     * structure, semantics. The guards cannot be waived by a strategy: they run for every strategy, including
     * ones that implement the SPI directly. The semantic level runs whenever the context has read the owners;
     * a result that was not checked against the actual layout is marked {@code STRUCTURE_ONLY} with the
     * reason, never printed silently, and is rejected when the context does not allow a structure-only
     * answer.
     *
     * @param groups Candidate partition of the snapshot nodes.
     * @param ctx Snapshot of the cluster state.
     * @return Verification result; {@link SafeToStopSetsVerification#failed()} is {@code true} when the
     *      command must fail instead of printing the groups.
     */
    public static SafeToStopSetsVerification verify(List<List<UUID>> groups, SafeToStopSetsContext ctx) {
        if (!ctx.partitionOwnersAvailable())
            return new SafeToStopSetsVerification(
                false,
                SafeToStopSetsVerification.Mode.NOT_READY,
                Collections.singletonList(
                    "The partition owners are not available: the cluster topology or the affinity is not "
                        + "ready, no answer can be formed"),
                Collections.emptyList());

        if (!ctx.topologyStable())
            return new SafeToStopSetsVerification(
                false,
                SafeToStopSetsVerification.Mode.UNSTABLE,
                Collections.singletonList(
                    "The topology changed between the snapshot (" + ctx.topologyVersion()
                        + ") and the verification (" + ctx.currentTopologyVersion()
                        + "), the answer is void, retry after the topology stabilizes"),
                Collections.emptyList());

        List<String> violations = structuralViolations(groups, ctx);

        if (!violations.isEmpty())
            return new SafeToStopSetsVerification(
                false,
                SafeToStopSetsVerification.Mode.UNPROVABLE,
                violations,
                Collections.emptyList());

        if (!ctx.ownersRead()) {
            Map<String, String> blockers = ctx.structureOnlyBlockers();

            if (blockers.isEmpty() && ctx.ownershipMatchesAffinity())
                return new SafeToStopSetsVerification(
                    true,
                    SafeToStopSetsVerification.Mode.STRUCTURE_ONLY,
                    Collections.emptyList(),
                    Collections.singletonList(
                        "The partition owners were not read: the answer is derived from the configuration "
                            + "only (rendezvous affinity, no copy filters, ownership matches the affinity)"));

            List<String> reasons = new ArrayList<>();

            for (Map.Entry<String, String> blocker : blockers.entrySet())
                reasons.add(
                    "The configuration does not allow a structure-only answer for group ["
                        + blocker.getKey() + "]: " + blocker.getValue());

            if (!ctx.ownershipMatchesAffinity())
                reasons.add(
                    "The actual ownership does not match the affinity assignment, the answer must be "
                        + "checked against the actual layout");

            reasons.add("The strategy must read the partition owners, the groups produced without the read: "
                + groups);

            return new SafeToStopSetsVerification(
                false,
                SafeToStopSetsVerification.Mode.UNPROVABLE,
                reasons,
                Collections.emptyList());
        }

        violations = semanticViolations(groups, ctx);

        if (!violations.isEmpty())
            return new SafeToStopSetsVerification(
                false,
                SafeToStopSetsVerification.Mode.UNPROVABLE,
                violations,
                Collections.emptyList());

        return new SafeToStopSetsVerification(
            true,
            SafeToStopSetsVerification.Mode.EXACT,
            Collections.emptyList(),
            Collections.emptyList());
    }

    /**
     * The structure level: the group list must be a partition of the snapshot nodes — every snapshot node in
     * exactly one group, no empty groups, no unknown nodes.
     */
    private static List<String> structuralViolations(List<List<UUID>> groups, SafeToStopSetsContext ctx) {
        List<String> res = new ArrayList<>();
        Set<UUID> snapshot = new HashSet<>();

        for (ClusterNode node : ctx.nodes())
            snapshot.add(node.id());

        Set<UUID> seen = new HashSet<>();

        for (int i = 0; i < groups.size(); i++) {
            List<UUID> group = groups.get(i);

            if (group.isEmpty()) {
                res.add("Group #" + i + " is empty");

                continue;
            }

            for (UUID node : group) {
                if (!seen.add(node))
                    res.add("Node [" + node + "] belongs to more than one group");

                if (!snapshot.contains(node))
                    res.add("Group #" + i + " contains a node outside the snapshot: [" + node + "]");
            }
        }

        for (ClusterNode node : ctx.nodes()) {
            if (!seen.contains(node.id()))
                res.add("Node [" + node.id() + "] is not in any group");
        }

        return res;
    }

    /**
     * The semantic level: no group may hold a whole set of partition owners. The owner sets of the snapshot
     * are already restricted to the snapshot nodes, so a node outside the snapshot can neither form nor
     * satisfy a constraint.
     */
    private static List<String> semanticViolations(List<List<UUID>> groups, SafeToStopSetsContext ctx) {
        List<String> res = new ArrayList<>();

        for (PartitionOwners owners : ctx.partitionOwners()) {
            Set<UUID> set = new HashSet<>(owners.owners());

            for (List<UUID> group : groups) {
                if (group.containsAll(set)) {
                    res.add("Group " + group + " fully contains the owners of partition ["
                        + owners.cacheGroupName() + '/' + owners.partition() + "]: " + owners.owners());

                    break;
                }
            }
        }

        return res;
    }

    /**
     * Anytime local search that merges singleton nodes into existing groups, without violating the safety
     * invariant. The result is always safe: a node is only added to a group when the group does not contain
     * any edge after the addition.
     * <p>
     * Two types of moves are tried in each pass:
     * <ol>
     *   <li>Direct merge: a singleton {@code v} is added to the smallest group {@code g} where the result
     *       is safe and {@code |g| < maxGroupSize}.</li>
     *   <li>Swap-merge: a node {@code u} is moved out of group {@code g} to make room for singleton
     *       {@code v}; the swap is kept only if {@code (g - u) ∪ {v}} is safe <em>and</em> {@code u} can
     *       then be merged into another group (otherwise the swap is reverted).</li>
     * </ol>
     * The search is deterministic (fixed sweep order by group index and node bit position), and stops when
     * no improvement is possible or the iteration budget is exhausted.
     *
     * @param groups Initial partition (typically from {@link #greedy}), all groups safe.
     * @param hg Owner hypergraph.
     * @param order Canonical node order (unused in the current implementation, kept for SPI compatibility).
     * @param maxGroupSize Maximum group size, at least {@code 1}.
     * @return Improved partition with fewer singletons (or identical to input if no improvement is possible);
     *      all groups remain safe.
     */
    public static List<List<UUID>> repair(
        List<List<UUID>> groups,
        SafeToStopSetsHypergraph hg,
        List<ClusterNode> order,
        int maxGroupSize
    ) {
        int n = hg.nodes().size();
        int maxIterations = 10 * n * n;
        int iterations = 0;

        List<BitSet> bits = new ArrayList<>(groups.size());

        for (List<UUID> group : groups) {
            BitSet b = new BitSet(n);

            for (UUID id : group)
                b.set(hg.indexOf(id));

            bits.add(b);
        }

        boolean improved;

        do {
            improved = false;

            for (int si = 0; si < bits.size(); si++) {
                if (iterations >= maxIterations)
                    break;

                BitSet singleton = bits.get(si);

                if (singleton.cardinality() != 1)
                    continue;

                int v = singleton.nextSetBit(0);

                // Direct merge: try each group (smallest first, then by index)
                int bestDirect = -1;
                int bestDirectSize = Integer.MAX_VALUE;

                for (int gi = 0; gi < bits.size(); gi++) {
                    if (gi == si)
                        continue;

                    BitSet target = bits.get(gi);

                    if (target.cardinality() >= maxGroupSize)
                        continue;

                    BitSet candidate = (BitSet) target.clone();
                    candidate.set(v);

                    if (hg.violations(candidate) == 0 && target.cardinality() < bestDirectSize) {
                        bestDirectSize = target.cardinality();
                        bestDirect = gi;
                    }
                }

                if (bestDirect >= 0) {
                    bits.get(bestDirect).set(v);
                    bits.remove(si);
                    improved = true;
                    break;
                }

                // Swap-merge: v goes to g (replacing u), u must fit in another group
                boolean swapped = false;

                for (int gi = 0; gi < bits.size() && !swapped; gi++) {
                    if (gi == si)
                        continue;

                    BitSet target = bits.get(gi);

                    if (target.cardinality() == 1)
                        continue;

                    for (int u = target.nextSetBit(0); u >= 0; u = target.nextSetBit(u + 1)) {
                        if (iterations >= maxIterations)
                            break;

                        iterations++;

                        BitSet newTarget = (BitSet) target.clone();
                        newTarget.clear(u);
                        newTarget.set(v);

                        if (hg.violations(newTarget) != 0)
                            continue;

                        // u is now a singleton: check it is safe alone (no size-1 edge at u)
                        BitSet uAlone = new BitSet(n);
                        uAlone.set(u);

                        if (hg.violations(uAlone) != 0)
                            continue;

                        // Try to merge u into another group
                        int mergeTo = -1;
                        int mergeToSize = Integer.MAX_VALUE;

                        for (int mi = 0; mi < bits.size(); mi++) {
                            if (mi == gi || mi == si)
                                continue;

                            BitSet other = bits.get(mi);

                            if (other.cardinality() >= maxGroupSize)
                                continue;

                            BitSet cand = (BitSet) other.clone();
                            cand.set(u);

                            if (hg.violations(cand) == 0 && other.cardinality() < mergeToSize) {
                                mergeToSize = other.cardinality();
                                mergeTo = mi;
                            }
                        }

                        if (mergeTo >= 0) {
                            bits.set(gi, newTarget);
                            bits.get(mergeTo).set(u);
                            bits.remove(si);
                            improved = true;
                            swapped = true;
                            break;
                        }
                    }
                }
            }
        } while (improved && iterations < maxIterations);

        List<List<UUID>> res = new ArrayList<>(bits.size());

        for (BitSet b : bits) {
            List<UUID> ids = new ArrayList<>(b.cardinality());

            for (int i = 0; i < n; i++) {
                if (b.get(i))
                    ids.add(hg.nodes().get(i).id());
            }

            res.add(ids);
        }

        return res;
    }

    /**
     * @param requireds Bit sets of the edges containing the candidate node, each without the node itself;
     *      an empty required set means an edge consisting of the candidate node alone, which conflicts with
     *      any group.
     * @param group Current group as a bit set over the hypergraph nodes.
     * @return {@code true} if adding the candidate node to the group would contain a whole edge.
     */
    private static boolean conflicts(List<BitSet> requireds, BitSet group) {
        for (BitSet required : requireds) {
            BitSet rest = (BitSet) required.clone();

            rest.andNot(group);

            if (rest.isEmpty())
                return true;
        }

        return false;
    }

    /**
     * @param nodes Snapshot nodes, ordered by consistent ID.
     * @return A group of one node per snapshot node: no two nodes may be stopped together.
     */
    public static List<List<UUID>> singletons(List<ClusterNode> nodes) {
        List<List<UUID>> res = new ArrayList<>(nodes.size());

        for (ClusterNode node : nodes)
            res.add(Collections.singletonList(node.id()));

        return res;
    }
}
