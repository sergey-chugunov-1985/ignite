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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.lang.IgniteExperimental;

/**
 * Hypergraph of the partition owner sets of a snapshot, the source of the safety constraint: a group is safe
 * when it does not contain any edge (owner set) in its entirety.
 * <p>
 * An edge is a {@link BitSet} over the snapshot nodes, in the order of {@link #nodes()}. Duplicate owner sets
 * are collapsed into a single edge, and every edge that strictly contains another edge is dropped: the
 * constraint of the smaller edge implies the constraint of the larger one, so the larger one is redundant.
 * <p>
 * The class is deterministic: the same snapshot always produces the same edge list, byte for byte.
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
        List<ClusterNode> nodes = ctx.nodes();

        Map<UUID, Integer> index = new HashMap<>(nodes.size() * 2);

        for (int i = 0; i < nodes.size(); i++)
            index.put(nodes.get(i).id(), i);

        Set<BitSet> distinct = new HashSet<>();

        for (PartitionOwners owners : ctx.partitionOwners()) {
            BitSet edge = new BitSet(nodes.size());

            for (UUID owner : owners.owners()) {
                Integer idx = index.get(owner);

                if (idx == null)
                    throw new IllegalStateException(
                        "Owner is not a snapshot node: partition ["
                            + owners.cacheGroupName() + '/' + owners.partition() + "], owner [" + owner + "], nodes "
                            + "of the snapshot are the restricted owner sets, this is a snapshot contract violation.");

                edge.set(idx);
            }

            if (edge.isEmpty())
                throw new IllegalStateException(
                    "Partition has no owner left among the snapshot nodes: ["
                        + owners.cacheGroupName() + '/' + owners.partition() + "]; the snapshot must refuse to "
                        + "produce such a partition.");

            distinct.add(edge);
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
     * This is the semantic level of the verification, and the conflict metric of the local search.
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

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SafeToStopSetsHypergraph.class, this);
    }
}
