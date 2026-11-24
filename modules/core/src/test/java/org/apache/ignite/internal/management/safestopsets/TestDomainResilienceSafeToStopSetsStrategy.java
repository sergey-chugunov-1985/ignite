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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;

/**
 * Test-only strategy of the domain-resilience kind: the safety constraint is "one copy of every partition stays
 * in every domain", so the edges of the hypergraph are the intersections {@code H(c, p) ∩ S_d} of the snapshot
 * partition owners with the domains.
 * <p>
 * The domains are the parity of the node position in {@link SafeToStopSetsContext#nodes()}: the snapshot nodes
 * are ordered by consistent id, so every node of the cluster derives the same mapping and the answer stays a
 * deterministic function of the snapshot. An out-of-tree plugin uses the real data center id of the node instead;
 * the test keeps the rule local so the cluster needs no per-node attribute.
 * <p>
 * The strategy is registered in {@code src/test/java/META-INF/services} and is resolved by name
 * ({@value #NAME}) exactly as an out-of-tree provider would be: the integration test runs the full task path on a
 * real cluster and checks that an answer with a domain constraint over the extension point is verified as
 * {@link SafeToStopSetsVerification.Mode#EXACT}.
 */
public class TestDomainResilienceSafeToStopSetsStrategy extends AbstractSafeToStopSetsStrategy {
    /** Strategy name, test-only. */
    public static final String NAME = "test-domain-resilience";

    /** Number of domains the parity rule produces for a cluster of two or more nodes. */
    private static final int DOMAINS = 2;

    /** {@inheritDoc} */
    @Override public String name() {
        return NAME;
    }

    /**
     * Builds the edges as a partition of every owner set {@code H(c, p)} into the parts inside each domain, as
     * the coverage contract of the hook requires: the intersections are non-empty, they cover the whole owner
     * set, and none of them is weaker than the owner set itself.
     *
     * @param ctx Snapshot of the cluster state, the owners are already read.
     * @return Hypergraph of the domain parts of the owner sets.
     */
    @Override protected SafeToStopSetsHypergraph hypergraph(SafeToStopSetsContext ctx) {
        Map<UUID, Integer> domains = domains(ctx.nodes());

        List<List<UUID>> edges = new ArrayList<>();

        for (PartitionOwners owners : ctx.partitionOwners()) {
            Map<Integer, List<UUID>> parts = new LinkedHashMap<>();

            for (UUID id : owners.owners()) {
                Integer domain = domains.get(id);

                if (domain == null)
                    throw new IllegalStateException("Owner [" + id + "] is not a snapshot node");

                parts.computeIfAbsent(domain, key -> new ArrayList<>()).add(id);
            }

            edges.addAll(parts.values());
        }

        return SafeToStopSetsHypergraph.of(ctx.nodes(), edges);
    }

    /** {@inheritDoc} */
    @Override protected List<ClusterNode> orderNodes(SafeToStopSetsContext ctx) {
        return ctx.nodes();
    }

    /** {@inheritDoc} */
    @Override protected int maxGroupSize(SafeToStopSetsContext ctx) {
        // The domain promise "a group takes at most one node of a domain" degrades as soon as a group grows past
        // the number of domains: the same rule as the plugin bound min(m_cfg - 1, dcsNum).
        return Math.min(DOMAINS, ctx.nodes().size());
    }

    /**
     * @param nodes Snapshot nodes, ordered by consistent id.
     * @return Node id to domain index mapping: the parity of the node position in the snapshot.
     */
    public static Map<UUID, Integer> domains(List<ClusterNode> nodes) {
        Map<UUID, Integer> res = new LinkedHashMap<>();

        for (int i = 0; i < nodes.size(); i++)
            res.put(nodes.get(i).id(), i % DOMAINS);

        return res;
    }
}
