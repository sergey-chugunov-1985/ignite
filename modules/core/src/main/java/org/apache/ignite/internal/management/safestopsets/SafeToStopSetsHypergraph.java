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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.lang.IgniteExperimental;
import org.jetbrains.annotations.Nullable;

/**
 * Hypergraph of the safety constraints of a snapshot: a group of nodes is safe when it does not contain any
 * edge in its entirety, an edge being a set of nodes that may not be stopped all at once. The default edge set
 * is the partition owner sets {@code H(c, p)} of the snapshot, see {@link #of(SafeToStopSetsContext)}; a
 * strategy whose own constraint is expressed by another set of edges on the same nodes builds the hypergraph
 * with {@link #of(List, Collection)}.
 * <p>
 * An edge is a {@link BitSet} over the snapshot nodes, in the order of {@link #nodes()}. Duplicate edges are
 * collapsed into a single edge, and every edge that strictly contains another edge is dropped: the constraint
 * of the smaller edge implies the constraint of the larger one, so the larger one is redundant.
 * <p>
 * The class is deterministic: the same nodes and the same set of edges always produce the same edge list, byte
 * for byte, regardless of the iteration order of the input.
 */
@IgniteExperimental
public final class SafeToStopSetsHypergraph {
    /** Snapshot nodes; an edge is a bit set over these indices. */
    private final List<ClusterNode> nodes;

    /** Node ID to edge bit set index. */
    private final Map<UUID, Integer> nodeIndex;

    /** Deduplicated, undominated edges, sorted by size and then by bits. */
    private final List<BitSet> edges;

    /** Size of the smallest edge, {@code 0} when there are no edges. */
    private final int minEdgeSize;

    /** Size of the largest edge, {@code 0} when there are no edges. */
    private final int maxEdgeSize;

    /**
     * @param nodes Snapshot nodes, ordered; an edge is a bit set over these indices.
     * @param edges Deduplicated, undominated edges.
     */
    private SafeToStopSetsHypergraph(List<ClusterNode> nodes, List<BitSet> edges) {
        this.nodes = nodes;
        this.edges = edges;

        Map<UUID, Integer> index = new HashMap<>(nodes.size() * 2);

        for (int i = 0; i < nodes.size(); i++)
            index.put(nodes.get(i).id(), i);

        this.nodeIndex = Collections.unmodifiableMap(index);

        int min = 0;
        int max = 0;

        for (BitSet edge : edges) {
            int size = edge.cardinality();

            if (min == 0)
                min = size;
            else
                min = Math.min(min, size);

            max = Math.max(max, size);
        }

        this.minEdgeSize = min;
        this.maxEdgeSize = max;
    }

    /**
     * Builds the hypergraph from the snapshot partition owners: one edge per distinct owner set, dominated
     * edges removed. The owner sets of the snapshot are already restricted to the snapshot nodes.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Hypergraph of the owner sets.
     * @throws IllegalStateException If an owner set is empty after the restriction to the snapshot nodes, or an
     *      owner is not a snapshot node: both are snapshot contract violations, and a silently shrunken edge
     *      would make the answer more restrictive than the layout, while a silently dropped owner would make
     *      it less restrictive, so both must fail.
     */
    public static SafeToStopSetsHypergraph of(SafeToStopSetsContext ctx) {
        List<PartitionOwners> owned = ctx.partitionOwners();

        List<List<UUID>> edges = new ArrayList<>(owned.size());
        List<String> origins = new ArrayList<>(owned.size());

        for (PartitionOwners owners : owned) {
            edges.add(owners.owners());

            origins.add("[" + owners.cacheGroupName() + '/' + owners.partition() + ']');
        }

        return of(ctx.nodes(), edges, origins);
    }

