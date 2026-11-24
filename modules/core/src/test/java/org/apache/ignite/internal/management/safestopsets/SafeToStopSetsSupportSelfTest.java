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

/**
 * Tests the common engine ({@link SafeToStopSetsSupport}, {@link SafeToStopSetsHypergraph},
 * {@link SafeToStopSetsVerification}) without a cluster: the hypergraph's deduplication and dominated-edge
 * removal, the greedy and repair passes, and the verification's levels, order and failure semantics.
 */
public class SafeToStopSetsSupportSelfTest {
    /** Name of the group used in the configuration. */
    private static final String GRP = "grp";

    // ---------------------------------------------------------------------
    // Hypergraph
    // ---------------------------------------------------------------------

    /**
     * Identical owner sets collapse into a single edge, and an edge that strictly contains a smaller edge is
     * dropped as dominated.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testHypergraphDedupAndDominance() throws Exception {
        List<UUID> nodes = ids(4);

        // P1 = {A,B}, P2 = {A,B} (duplicate), P3 = {A,B,C} (dominated by {A,B}).
        List<PartitionOwners> owners = ownersFor(nodes, new int[][] {{0, 1}, {0, 1}, {0, 1, 2}});

        TestSafeToStopSetsContext ctx = ctx(nodes, owners);
        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        assertEquals("Duplicate and dominated edges must be removed", 1, hg.edgeCount());
        assertEquals(2, hg.minEdgeSize());
        assertEquals(2, hg.maxEdgeSize());
    }

    /**
     * The same owner sets must give the same edge list regardless of the order they were read in: the edge
     * list is sorted, so the result is independent of the iteration order of any hash structure.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testHypergraphDeterminism() throws Exception {
        List<UUID> nodes = ids(4);
        int[][] sets = {{0, 1}, {2, 3}, {1, 2}, {0, 3}};

        List<PartitionOwners> forward = ownersFor(nodes, sets);
        List<PartitionOwners> backward = ownersFor(nodes, reverse(sets));

        SafeToStopSetsHypergraph h1 = SafeToStopSetsSupport.hypergraph(ctx(nodes, forward));
        SafeToStopSetsHypergraph h2 = SafeToStopSetsSupport.hypergraph(ctx(nodes, backward));

        assertEquals("Edge list must not depend on the owner read order", h1.edges(), h2.edges());
        assertEquals(h1.edgeCount(), h2.edgeCount());
    }

    // ---------------------------------------------------------------------
    // Greedy
    // ---------------------------------------------------------------------

    /**
     * When every pair of nodes is an edge, no two nodes may be stopped together: the greedy pass must return
     * all singletons.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testGreedyAllPairsAllSingletons() throws Exception {
        List<UUID> nodes = ids(4);

        int[][] sets = {{0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3}};

        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, sets));
        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        List<List<UUID>> res = SafeToStopSetsSupport.greedy(hg, ctx.nodes(), 2);

        assertExactPartition(res, nodes);
        assertEquals("Every group must be a singleton", 4, res.size());

        for (List<UUID> grp : res)
            assertEquals(1, grp.size());
    }

    /**
     * A bipartite owner graph (copies always spread across the two halves) admits two groups, one per half:
     * the greedy pass must find them.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testGreedyBipartiteTwoGroups() throws Exception {
        List<UUID> nodes = ids(4);

        // Cross pairs only: {A,C}, {A,D}, {B,C}, {B,D}.
        int[][] sets = {{0, 2}, {0, 3}, {1, 2}, {1, 3}};

        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, sets));
        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        List<List<UUID>> res = SafeToStopSetsSupport.greedy(hg, ctx.nodes(), 2);

        assertExactPartition(res, nodes);
        assertEquals(2, res.size());

        for (List<UUID> grp : res)
            assertEquals(2, grp.size());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
    }

    /**
     * A partition with a single copy left ({@code H(p) = {A}}) forbids A from being stopped together with
     * anyone: the node must be a singleton and the remaining nodes must still be packed together.
     * <p>
     * The regression this test guards: {@code requireds} is collected only from the edges that contain the
     * candidate node, so for {@code H(p) = {A}} the set is empty, {@code conflicts} finds no conflict and
     * every following node joins the group of A. The edge {@code {A}} is then contained in a group of size
     * 2, which the verification rejects with {@code UNPROVABLE}: the engine produces an answer it has to
     * fail.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testGreedySingleOwnerEdgeIsSingleton() throws Exception {
        List<UUID> nodes = ids(4);

        // The only copy of the partition is on A.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0}}));
        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        assertEquals(1, hg.edgeCount());
        assertEquals("A single-owner partition is an edge of size 1", 1, hg.minEdgeSize());

        List<List<UUID>> res = SafeToStopSetsSupport.greedy(hg, ctx.nodes(), 3);

        assertExactPartition(res, nodes);
        assertEquals(2, res.size());
        assertEquals("The node that holds the only copy must stay alone", 1, grpOf(res, nodes.get(0)).size());
        assertEquals("The remaining nodes must still be packed together", 3, grpOf(res, nodes.get(1)).size());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertTrue("greedy must never return a group the verification rejects: " + ver.violations(),
            ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());

        // The local search must not merge the node into a group either: it already checks the full edge set.
        List<List<UUID>> rep = SafeToStopSetsSupport.repair(res, hg, ctx.nodes(), 3);

        assertExactPartition(rep, nodes);
        assertEquals("repair must keep the node alone", 1, grpOf(rep, nodes.get(0)).size());
    }

    /**
     * The single-owner case must not depend on the node order. With the forward order the nodes used to be
     * merged into the group of the node that holds the only copy; with the reverse order they were not, so
     * the answer depended on the order and one of the two answers was rejected by the verification.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testGreedySingleOwnerEdgeIsOrderIndependent() throws Exception {
        List<UUID> nodes = ids(4);

        // {A} — the only copy is on A; {A,B} — two copies, so A also conflicts with B.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0}, {0, 1}}));
        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        List<ClusterNode> order = ctx.nodes();
        List<ClusterNode> reversedOrder = new ArrayList<>(order);

        Collections.reverse(reversedOrder);

        for (List<ClusterNode> ord : Arrays.asList(order, reversedOrder)) {
            List<List<UUID>> res = SafeToStopSetsSupport.greedy(hg, ord, 3);

            assertExactPartition(res, nodes);
            assertEquals("A must stay alone in any order", 1, grpOf(res, nodes.get(0)).size());

            SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

            assertTrue("A group of the answer is unsafe: " + ver.violations(), ver.valid());
            assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
        }
    }

    // ---------------------------------------------------------------------
    // Repair
    // ---------------------------------------------------------------------

    /**
     * The local search merges singletons into existing groups and never creates an unsafe group: after the
     * repair the verification is still exact and valid.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testRepairMergesSingletons() throws Exception {
        List<UUID> nodes = ids(4);

        // One edge {A,B}: A and B may not be stopped together.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0, 1}}));
        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        // Deliberately suboptimal partition: {A,C} plus the singletons {B} and {D}.
        List<List<UUID>> input = Arrays.asList(
            Arrays.asList(nodes.get(0), nodes.get(2)),
            Collections.singletonList(nodes.get(1)),
            Collections.singletonList(nodes.get(3)));

        List<List<UUID>> res = SafeToStopSetsSupport.repair(input, hg, ctx.nodes(), 3);

        assertExactPartition(res, nodes);
        assertEquals("The singleton must be merged into an existing group", 2, res.size());

        for (List<UUID> grp : res)
            assertEquals(2, grp.size());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
    }

    /**
     * A singleton that conflicts with every other node stays a singleton: the repair must not merge it and
     * must not turn a safe partition into an unsafe one.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testRepairKeepsUnmergeableSingleton() throws Exception {
        List<UUID> nodes = ids(3);

        // B conflicts with both A and C.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0, 1}, {1, 2}}));
        SafeToStopSetsHypergraph hg = SafeToStopSetsSupport.hypergraph(ctx);

        List<List<UUID>> input = Arrays.asList(
            Collections.singletonList(nodes.get(0)),
            Collections.singletonList(nodes.get(1)),
            Collections.singletonList(nodes.get(2)));

        List<List<UUID>> res = SafeToStopSetsSupport.repair(input, hg, ctx.nodes(), 3);

        assertExactPartition(res, nodes);

        int singletons = 0;

        for (List<UUID> grp : res)
            if (grp.size() == 1)
                singletons++;

        assertEquals("B cannot be merged without breaking the invariant", 1, singletons);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
    }

    // ---------------------------------------------------------------------
    // Verification: guards
    // ---------------------------------------------------------------------

    /**
     * A non-ready snapshot fails closed: no answer, regardless of the group list.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyNotReady() throws Exception {
        List<UUID> nodes = ids(2);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());
        ctx.ownersAvailable(false);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1))), ctx);

        assertFalse(ver.valid());
        assertTrue(ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.NOT_READY, ver.mode());
    }

    /**
     * A topology change between the snapshot and the verification voids the answer: the result is
     * {@code UNSTABLE} and never {@code valid}.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyUnstableIsNotValid() throws Exception {
        List<UUID> nodes = ids(2);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());
        ctx.topologyStable(false);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1))), ctx);

        assertFalse("An unstable topology must never produce a valid answer", ver.valid());
        assertTrue(ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.UNSTABLE, ver.mode());
    }

    // ---------------------------------------------------------------------
    // Verification: structural level
    // ---------------------------------------------------------------------

    /**
     * A group list that does not cover every snapshot node is structurally broken and is rejected.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyIncompleteCoverage() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1))), ctx);

        assertFalse(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
        assertFalse(ver.violations().isEmpty());
    }

    /**
     * A node that belongs to two groups is structurally broken and is rejected.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyDuplicateNode() throws Exception {
        List<UUID> nodes = ids(3);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(
                Arrays.asList(nodes.get(0), nodes.get(1)),
                Arrays.asList(nodes.get(0), nodes.get(2))), ctx);

        assertFalse(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
    }

    /**
     * An empty group is structurally broken and is rejected.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyEmptyGroup() throws Exception {
        List<UUID> nodes = ids(2);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1)), Collections.emptyList()), ctx);

        assertFalse(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
    }

    // ---------------------------------------------------------------------
    // Verification: semantic level
    // ---------------------------------------------------------------------

    /**
     * A group that fully contains the owners of a partition is unsafe and is rejected. The owner sets of
     * the snapshot are already restricted to the snapshot nodes, so the check operates on the remaining
     * copies only.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyMonochromaticEdge() throws Exception {
        List<UUID> nodes = ids(4);

        // Partition 0 is owned by {A,B}: the group {A,B} would stop both copies at once.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0, 1}}));

        ctx.partitionOwners();

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1)),
                Arrays.asList(nodes.get(2), nodes.get(3))), ctx);

        assertFalse(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
        assertFalse(ver.violations().isEmpty());
    }

    /**
     * A singleton that fully contains the owners of a partition is not a semantic violation: singletons
     * are not stop candidates, they are printed as "cannot be stopped together with any other node". The
     * all-singletons result is rejected by the task as "no safe group" instead.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifySingletonOwnerIsNotAViolation() throws Exception {
        List<UUID> nodes = ids(2);

        // Partition 0 is owned by {A} only: the singleton {A} holds its only copy.
        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0}}));

        ctx.partitionOwners();

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Collections.singletonList(nodes.get(0)),
                Collections.singletonList(nodes.get(1))), ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
        assertTrue(ver.violations().isEmpty());
    }

    /**
     * A group that does not fully contain any owner set is safe: the verification is exact and valid.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifySafeGroups() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = ctx(nodes, ownersFor(nodes, new int[][] {{0, 1}}));

        ctx.partitionOwners();

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(2)),
                Arrays.asList(nodes.get(1), nodes.get(3))), ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
        assertTrue(ver.violations().isEmpty());
    }

    // ---------------------------------------------------------------------
    // Verification: structure-only (D24)
    // ---------------------------------------------------------------------

    /**
     * When the owners were not read and the configuration allows it (no blockers, ownership matches the
     * affinity), the answer is marked {@code STRUCTURE_ONLY} with a reason, never printed silently.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyStructureOnly() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1)),
                Arrays.asList(nodes.get(2), nodes.get(3))), ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.STRUCTURE_ONLY, ver.mode());
        assertFalse(ver.failed());
        assertFalse("The structure-only reason must be present", ver.notVerified().isEmpty());
    }

    /**
     * When the owners were not read and the ownership does not match the affinity, the answer is rejected
     * with {@code UNPROVABLE}: a structure-only derivation is not sound in that state.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyStructureOnlyRequiresOwnersWhenOwnershipMismatch() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());
        ctx.ownershipMatchesAffinity(false);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1)),
                Arrays.asList(nodes.get(2), nodes.get(3))), ctx);

        assertFalse(ver.valid());
        assertTrue(ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
    }

    // ---------------------------------------------------------------------
    // Verification: UNPROVABLE via a faulty test strategy
    // ---------------------------------------------------------------------

    /**
     * A strategy that produces a group list without reading the owners must be rejected when the
     * configuration has a structure-only blocker.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testUnprovableWithBlocker() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());
        ctx.blocker(GRP, "copy filter without excludeNeighbors");

        TestOwnersSafeToStopSetsStrategy strategy = new TestOwnersSafeToStopSetsStrategy();
        List<List<UUID>> res = strategy.split(ctx);

        assertFalse("The faulty strategy must not read the owners", ctx.ownersRead());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertFalse(ver.valid());
        assertTrue(ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
    }

    /**
     * The same faulty strategy is also rejected when the ownership does not match the affinity, even with
     * no configuration blockers.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testUnprovableWithOwnershipMismatch() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList());
        ctx.ownershipMatchesAffinity(false);

        TestOwnersSafeToStopSetsStrategy strategy = new TestOwnersSafeToStopSetsStrategy();
        List<List<UUID>> res = strategy.split(ctx);

        assertFalse("The faulty strategy must not read the owners", ctx.ownersRead());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertFalse(ver.valid());
        assertTrue(ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
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
     * @param sets Owner set indices, in reverse order.
     */
    private static int[][] reverse(int[][] sets) {
        int[][] res = new int[sets.length][];

        for (int i = 0; i < sets.length; i++)
            res[i] = sets[sets.length - 1 - i];

        return res;
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param owners Partition owners.
     * @return Fake snapshot with the one {@code PARTITIONED} group.
     */
    private static TestSafeToStopSetsContext ctx(List<UUID> nodes, List<PartitionOwners> owners) {
        Map<String, CacheGroupInfo> groups = new LinkedHashMap<>();
        groups.put(GRP, new CacheGroupInfo(GRP, CacheMode.PARTITIONED, 1, PartitionLossPolicy.READ_WRITE_SAFE));

        return new TestSafeToStopSetsContext(nodes, groups, new LinkedHashMap<>(), owners);
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
     * @param res Groups of node IDs.
     * @param all Expected node IDs.
     */
    private static void assertExactPartition(List<List<UUID>> res, List<UUID> all) {
        List<UUID> flat = new ArrayList<>();

        for (List<UUID> grp : res) {
            assertFalse("Empty groups are not allowed", grp.isEmpty());
            flat.addAll(grp);
        }

        assertEquals(all.size(), flat.size());

        for (UUID id : all)
            assertEquals(1, flat.stream().filter(id::equals).count());
    }
}
