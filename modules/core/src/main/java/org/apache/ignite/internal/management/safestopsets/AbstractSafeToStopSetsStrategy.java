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
 * method is final: it orchestrates the engine and the subclasses only control the node order, the maximum
 * group size, and whether the local search is enabled.
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

        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

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