    /**
     * Builds the hypergraph from an arbitrary set of edges over the snapshot nodes, normalized exactly as
     * {@link #of(SafeToStopSetsContext)} does: duplicates collapsed, dominated edges dropped, the rest sorted by
     * size and then by bits.
     * <p>
     * This is the extension point for a strategy whose safety constraint is another set of edges on the same
     * nodes, for example the copies of a partition inside a single data center. The edges are the responsibility
     * of the strategy: the class only guarantees that the group-wise constraint ("no edge is contained in a
     * group") is normalized and checked in one place. An edge weaker than an owner set {@code H(c, p)} of the
     * snapshot does not prove the default invariant, so a strategy that feeds the result to
     * {@link SafeToStopSetsSupport#greedy} must build its edges as a partition of every owner set into non-empty
     * parts (plus whatever additional edges its own domain requires), see
     * {@link AbstractSafeToStopSetsStrategy#hypergraph(SafeToStopSetsContext)}.
     *
     * @param nodes Snapshot nodes, an edge is a set of IDs of these nodes.
     * @param edges Edges over {@code nodes}: each edge is a set of nodes that may not be stopped all at once.
     * @return Hypergraph of the edges, deduplicated, undominated and sorted.
     * @throws IllegalStateException If an edge references a node that is not in {@code nodes}, or an edge is
     *      empty: both are contract violations of the caller, and a silently shrunken edge would make the answer
     *      more restrictive than intended, while a silently dropped node would make it less restrictive, so both
     *      must fail.
     */
    public static SafeToStopSetsHypergraph of(List<ClusterNode> nodes, Collection<List<UUID>> edges) {
        return of(nodes, edges, null);
    }

    /**
     * Normalizes an edge set and builds the hypergraph: the single place where deduplication, dominated removal
     * and sorting live, shared by {@link #of(SafeToStopSetsContext)} and {@link #of(List, Collection)}.
     *
     * @param nodes Snapshot nodes, an edge is a set of IDs of these nodes.
     * @param edges Edges over {@code nodes}.
     * @param origins Per-edge coordinates for the failure messages, in the order of {@code edges}, {@code null}
     *      when the edges do not come from the partitions of the snapshot and the index is used instead.
     * @return Hypergraph of the edges, deduplicated, undominated and sorted.
     * @throws IllegalStateException If an edge references a node that is not in {@code nodes}, or an edge is
     *      empty.
     */
    private static SafeToStopSetsHypergraph of(
        List<ClusterNode> nodes,
        Collection<List<UUID>> edges,
        @Nullable List<String> origins
    ) {
        Map<UUID, Integer> index = new HashMap<>(nodes.size() * 2);

        for (int i = 0; i < nodes.size(); i++)
            index.put(nodes.get(i).id(), i);

        Set<BitSet> distinct = new HashSet<>();

        int edgeIdx = 0;

        for (List<UUID> nodeIds : edges) {
            BitSet edge = new BitSet(nodes.size());

            for (UUID nodeId : nodeIds) {
                Integer idx = index.get(nodeId);

                if (idx == null)
                    throw new IllegalStateException(foreignNodeMsg(origins, nodeId, edgeIdx));

                edge.set(idx);
            }

            if (edge.isEmpty())
                throw new IllegalStateException(emptyEdgeMsg(origins, edgeIdx));

            distinct.add(edge);

            edgeIdx++;
        }

        List<BitSet> sorted = new ArrayList<>(distinct);

        sorted.sort(SafeToStopSetsHypergraph::compare);

        List<BitSet> res = new ArrayList<>(sorted.size());

        for (BitSet edge : sorted) {
            boolean dominated = false;

            for (BitSet kept : res) {
                if (kept.cardinality() < edge.cardinality() && contains(kept, edge)) {
                    dominated = true;

                    break;
                }
            }

            if (!dominated)
                res.add(edge);
        }

        return new SafeToStopSetsHypergraph(nodes, res);
    }

    /**
     * @param origins Per-edge coordinates of the snapshot partitions, {@code null} for arbitrary edges.
     * @param nodeId Node of an edge that is not in the node list.
     * @param edgeIdx Index of the edge in the input.
     * @return Message of the fail-fast on a node that is not in the node list.
     */
    private static String foreignNodeMsg(@Nullable List<String> origins, UUID nodeId, int edgeIdx) {
        if (origins == null) {
            return "Node of an edge is not a snapshot node: edge #" + edgeIdx + ", node [" + nodeId + "], the edges "
                + "must be built over the nodes of the snapshot, this is a contract violation.";
        }

        return "Owner is not a snapshot node: partition " + origins.get(edgeIdx) + ", owner [" + nodeId + "], nodes "
            + "of the snapshot are the restricted owner sets, this is a snapshot contract violation.";
    }

