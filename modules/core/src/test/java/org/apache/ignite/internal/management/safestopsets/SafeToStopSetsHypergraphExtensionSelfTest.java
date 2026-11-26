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
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cluster.ClusterNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the hypergraph extension point of the strategies: the public factory
 * {@link SafeToStopSetsHypergraph#of(List, java.util.Collection)}, the {@link AbstractSafeToStopSetsStrategy#hypergraph}
 * hook, its routing through {@link SafeToStopSetsStrategy#split}, and the coverage contract the fail-closed
 * verification of an override relies on. No cluster is needed: the engine is driven by the test snapshot.
 */
public class SafeToStopSetsHypergraphExtensionSelfTest {
    /** Name of the group used in the configuration. */
    private static final String GRP = "grp";

    // ---------------------------------------------------------------------
    // Hypergraph factory
    // ---------------------------------------------------------------------

    /**
     * The public factory over an explicit edge list builds the same hypergraph as the snapshot factory fed with
     * the same edges as owner sets: same edges, same edge count, same size bounds, same violations on samples.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testFactoriesAreEquivalent() throws Exception {
        List<UUID> nodes = ids(5);
        int[][] sets = {{0, 1}, {0, 1}, {1, 2, 3}, {3, 4}, {0, 2}};

        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, sets));

        SafeToStopSetsHypergraph fromCtx = SafeToStopSetsHypergraph.of(ctx);
        SafeToStopSetsHypergraph fromEdges = SafeToStopSetsHypergraph.of(ctx.nodes(), edgesOf(nodes, sets));

        assertEquals(fromCtx.edges(), fromEdges.edges());
        assertEquals(fromCtx.edgeCount(), fromEdges.edgeCount());
        assertEquals(fromCtx.minEdgeSize(), fromEdges.minEdgeSize());
        assertEquals(fromCtx.maxEdgeSize(), fromEdges.maxEdgeSize());
        assertEquals(fromCtx.nodes().size(), fromEdges.nodes().size());

        List<List<UUID>> samples = Arrays.asList(
            nodes.subList(0, 2), nodes.subList(2, 4), nodes.subList(1, 4), nodes);

        for (List<UUID> sample : samples) {
            assertEquals("Violations must not depend on the factory",
                fromCtx.violations(bits(fromCtx, sample)), fromEdges.violations(bits(fromEdges, sample)));
        }

        assertEquals("The duplicate is collapsed", 4, fromEdges.edgeCount());
        assertEquals(2, fromEdges.minEdgeSize());
        assertEquals(3, fromEdges.maxEdgeSize());
    }

    /**
     * The public factory normalizes exactly as the snapshot factory: duplicates collapsed, dominated edges
     * dropped, the rest sorted by size and then by bits; a node outside the node list and an empty edge are
     * rejected fail-fast.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNewFactoryNormalization() throws Exception {
        List<UUID> nodes = ids(3);
        List<ClusterNode> clusterNodes = ctx(nodes, Collections.emptyList()).nodes();

        UUID a = nodes.get(0);
        UUID b = nodes.get(1);
        UUID c = nodes.get(2);

        SafeToStopSetsHypergraph hg = SafeToStopSetsHypergraph.of(clusterNodes, Arrays.asList(
            singleton(a), Arrays.asList(a, b), Arrays.asList(a, b), Arrays.asList(a, b, c)));

        assertEquals("The duplicate and the dominated edges must be removed", 1, hg.edgeCount());
        assertEquals(1, hg.minEdgeSize());
        assertEquals(1, hg.maxEdgeSize());

        // {A} dominates {A,B,C}; {B,C} is neither a duplicate nor dominated and stays.
        hg = SafeToStopSetsHypergraph.of(clusterNodes, Arrays.asList(
            Arrays.asList(a, b, c), Arrays.asList(b, c), singleton(a)));

        assertEquals(2, hg.edgeCount());
        assertEquals("Edges are sorted by size, then by bits", bits(hg, singleton(a)), hg.edges().get(0));
        assertEquals(bits(hg, Arrays.asList(b, c)), hg.edges().get(1));

        try {
            SafeToStopSetsHypergraph.of(clusterNodes, Collections.singletonList(singleton(UUID.randomUUID())));

            fail("An edge over a node outside the node list must be rejected");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("not a snapshot node"));
        }

        try {
            SafeToStopSetsHypergraph.of(clusterNodes, Collections.singletonList(Collections.emptyList()));

            fail("An empty edge must be rejected");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("is empty"));
        }
    }

    /**
     * The same edges in a different input order give the same hypergraph: the edge list is sorted, so the result
     * does not depend on the iteration order of any hash structure the strategy used to build its edges.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNewFactoryDeterminism() throws Exception {
        List<UUID> nodes = ids(6);
        int[][] sets = {{0, 1}, {2, 3}, {4, 5}, {0, 2, 4}, {1, 3, 5}, {0, 1, 2}, {2, 3}};

        List<ClusterNode> clusterNodes = ctx(nodes, Collections.emptyList()).nodes();

        List<List<UUID>> forward = edgesOf(nodes, sets);

        SafeToStopSetsHypergraph h1 = SafeToStopSetsHypergraph.of(clusterNodes, forward);
        SafeToStopSetsHypergraph h2 = SafeToStopSetsHypergraph.of(clusterNodes, reversed(forward));
        SafeToStopSetsHypergraph h3 = SafeToStopSetsHypergraph.of(clusterNodes, reordered(forward));

        assertEquals("The edge list must not depend on the input order", h1.edges(), h2.edges());
        assertEquals(h1.edges(), h3.edges());
        assertEquals(h1.edgeCount(), h3.edgeCount());
        assertEquals(h1.minEdgeSize(), h3.minEdgeSize());
        assertEquals(h1.maxEdgeSize(), h3.maxEdgeSize());

        List<UUID> sample = nodes.subList(0, 3);

        assertEquals(h1.violations(bits(h1, sample)), h2.violations(bits(h2, sample)));
        assertEquals(h1.violations(bits(h1, sample)), h3.violations(bits(h3, sample)));
    }

    // ---------------------------------------------------------------------
    // Routing of the hook through split
    // ---------------------------------------------------------------------

    /**
     * {@code split} must work with the hypergraph returned by the hook: where the strategy's edges and the owner
     * sets give a different packing, the result follows the strategy's edges.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSplitUsesOverriddenHypergraph() throws Exception {
        List<UUID> nodes = ids(4);

        UUID a = nodes.get(0);
        UUID b = nodes.get(1);
        UUID c = nodes.get(2);
        UUID d = nodes.get(3);

        // The owners allow two pairs, the domain constraint of the strategy pins A alone.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0, 1}, {2, 3}}));

        List<List<UUID>> fromOwners = new DefaultHypergraphStrategy(2).split(ctx);

        assertEquals(Arrays.asList(Arrays.asList(a, c), Arrays.asList(b, d)), fromOwners);

        List<List<UUID>> fromOverride =
            new FixedEdgesStrategy(Collections.singletonList(singleton(a)), 2).split(ctx);

        assertEquals(3, fromOverride.size());
        assertEquals(singleton(a), grpOf(fromOverride, a));
        assertFalse("The strategy's edges must win over the owner sets: " + fromOverride, fromOwners.equals(fromOverride));
        assertTrue("The override is called on a context with materialized owners", ctx.ownersRead());
    }

    /**
     * A strategy that does not override the hook gets exactly what the engine returns for the snapshot owner
     * hypergraph: the regression guard of routing {@code split} through {@code this.hypergraph(ctx)}.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDefaultHypergraphSplitMatchesEngine() throws Exception {
        List<UUID> nodes = ids(5);
        int[][] sets = {{0, 1}, {2, 3}, {1, 4}, {0, 4}, {2, 4}};

        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, sets));

        List<List<UUID>> viaStrategy = new DefaultHypergraphStrategy(2).split(ctx);

        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        List<List<UUID>> direct = SafeToStopSetsSupport.greedy(hg, ctx.nodes(), 2);

        direct = SafeToStopSetsSupport.repair(direct, hg, ctx.nodes(), 2);

        assertEquals(direct, viaStrategy);
    }

    // ---------------------------------------------------------------------
    // Coverage contract
    // ---------------------------------------------------------------------

    /**
     * Edges built as the intersections of the owner sets with a partition of the nodes (the copies of a partition
     * inside one domain) cover every owner set, so the verification proves the answer {@code EXACT}, while the
     * domain invariant itself is checked by the test only: the core does not know the domains.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCoverageContractAccepted() throws Exception {
        List<UUID> nodes = ids(6);
        int[][] sets = {{0, 1, 3, 4}, {1, 2, 4, 5}, {0, 2, 3, 5}};

        Map<UUID, Integer> domains = new LinkedHashMap<>();

        for (int i = 0; i < nodes.size(); i++)
            domains.put(nodes.get(i), i < 3 ? 0 : 1);

        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, sets));

        List<List<UUID>> res = new DomainEdgesStrategy(domains, 3).split(ctx);

        assertEquals(Arrays.asList(
            Arrays.asList(nodes.get(0), nodes.get(3)),
            Arrays.asList(nodes.get(1), nodes.get(4)),
            Arrays.asList(nodes.get(2), nodes.get(5))), res);

        assertDomainProperty(res, nodes, sets, domains);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertTrue(ver.toString(), ver.valid());
        assertFalse(ver.toString(), ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
    }

    /**
     * Edges weaker than the owner sets are not a proof: the answer may take every copy of a partition, so the
     * verification rejects it fail-closed and no group is printed.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testWeakerEdgesRejected() throws Exception {
        List<UUID> nodes = ids(3);

        UUID a = nodes.get(0);
        UUID b = nodes.get(1);

        // The partition of A has a single owner, while the strategy declares only {A, B} as an edge: the owner
        // set of A is not covered, the constraint is weaker than the one of the snapshot.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0}, {1, 2}}));

        List<List<UUID>> res = new FixedEdgesStrategy(Collections.singletonList(Arrays.asList(a, b)), 3).split(ctx);

        assertTrue("The weaker edge must let A join another node: " + res, grpOf(res, a).size() > 1);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertFalse("The answer must not be printed: " + ver, ver.valid());
        assertTrue(ver.toString(), ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
        assertFalse(ver.violations().isEmpty());
    }

    // ---------------------------------------------------------------------
    // Test-only strategies
    // ---------------------------------------------------------------------

    /**
     * Strategy whose safety constraint is a fixed edge list, built with the public factory: the override under
     * test. The local search is disabled, so the result is exactly the greedy packing over these edges.
     */
    private static class FixedEdgesStrategy extends AbstractSafeToStopSetsStrategy {
        /** Edges the strategy declares as its safety constraint. */
        private final List<List<UUID>> edges;

        /** Maximum size of a group. */
        private final int maxGroupSize;

        /**
         * @param edges Edges the strategy declares as its safety constraint.
         * @param maxGroupSize Maximum size of a group.
         */
        FixedEdgesStrategy(List<List<UUID>> edges, int maxGroupSize) {
            this.edges = edges;
            this.maxGroupSize = maxGroupSize;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return "test-fixed-edges";
        }

        /** {@inheritDoc} */
        @Override protected SafeToStopSetsHypergraph hypergraph(SafeToStopSetsContext ctx) {
            return SafeToStopSetsHypergraph.of(ctx.nodes(), edges);
        }

        /** {@inheritDoc} */
        @Override protected List<ClusterNode> orderNodes(SafeToStopSetsContext ctx) {
            return ctx.nodes();
        }

        /** {@inheritDoc} */
        @Override protected int maxGroupSize(SafeToStopSetsContext ctx) {
            return maxGroupSize;
        }

        /** {@inheritDoc} */
        @Override protected boolean repairEnabled() {
            return false;
        }
    }

    /**
     * Strategy that does not override the hook: the safety constraint is the owner hypergraph of the snapshot.
     */
    private static class DefaultHypergraphStrategy extends AbstractSafeToStopSetsStrategy {
        /** Maximum size of a group. */
        private final int maxGroupSize;

        /**
         * @param maxGroupSize Maximum size of a group.
         */
        DefaultHypergraphStrategy(int maxGroupSize) {
            this.maxGroupSize = maxGroupSize;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return "test-default-hypergraph";
        }

        /** {@inheritDoc} */
        @Override protected List<ClusterNode> orderNodes(SafeToStopSetsContext ctx) {
            return ctx.nodes();
        }

        /** {@inheritDoc} */
        @Override protected int maxGroupSize(SafeToStopSetsContext ctx) {
            return maxGroupSize;
        }
    }

    /**
     * Strategy whose constraint is "one copy of every partition stays in every domain": the edges are the
     * intersections {@code H(c, p) ∩ S_d}, derived from the partition owners inside the hook, as the coverage
     * contract expects.
     */
    private static class DomainEdgesStrategy extends AbstractSafeToStopSetsStrategy {
        /** Node ID to domain index mapping, a partition of the snapshot nodes. */
        private final Map<UUID, Integer> domains;

        /** Maximum size of a group. */
        private final int maxGroupSize;

        /**
         * @param domains Node ID to domain index mapping.
         * @param maxGroupSize Maximum size of a group.
         */
        DomainEdgesStrategy(Map<UUID, Integer> domains, int maxGroupSize) {
            this.domains = domains;
            this.maxGroupSize = maxGroupSize;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return "test-domain-edges";
        }

        /** {@inheritDoc} */
        @Override protected SafeToStopSetsHypergraph hypergraph(SafeToStopSetsContext ctx) {
            List<List<UUID>> edges = new ArrayList<>();

            for (PartitionOwners owners : ctx.partitionOwners()) {
                Map<Integer, List<UUID>> parts = new LinkedHashMap<>();

                for (UUID id : owners.owners())
                    parts.computeIfAbsent(domains.get(id), domain -> new ArrayList<>()).add(id);

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
            return maxGroupSize;
        }

        /** {@inheritDoc} */
        @Override protected boolean repairEnabled() {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * @param n Number of nodes.
     * @return Node IDs, in snapshot order.
     */
    private static List<UUID> ids(int n) {
        List<UUID> res = new ArrayList<>(n);

        for (int i = 0; i < n; i++)
            res.add(UUID.randomUUID());

        return res;
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param sets Sets as indices into {@code nodes}; one entry per set.
     * @return The sets as node ID lists.
     */
    private static List<List<UUID>> edgesOf(List<UUID> nodes, int[][] sets) {
        List<List<UUID>> res = new ArrayList<>(sets.length);

        for (int[] set : sets) {
            List<UUID> edge = new ArrayList<>(set.length);

            for (int idx : set)
                edge.add(nodes.get(idx));

            res.add(edge);
        }

        return res;
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param sets Owner sets as indices into {@code nodes}; one partition per entry.
     * @return Partition owners, all for the group {@value #GRP}.
     */
    private static List<PartitionOwners> ownersFor(List<UUID> nodes, int[][] sets) {
        List<PartitionOwners> res = new ArrayList<>(sets.length);

        for (int i = 0; i < sets.length; i++) {
            List<UUID> owners = new ArrayList<>(sets[i].length);

            for (int idx : sets[i])
                owners.add(nodes.get(idx));

            res.add(new PartitionOwners(GRP, i, owners));
        }

        return res;
    }

    /**
     * @param id Node ID.
     * @return A set of one node.
     */
    private static List<UUID> singleton(UUID id) {
        return Collections.singletonList(id);
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param owners Partition owners.
     * @return Fake snapshot with the one {@code PARTITIONED} group.
     */
    private static TestSafeToStopSetsContext ctx(List<UUID> nodes, List<PartitionOwners> owners) {
        Map<String, CacheGroupInfo> groups = new LinkedHashMap<>();
        groups.put(GRP, new CacheGroupInfo(GRP, CacheMode.PARTITIONED, 1, PartitionLossPolicy.READ_WRITE_SAFE, null));

        return new TestSafeToStopSetsContext(nodes, groups, new LinkedHashMap<>(), owners);
    }

    /**
     * @param hg Hypergraph the IDs are indexed by.
     * @param ids Node IDs of the group.
     * @return The group as a bit set over {@link SafeToStopSetsHypergraph#nodes()}.
     */
    private static BitSet bits(SafeToStopSetsHypergraph hg, List<UUID> ids) {
        BitSet res = new BitSet(hg.nodes().size());

        for (UUID id : ids)
            res.set(hg.indexOf(id));

        return res;
    }

    /**
     * @param res Groups of node IDs.
     * @param id Node ID.
     * @return The group that contains the node.
     */
    private static List<UUID> grpOf(List<List<UUID>> res, UUID id) {
        for (List<UUID> grp : res) {
            if (grp.contains(id))
                return grp;
        }

        throw new AssertionError("Node [" + id + "] is not in any group: " + res);
    }

    /**
     * @param list Edges in the input order.
     * @return The same edges in the reversed order.
     */
    private static <T> List<T> reversed(List<T> list) {
        List<T> res = new ArrayList<>(list);

        Collections.reverse(res);

        return res;
    }

    /**
     * @param list Edges in the input order.
     * @return The same edges, the even indices first, then the odd ones.
     */
    private static <T> List<T> reordered(List<T> list) {
        List<T> res = new ArrayList<>(list.size());

        for (int i = 0; i < list.size(); i += 2)
            res.add(list.get(i));

        for (int i = 1; i < list.size(); i += 2)
            res.add(list.get(i));

        return res;
    }

    /**
     * The domain invariant, checked by the test and not by the core: no group may take every copy of a partition
     * inside one domain, otherwise stopping the group loses the last copy of that partition in that domain.
     *
     * @param res Groups of node IDs.
     * @param nodes Node IDs, in snapshot order.
     * @param sets Owner sets as indices into {@code nodes}.
     * @param domains Node ID to domain index mapping.
     */
    private static void assertDomainProperty(
        List<List<UUID>> res,
        List<UUID> nodes,
        int[][] sets,
        Map<UUID, Integer> domains
    ) {
        for (List<UUID> grp : res) {
            for (int[] set : sets) {
                Map<Integer, List<UUID>> parts = new LinkedHashMap<>();

                for (int idx : set)
                    parts.computeIfAbsent(domains.get(nodes.get(idx)), domain -> new ArrayList<>()).add(nodes.get(idx));

                for (Map.Entry<Integer, List<UUID>> part : parts.entrySet()) {
                    assertFalse("Group " + grp + " takes every copy of a partition inside the domain "
                        + part.getKey() + ": " + part.getValue(), grp.containsAll(part.getValue()));
                }
            }
        }
    }
}
