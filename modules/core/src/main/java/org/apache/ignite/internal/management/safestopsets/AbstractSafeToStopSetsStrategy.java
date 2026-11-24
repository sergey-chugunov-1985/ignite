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

import java.util.List;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.lang.IgniteExperimental;

/**
 * Base class for strategies that always read the partition owners and build the answer from the actual
 * layout using the common engine (hypergraph, greedy, repair). The {@link #split(SafeToStopSetsContext)}
 * method is final: it orchestrates the engine and the subclasses only control the source of the safety
 * constraint ({@link #hypergraph(SafeToStopSetsContext)}), the node order, the maximum group size, and
 * whether the local search is enabled.
 * <p>
 * A strategy that does not fit this shape (for example, the configuration-only {@code backups-count} on
 * its fast path) implements {@link SafeToStopSetsStrategy} directly.
 */
@IgniteExperimental
public abstract class AbstractSafeToStopSetsStrategy implements SafeToStopSetsStrategy {
    /**
     * Builds the answer from the actual layout: reads the partition owners, builds the hypergraph, runs the
     * greedy pass in the order given by {@link #orderNodes(SafeToStopSetsContext)} with the size bound from
     * {@link #maxGroupSize(SafeToStopSetsContext)}, and optionally applies the local search.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Partition of the snapshot nodes into safe groups.
     */
    @Override
    public final List<List<UUID>> split(SafeToStopSetsContext ctx) {
        ctx.partitionOwners();

        SafeToStopSetsHypergraph hg = hypergraph(ctx);

        List<ClusterNode> order = orderNodes(ctx);

        int maxGroupSize = maxGroupSize(ctx);

        if (maxGroupSize < 1)
            maxGroupSize = 1;

        List<List<UUID>> result = SafeToStopSetsSupport.greedy(hg, order, maxGroupSize);

        if (repairEnabled() && maxGroupSize > 1)
            result = SafeToStopSetsSupport.repair(result, hg, order, maxGroupSize);

        return result;
    }

    /**
     * Builds the hypergraph the greedy pass and the local search work with: the source of the safety constraint
     * of the strategy. The default is the owner hypergraph of the snapshot
     * ({@link SafeToStopSetsSupport#hypergraph(SafeToStopSetsContext)}), that is the constraint "no partition
     * loses its last copy".
     * <p>
     * A strategy with a stronger domain constraint, expressed by another set of edges over the same nodes (for
     * example, the copies of a partition inside a single data center), overrides this method and builds the
     * hypergraph with {@link SafeToStopSetsHypergraph#of(java.util.List, java.util.Collection)}. The contract of
     * an override:
     * <ol>
     * <li>the result must be a deterministic function of {@code ctx}: the answer is reproducible on any node of
     * the cluster, byte for byte;</li>
     * <li>the method is called after the owners are materialized: {@link #split} reads them first, so
     * {@link SafeToStopSetsContext#ownersRead()} is already {@code true}, and the edges may be derived from
     * {@link SafeToStopSetsContext#partitionOwners()};</li>
     * <li>the <b>coverage contract</b>: every owner set {@code H(c, p)} of the snapshot must be covered by a
     * non-empty edge of the result, that is the edges are built as a partition of each {@code H(c, p)} into
     * non-empty parts (for example, its intersections with the data centers), plus the additional edges the
     * domain requires. Edges weaker than the owner sets (a superset of an owner set with none of its parts) are
     * forbidden: such an answer loses the last copy of a partition.</li>
     * </ol>
     * <p>
     * The core verifies only the default invariant "no group contains an owner set". Thanks to the coverage
     * contract that check is a fail-closed guard for an override too: an answer that keeps every edge of the
     * result out of every group also keeps every owner set out of every group. The domain constraint itself is
     * <em>not</em> verified by the core, because it is not expressed by
     * {@link SafeToStopSetsContext#partitionOwners()}: its correctness is the responsibility of the strategy.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Hypergraph of the safety constraint of the strategy, see the coverage contract above.
     */
    protected SafeToStopSetsHypergraph hypergraph(SafeToStopSetsContext ctx) {
        return SafeToStopSetsSupport.hypergraph(ctx);
    }

    /**
     * @param ctx Snapshot of the cluster state.
     * @return Nodes in the order the greedy pass should process them. Subclasses use this to encode their
     *      domain-specific ordering (cell interleaving, data center round-robin, etc.).
     */
    protected abstract List<ClusterNode> orderNodes(SafeToStopSetsContext ctx);

    /**
     * @param ctx Snapshot of the cluster state.
     * @return Maximum size of a group the strategy wants to produce, at least {@code 1}.
     */
    protected abstract int maxGroupSize(SafeToStopSetsContext ctx);

    /**
     * @return Whether the local search ({@link SafeToStopSetsSupport#repair}) is applied after the greedy
     *      pass to merge singletons into existing groups.
     */
    protected boolean repairEnabled() {
        return true;
    }
}