    /**
     * @param origins Per-edge coordinates of the snapshot partitions, {@code null} for arbitrary edges.
     * @param edgeIdx Index of the edge in the input.
     * @return Message of the fail-fast on an empty edge.
     */
    private static String emptyEdgeMsg(@Nullable List<String> origins, int edgeIdx) {
        if (origins == null) {
            return "Edge #" + edgeIdx + " is empty: an empty edge is contained in any group, so no group would be "
                + "safe, this is a contract violation.";
        }

        return "Partition has no owner left among the snapshot nodes: " + origins.get(edgeIdx) + "; the snapshot "
            + "must refuse to produce such a partition.";
    }

    /**
     * @return Snapshot nodes, in the order the edge bit sets are indexed by.
     */
    public List<ClusterNode> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    /**
     * @return Deduplicated, undominated edges, sorted by size and then by bits.
     */
    public List<BitSet> edges() {
        return Collections.unmodifiableList(edges);
    }

    /**
     * @param nodeId A snapshot node ID.
     * @return Index of the node in the edge bit sets.
     * @throws IllegalStateException If the node is not in the snapshot.
     */
    public int indexOf(UUID nodeId) {
        Integer idx = nodeIndex.get(nodeId);

        if (idx == null)
            throw new IllegalStateException("Node is not in the snapshot: " + nodeId);

        return idx;
    }

    /**
     * @return Number of edges after deduplication and dominated removal.
     */
    public int edgeCount() {
        return edges.size();
    }

    /**
     * @return Size of the smallest edge, {@code 0} when there are no edges.
     */
    public int minEdgeSize() {
        return minEdgeSize;
    }

    /**
     * @return Size of the largest edge, {@code 0} when there are no edges.
     */
    public int maxEdgeSize() {
        return maxEdgeSize;
    }

    /**
     * Counts the edges fully contained in a candidate group: the group is safe when the count is {@code 0}.
     * This is the data safety level of the verification, and the conflict metric of the local search.
     *
     * @param group Candidate group as a bit set over {@link #nodes()}.
     * @return Number of edges fully contained in the group.
     */
    public int violations(BitSet group) {
        int res = 0;

        for (BitSet edge : edges)
            if (contains(edge, group))
                res++;

        return res;
    }

    /**
     * @param smaller An edge bit set.
     * @param superset A bit set to test.
     * @return {@code true} if every bit of {@code smaller} is set in {@code superset}.
     */
    private static boolean contains(BitSet smaller, BitSet superset) {
        BitSet rest = (BitSet) smaller.clone();

        rest.andNot(superset);

        return rest.isEmpty();
    }

    /**
     * Deterministic edge order: by size, then by bit pattern, independent of the iteration order of any hash
     * structure.
     */
    private static int compare(BitSet a, BitSet b) {
        int bySize = Integer.compare(a.cardinality(), b.cardinality());

        if (bySize != 0)
            return bySize;

        long[] la = a.toLongArray();
        long[] lb = b.toLongArray();
        int len = Math.max(la.length, lb.length);

        for (int i = 0; i < len; i++) {
            long x = i < la.length ? la[i] : 0L;
            long y = i < lb.length ? lb[i] : 0L;

            if (x != y)
                return Long.compare(x, y);
        }

        return 0;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The edges themselves are not printed: one edge is a bit set over every snapshot node, so the full list
     * would be tens of kilobytes on one log line. The sizes are what a reader needs to interpret the answer.
     */
    @Override public String toString() {
        return S.toString(SafeToStopSetsHypergraph.class, this,
            "nodesSize", nodes.size(),
            "edgesSize", edges.size());
    }
}
