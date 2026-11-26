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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.lang.IgniteExperimental;

/**
 * The common building blocks of the safe-to-stop-sets calculation: the exclusions declared by a strategy, the
 * owner hypergraph, the greedy pass, the local search and the verification. Static methods, no state,
 * deterministic: the same snapshot always produces the same result, on any node of the cluster, byte for byte.
 * <p>
 * Safety is not a property of any single block: the greedy pass and the local search only ever return safe
 * groups (they never create a group that contains an edge), and the verification re-checks the final result
 * against the actual layout. A strategy can only affect the quality of the answer, never its safety.
 */
@IgniteExperimental
public final class SafeToStopSetsSupport {
    /** */
    private SafeToStopSetsSupport() {
        // No instances.
    }

    /**
     * Validates the exclusions declared by the strategy and turns them into the mapping the snapshot builder
     * merges with the exclusions of the operator.
     * <p>
     * The declaration is only a declaration: the collections of the snapshot are unmodifiable and reachable
     * only through getters, so a strategy cannot remove a group itself. The core applies the declaration before
     * the snapshot is built, therefore an excluded group is absent from every view of the snapshot and is
     * neither split nor verified.
     * <p>
     * The declaration is checked strictly, because a broken one silently changes the scope of the answer: a
     * group that is not in {@code groups} means that the strategy and the core disagree about the scope, and an
     * empty reason would hide the exclusion from the report. Both are bugs of the implementation, so the
     * failure is an {@link IllegalStateException} — an internal error, not a wrong command line.
     *
     * @param strategy Strategy that declares the exclusions.
     * @param options Options of this run, as given on the command line.
     * @param groups Configurations of the groups in scope, ordered by group name.
     * @return Group name to the declared reason, a subset of {@code groups}, ordered by group name; empty when
     *      the strategy excludes nothing.
     * @throws IllegalStateException If the declaration names an unknown group, omits the reason or is
     *      {@code null}.
     */
    public static Map<String, String> applyExclusions(
        SafeToStopSetsStrategy strategy,
        Map<String, String> options,
        Map<String, CacheGroupInfo> groups
    ) {
        Map<String, String> declared = strategy.excludeGroups(options, groups);

        if (declared == null) {
            throw new IllegalStateException("Strategy \"" + strategy.name() +
                "\" returned null instead of the excluded cache groups");
        }

        if (declared.isEmpty())
            return Collections.emptyMap();

        for (Map.Entry<String, String> entry : declared.entrySet()) {
            String grpName = entry.getKey();

            if (grpName == null || !groups.containsKey(grpName)) {
                throw new IllegalStateException("Strategy \"" + strategy.name() + "\" excluded an unknown cache group " +
                    "[group=" + grpName + ", known=" + groups.keySet() + ']');
            }

            if (entry.getValue() == null || entry.getValue().trim().isEmpty()) {
                throw new IllegalStateException("Strategy \"" + strategy.name() + "\" excluded a cache group " +
                    "without a reason [group=" + grpName + ']');
            }
        }

        // The declaration is re-projected onto the groups, so neither the order nor the map type chosen by the
        // strategy can leak into the snapshot or into the report.

        Map<String, String> res = new LinkedHashMap<>();

        for (String grpName : groups.keySet()) {
            String reason = declared.get(grpName);

            if (reason != null)
                res.put(grpName, reason);
        }

        return Collections.unmodifiableMap(res);
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
     * Computes the group bound the configuration guarantees: one copy less than the smallest copy bound of the
     * cache groups in scope, so a group of that size never holds all copies of a partition.
     * <p>
     * The copy bound of a group is {@code |affinityNodes(group)|}, not the number of server nodes: with
     * persistence the affinity node set is restricted by the baseline, and for a {@code REPLICATED} group it is
     * the copy set itself. For a {@code REPLICATED} group the bound is {@code |affinityNodes(group)|} computed
     * without {@code backups + 1}: {@code backups} is {@link Integer#MAX_VALUE} there and the sum overflows.
     * <p>
     * This is the single definition of the two edge cases the strategies share, so no strategy has to repeat
     * the arithmetic: {@link Integer#MAX_VALUE} when the snapshot has no cache group in scope (there is no copy
     * to lose, the whole cluster may be stopped at once), and {@code 1} when the smallest group has a single
     * copy (no two nodes may be stopped together, every node is a group of its own). There is no other sentinel:
     * a group of one node is always safe.
     * <p>
     * This is also the bound the verification enforces on a configuration-only answer: a strategy that did not read
     * the partition owners may not produce a group larger than this, because the copy count of the
     * configuration is the only safety argument available without the layout.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Maximum size of a safe group, at least {@code 1}; {@link Integer#MAX_VALUE} means "no limit".
     */
    public static int maxSafeGroupSize(SafeToStopSetsContext ctx) {
        if (ctx.cacheGroups().isEmpty())
            return Integer.MAX_VALUE;

        int smallest = Integer.MAX_VALUE;

        for (CacheGroupInfo grp : ctx.cacheGroups().values()) {
            int affinityNodes = ctx.affinityNodesCount(grp.name());

            int copies = grp.mode() == CacheMode.REPLICATED
                ? affinityNodes
                : Math.min(grp.backups() + 1, affinityNodes);

            smallest = Math.min(smallest, copies);
        }

        return Math.max(1, smallest - 1);
    }

    /**
     * Greedy construction of the maximal safe sets, in the given node order. A node is added to a group only
     * when the group contains no edge (owner set) in its entirety after the addition, so the pass never makes
     * a group of more than one node unsafe. The order of the nodes affects the quality of the packing (group
     * sizes, number of singletons), never the safety.
     * <p>
     * The only group that may hold an edge is a group of one node whose owner set is that node alone: such a
     * node cannot be stopped together with any other one, so its group is opened and immediately closed, and
     * no node is ever added to it. Closing the group is what keeps the invariant the packing relies on: the
     * node gets an empty set of requirements (the edge minus the node itself), which reads as "fits
     * everywhere", and only the closed flag stops the next node from joining it.
     * <p>
     * A node is added to the smallest group it fits; when no existing group fits, a new group is opened.
     * The groups are returned in creation order, the node IDs inside a group in processing order.
     *
     * @param hg Owner hypergraph.
     * @param order Nodes to split, in the order the strategy wants them processed.
     * @param maxGroupSize Maximum size of a group, at least {@code 1}; groups are never larger.
     * @return Partition of the nodes into groups, each of size no more than {@code maxGroupSize}; every group
     *      of more than one node is safe.
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
        List<List<Integer>> grps = new ArrayList<>();
        List<BitSet> grpBits = new ArrayList<>();
        BitSet closed = new BitSet();

        for (ClusterNode node : order) {
            int idx = hg.indexOf(node.id());

            requireds.clear();

            boolean alone = false;

            for (BitSet edge : hg.edges()) {
                if (!edge.get(idx))
                    continue;

                BitSet required = (BitSet) edge.clone();

                required.clear(idx);
                requireds.add(required);

                alone |= required.isEmpty();
            }

            int best = -1;
            int bestSize = Integer.MAX_VALUE;

            for (int i = 0; i < grps.size(); i++) {
                if (closed.get(i) || grpBits.get(i).cardinality() >= maxGroupSize)
                    continue;

                if (conflicts(requireds, grpBits.get(i)))
                    continue;

                if (grpBits.get(i).cardinality() < bestSize) {
                    bestSize = grpBits.get(i).cardinality();
                    best = i;
                }
            }

            if (best >= 0) {
                grpBits.get(best).set(idx);
            }
            else {
                BitSet bits = new BitSet(hg.nodes().size());

                bits.set(idx);
                grpBits.add(bits);
                grps.add(new ArrayList<>());

                if (alone)
                    closed.set(grps.size() - 1);
            }

            grps.get(best >= 0 ? best : grps.size() - 1).add(idx);
        }

        List<List<UUID>> res = new ArrayList<>(grps.size());

        for (List<Integer> grp : grps) {
            List<UUID> ids = new ArrayList<>(grp.size());

            for (int idx : grp)
                ids.add(hg.nodes().get(idx).id());

            res.add(ids);
        }

        return res;
    }

    /**
     * Verification of the split integrity and of the data safety of a group list, in a short-circuiting order:
     * ready, stable, split integrity, data safety. The guards cannot be waived by a strategy: they run for
     * every strategy, including ones that implement the SPI directly. The data safety level runs whenever the
     * context has read the owners; a result that was not checked against the actual layout is marked
     * {@code CONFIG_ONLY} with the reason, never printed silently, and is rejected when the context does not
     * allow a configuration-only answer.
     * <p>
     * A configuration-only answer is accepted only within the group size bound the configuration guarantees
     * ({@link #maxSafeGroupSize(SafeToStopSetsContext)}): the copy count of a group is a property of the
     * configuration, so a group that respects the bound keeps at least one live copy of every partition
     * whatever the layout is. The size bound is the only thing a configuration-only answer can prove: it says
     * nothing about <em>where</em> the copies live, so a strategy whose constraint depends on the placement
     * (a cell, a data center, an affinity filter) has to read the partition owners and accept the data safety
     * level instead of relying on the configuration bound.
     *
     * @param grps Candidate partition of the snapshot nodes.
     * @param ctx Snapshot of the cluster state.
     * @return Verification result; {@link SafeToStopSetsVerification#failed()} is {@code true} when the
     *      command must fail instead of printing the groups.
     */
    public static SafeToStopSetsVerification verify(List<List<UUID>> grps, SafeToStopSetsContext ctx) {
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

        List<String> violations = splitIntegrityViolations(grps, ctx);

        if (!violations.isEmpty())
            return new SafeToStopSetsVerification(
                false,
                SafeToStopSetsVerification.Mode.UNPROVABLE,
                violations,
                Collections.emptyList());

        if (!ctx.ownersRead()) {
            Map<String, String> blockers = ctx.configOnlyBlockers();

            if (blockers.isEmpty() && ctx.ownershipMatchesAffinity()) {
                List<String> oversized = oversizedGroups(grps, maxSafeGroupSize(ctx));

                if (oversized.isEmpty())
                    return new SafeToStopSetsVerification(
                        true,
                        SafeToStopSetsVerification.Mode.CONFIG_ONLY,
                        Collections.emptyList(),
                        Collections.singletonList(
                            "The partition owners were not read: the answer is derived from the configuration "
                                + "only (rendezvous affinity, no copy filters, ownership matches the affinity, "
                                + "every group within the safe size bound)"));

                return new SafeToStopSetsVerification(
                    false,
                    SafeToStopSetsVerification.Mode.UNPROVABLE,
                    oversized,
                    Collections.emptyList());
            }

            List<String> reasons = new ArrayList<>();

            for (Map.Entry<String, String> blocker : blockers.entrySet())
                reasons.add(
                    "The configuration does not allow a configuration-only answer for group ["
                        + blocker.getKey() + "]: " + blocker.getValue());

            if (!ctx.ownershipMatchesAffinity())
                reasons.add(
                    "The actual ownership does not match the affinity assignment, the answer must be "
                        + "checked against the actual layout");

            reasons.add("The strategy must read the partition owners, the groups produced without the read: "
                + grps);

            return new SafeToStopSetsVerification(
                false,
                SafeToStopSetsVerification.Mode.UNPROVABLE,
                reasons,
                Collections.emptyList());
        }

        violations = dataSafetyViolations(grps, ctx);

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
     * The size level of a configuration-only answer: without the partition owners the only safety argument left is
     * the copy count of the configuration, so every group must fit into
     * {@link #maxSafeGroupSize(SafeToStopSetsContext)}. A group that does not fit may hold a whole owner set of
     * a partition with fewer copies than the bound assumes, which is exactly the loss the answer claims to
     * prevent. The check is {@code O(#groups)}: it reads the configuration only, so the configuration-only fast
     * path stays free of the layout read.
     *
     * @param grps Candidate partition of the snapshot nodes, already verified for the split integrity.
     * @param maxGroupSize The largest group the configuration proves safe.
     * @return Empty when every group respects the bound, otherwise one human readable reason per offending
     *      group.
     */
    private static List<String> oversizedGroups(List<List<UUID>> grps, int maxGroupSize) {
        if (maxGroupSize == Integer.MAX_VALUE)
            return Collections.emptyList();

        List<String> res = new ArrayList<>();

        for (int i = 0; i < grps.size(); i++) {
            int size = grps.get(i).size();

            if (size > maxGroupSize)
                res.add("Group #" + i + " of " + size + " node(s) exceeds the largest group the configuration "
                    + "proves safe (" + maxGroupSize + "): the answer was not checked against the actual "
                    + "layout, so the strategy must read the partition owners instead of assuming the copy "
                    + "count of the configuration");
        }

        return res;
    }

    /**
     * The split integrity level: the group list must be a partition of the snapshot nodes — every snapshot node
     * in exactly one group, no empty groups, no unknown nodes.
     */
    private static List<String> splitIntegrityViolations(List<List<UUID>> grps, SafeToStopSetsContext ctx) {
        List<String> res = new ArrayList<>();
        Set<UUID> snapshot = new HashSet<>();

        for (ClusterNode node : ctx.nodes())
            snapshot.add(node.id());

        Set<UUID> seen = new HashSet<>();

        for (int i = 0; i < grps.size(); i++) {
            List<UUID> grp = grps.get(i);

            if (grp.isEmpty()) {
                res.add("Group #" + i + " is empty");

                continue;
            }

            for (UUID node : grp) {
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
     * The data safety level: no stop candidate may hold a whole set of partition owners. Singletons are not
     * stop candidates — they are printed as "cannot be stopped together with any other node" — so the
     * invariant only applies to groups of more than one node; the all-singletons result is rejected by the
     * task as "no safe group" instead. The owner sets of the snapshot are already restricted to the
     * snapshot nodes, so a node outside the snapshot can neither form nor satisfy a constraint.
     */
    private static List<String> dataSafetyViolations(List<List<UUID>> grps, SafeToStopSetsContext ctx) {
        List<String> res = new ArrayList<>();

        for (PartitionOwners owners : ctx.partitionOwners()) {
            Set<UUID> set = new HashSet<>(owners.owners());

            for (List<UUID> grp : grps) {
                if (grp.size() > 1 && grp.containsAll(set)) {
                    res.add("Group " + grp + " fully contains the owners of partition ["
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
     * @param grps Initial partition (typically from {@link #greedy}), all groups safe.
     * @param hg Owner hypergraph.
     * @param order Canonical node order (unused in the current implementation, kept for SPI compatibility).
     * @param maxGroupSize Maximum group size, at least {@code 1}.
     * @return Improved partition with fewer singletons (or identical to input if no improvement is possible);
     *      all groups remain safe.
     */
    public static List<List<UUID>> repair(
        List<List<UUID>> grps,
        SafeToStopSetsHypergraph hg,
        List<ClusterNode> order,
        int maxGroupSize
    ) {
        int n = hg.nodes().size();
        int maxIterations = 10 * n * n;
        int iterations = 0;

        List<BitSet> bits = new ArrayList<>(grps.size());

        for (List<UUID> grp : grps) {
            BitSet b = new BitSet(n);

            for (UUID id : grp)
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
     * @param grp Current group as a bit set over the hypergraph nodes.
     * @return {@code true} if adding the candidate node to the group would contain a whole edge.
     */
    private static boolean conflicts(List<BitSet> requireds, BitSet grp) {
        for (BitSet required : requireds) {
            BitSet rest = (BitSet) required.clone();

            rest.andNot(grp);

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
