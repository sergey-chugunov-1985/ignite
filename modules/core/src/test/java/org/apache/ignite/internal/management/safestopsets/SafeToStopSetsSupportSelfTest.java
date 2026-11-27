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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Tests the common engine ({@link SafeToStopSetsSupport}, {@link SafeToStopSetsHypergraph},
 * {@link SafeToStopSetsVerification}) without a cluster: the exclusions declared by a strategy, the hypergraph's
 * deduplication and dominated-edge removal, the greedy and repair passes, and the verification's levels, order
 * and failure semantics.
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
    // Verification: split integrity level
    // ---------------------------------------------------------------------

    /**
     * A group list that does not cover every snapshot node breaks the split integrity and is rejected.
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
     * A node that belongs to two groups breaks the split integrity and is rejected.
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
     * An empty group breaks the split integrity and is rejected.
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
    // Verification: data safety level
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
     * A singleton that fully contains the owners of a partition is not a data safety violation: singletons
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
    // Verification: configuration-only (D24)
    // ---------------------------------------------------------------------

    /**
     * When the owners were not read, the configuration allows a configuration-only answer, and every group fits
     * into the configuration's size bound, the answer is marked {@code CONFIG_ONLY} with a reason.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyConfigOnly() throws Exception {
        List<UUID> nodes = ids(4);

        // backups = 3: copies = 4, maxSafeGroupSize = 3; groups of 2 are within the bound.
        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList(), 3);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1)),
                Arrays.asList(nodes.get(2), nodes.get(3))), ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.CONFIG_ONLY, ver.mode());
        assertFalse(ver.failed());
        assertFalse("The configuration-only reason must be present", ver.notVerified().isEmpty());
    }

    /**
     * A group that exceeds the size bound the configuration guarantees cannot be proved safe without reading
     * the partition owners: the verification must refuse with {@code UNPROVABLE} instead of returning a
     * valid configuration-only answer. This is the regression for the C3 defect (a trivial strategy returning
     * the whole cluster as one group must not be accepted).
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyConfigOnlyOversizedGroupIsUnprovable() throws Exception {
        List<UUID> nodes = ids(4);

        // backups = 1: copies = 2, maxSafeGroupSize = 1; a group of 4 nodes exceeds the bound.
        TestSafeToStopSetsContext ctx = ctx(nodes, Collections.emptyList(), 1);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Collections.singletonList(new ArrayList<>(nodes)), ctx);

        assertFalse("A group that exceeds the configuration bound must be refused", ver.valid());
        assertTrue(ver.failed());
        assertEquals(SafeToStopSetsVerification.Mode.UNPROVABLE, ver.mode());
        assertFalse("The refusal must carry a readable reason", ver.violations().isEmpty());
        assertTrue(ver.violations().toString(),
            ver.violations().get(0).contains("Group #0"));
        assertTrue(ver.violations().toString(),
            ver.violations().get(0).contains("4"));
    }

    /**
     * For a {@code REPLICATED} group the copy set is the affinity node set; {@code backups} is
     * {@link Integer#MAX_VALUE} there and the bound must be computed without overflow. A group within the
     * bound is a valid configuration-only answer.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyConfigOnlyReplicatedWithinBound() throws Exception {
        List<UUID> nodes = ids(6);

        Map<String, CacheGroupInfo> grps = new LinkedHashMap<>();
        grps.put(GRP, new CacheGroupInfo(GRP, CacheMode.REPLICATED, Integer.MAX_VALUE,
            PartitionLossPolicy.READ_WRITE_SAFE, null));

        TestSafeToStopSetsContext ctx = new TestSafeToStopSetsContext(nodes, grps,
            Collections.singletonMap(GRP, 4), Collections.emptyList());

        // copies = |affinityNodes| = 4, maxSafeGroupSize = 3; groups of 2 are within the bound.
        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Arrays.asList(Arrays.asList(nodes.get(0), nodes.get(1)),
                Arrays.asList(nodes.get(2), nodes.get(3)),
                Arrays.asList(nodes.get(4), nodes.get(5))), ctx);

        assertTrue("A group within the REPLICATED bound must be accepted", ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.CONFIG_ONLY, ver.mode());
    }

    /**
     * A snapshot with no cache groups has no copy to lose: the bound is "no limit" and any group list is a
     * valid configuration-only answer, provided the structure is correct.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyConfigOnlyNoCacheGroups() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = new TestSafeToStopSetsContext(
            nodes, new LinkedHashMap<>(), new LinkedHashMap<>(), Collections.emptyList());

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(
            Collections.singletonList(new ArrayList<>(nodes)), ctx);

        assertTrue("With no cache groups the whole cluster may be stopped at once", ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.CONFIG_ONLY, ver.mode());
    }

    /**
     * When the owners were not read and the ownership does not match the affinity, the answer is rejected
     * with {@code UNPROVABLE}: a configuration-only derivation is not sound in that state.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testVerifyConfigOnlyRequiresOwnersWhenOwnershipMismatch() throws Exception {
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
     * configuration has a configuration-only blocker.
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
    // Strategy options
    // ---------------------------------------------------------------------

    /**
     * No {@code --strategy-options} is not an error: the run has an empty option map, which is what the
     * strategies see by default.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParseStrategyOptionsAbsent() throws Exception {
        assertTrue(SafeToStopSetsSupport.parseStrategyOptions(null).isEmpty());
        assertTrue(SafeToStopSetsSupport.parseStrategyOptions(new String[0]).isEmpty());
    }

    /**
     * A token is a name (the option is present with an empty value) or a {@code name=value} pair; the first
     * {@code '='} divides them, so a value may carry {@code '='}, and both parts are trimmed.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParseStrategyOptionsForms() throws Exception {
        assertEquals(options("a", ""), SafeToStopSetsSupport.parseStrategyOptions(new String[] {"a"}));
        assertEquals("'name=' is the same as presence", options("a", ""),
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {"a="}));
        assertEquals("Only the first '=' divides", options("a", "x=y"),
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {"a=x=y"}));
        assertEquals("The name and the value are trimmed", options("a", "1"),
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {" a = 1 "}));
        assertEquals("The case of the name is kept", options("A", "1"),
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {"A=1"}));

        Map<String, String> both = new LinkedHashMap<>();

        both.put("a", "1");
        both.put("b", "2");

        assertEquals(both, SafeToStopSetsSupport.parseStrategyOptions(new String[] {"b=2", "a=1"}));
    }

    /**
     * The result is ordered by name and immutable: the strategy cannot change the options of the run through
     * the map it receives.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParseStrategyOptionsOrderedAndImmutable() throws Exception {
        Map<String, String> res = SafeToStopSetsSupport.parseStrategyOptions(new String[] {"c", "a=1", "b=2"});

        assertEquals("The options are ordered by name", Arrays.asList("a", "b", "c"), new ArrayList<>(res.keySet()));

        try {
            res.put("d", "");

            fail("The options of the run must be immutable");
        }
        catch (UnsupportedOperationException ignored) {
            // Expected.
        }
    }

    /**
     * An empty token comes from a stray comma: the message names the whole raw list, so the operator sees
     * where the comma is wrong.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParseStrategyOptionsRejectsEmptyToken() throws Exception {
        try {
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {"a", "", "b"});

            fail("An empty option must be an operator error");
        }
        catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains("Empty strategy option"));
            assertTrue(msg, msg.contains("'--strategy-options a,,b'"));
        }
    }

    /**
     * A token without a name is a typo of the operator: the message shows the option form.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParseStrategyOptionsRejectsMissingName() throws Exception {
        try {
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {"=1"});

            fail("An option without a name must be an operator error");
        }
        catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains("has no name"));
            assertTrue(msg, msg.contains("'name' or 'name=value'"));
        }
    }

    /**
     * A name outside {@code [A-Za-z0-9._-]} cannot be declared by a strategy, so it is rejected with the name
     * alone: the value is not what has to be fixed.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParseStrategyOptionsRejectsMalformedName() throws Exception {
        try {
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {"bad name=1"});

            fail("A malformed option name must be an operator error");
        }
        catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains("Invalid strategy option name [name='bad name']"));
            assertTrue(msg, msg.contains("only letters, digits, '.', '_' and '-' are allowed"));
        }
    }

    /**
     * The same name twice is ambiguous: the operator must not guess which value the strategy received.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testParseStrategyOptionsRejectsDuplicate() throws Exception {
        try {
            SafeToStopSetsSupport.parseStrategyOptions(new String[] {"a=1", "a=2"});

            fail("A repeated option must be an operator error");
        }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Duplicate strategy option [name='a']"));
        }
    }

    /**
     * The declared options of the run pass the check, values included: the core never interprets them.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCheckOptionsAcceptsSupported() throws Exception {
        SafeToStopSetsSupport.checkOptions(new OptionsStrategy("bar", "baz"), Collections.emptyMap());

        Map<String, String> options = new LinkedHashMap<>();

        options.put("bar", "");
        options.put("baz", "any value");

        SafeToStopSetsSupport.checkOptions(new OptionsStrategy("bar", "baz"), options);
    }

    /**
     * An undeclared option is an operator error reported before the cluster is read: the message lists the
     * given and the supported names, both ordered by name.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCheckOptionsRejectsUnsupported() throws Exception {
        Map<String, String> options = new LinkedHashMap<>();

        options.put("foo", "");
        options.put("bar", "1");

        try {
            SafeToStopSetsSupport.checkOptions(new OptionsStrategy("baz", "bar"), options);

            fail("An undeclared option must be an operator error");
        }
        catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains("Option(s) [foo] are not supported by strategy \"with-options\""));
            assertTrue(msg, msg.contains("supported: [bar, baz]"));
        }
    }

    /**
     * A strategy that declares no option rejects any option of the run: the message shows the empty set
     * instead of omitting the supported list.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCheckOptionsOfStrategyWithoutOptions() throws Exception {
        try {
            SafeToStopSetsSupport.checkOptions(new TestOwnersSafeToStopSetsStrategy(), options("bar", ""));

            fail("A strategy without options must reject the given options");
        }
        catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains("supported: []"));
            assertTrue(msg, msg.contains("test-owners"));
        }
    }

    // ---------------------------------------------------------------------
    // Strategy exclusions
    // ---------------------------------------------------------------------

    /**
     * The declaration is re-projected onto the given groups: the result is ordered by group name, whatever the
     * order and the map type the strategy chose, and neither the declaration nor the input groups are modified.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testApplyExclusionsProjectsOntoGroups() throws Exception {
        Map<String, CacheGroupInfo> grps = groups("a", "b", "c");

        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("c", "no cell");
        declared.put("a", "no cell");

        ExcludingStrategy strategy = new ExcludingStrategy(declared);

        Map<String, String> res = SafeToStopSetsSupport.applyExclusions(strategy, options("skip", ""), grps);

        assertEquals("The result is ordered by group name", Arrays.asList("a", "c"), new ArrayList<>(res.keySet()));
        assertEquals("no cell", res.get("a"));

        assertEquals("The strategy must see the options of the run", options("skip", ""), strategy.seenOptions);
        assertEquals("The strategy must see the groups as they are", grps, strategy.seenGroups);
        assertEquals("The declaration of the strategy must not be modified", declared, strategy.declared);
        assertEquals("The input groups must not be modified", Arrays.asList("a", "b", "c"), new ArrayList<>(grps.keySet()));
    }

    /**
     * A strategy that declares nothing keeps every group: the default implementation of the SPI is silent.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testApplyExclusionsDefaultStrategyExcludesNothing() throws Exception {
        Map<String, String> res = SafeToStopSetsSupport.applyExclusions(
            new TestOwnersSafeToStopSetsStrategy(), Collections.emptyMap(), groups(GRP));

        assertTrue("A strategy without exclusions must not narrow the snapshot", res.isEmpty());

        Map<String, String> empty = SafeToStopSetsSupport.applyExclusions(
            new ExcludingStrategy(Collections.emptyMap()), Collections.emptyMap(), groups(GRP));

        assertTrue("An empty declaration must not narrow the snapshot", empty.isEmpty());
    }

    /**
     * A group outside the snapshot scope means that the strategy and the core disagree about the scope, which is
     * a bug of the implementation: an internal error, not a usage error of the operator.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testApplyExclusionsRejectsUnknownGroup() throws Exception {
        try {
            SafeToStopSetsSupport.applyExclusions(
                new ExcludingStrategy(Collections.singletonMap("zzz", "no cell")), Collections.emptyMap(), groups("a"));

            fail("A group outside the snapshot scope must be an internal error");
        }
        catch (IllegalStateException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains("excluding"));
            assertTrue(msg, msg.contains("zzz"));
        }
    }

    /**
     * An exclusion without a reason would hide itself from the report, so it is rejected instead of printed as
     * an empty line.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testApplyExclusionsRejectsMissingReason() throws Exception {
        try {
            SafeToStopSetsSupport.applyExclusions(
                new ExcludingStrategy(Collections.singletonMap("a", "  ")), Collections.emptyMap(), groups("a"));

            fail("An exclusion without a reason must be an internal error");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("without a reason"));
        }
    }

    /**
     * A {@code null} declaration is a broken implementation, not "nothing to exclude": the difference between
     * the two must not be guessed.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testApplyExclusionsRejectsNullDeclaration() throws Exception {
        try {
            SafeToStopSetsSupport.applyExclusions(
                new ExcludingStrategy(null), Collections.emptyMap(), groups("a"));

            fail("A null declaration must be an internal error");
        }
        catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("returned null"));
        }
    }

    /**
     * The applied exclusions are stored in the snapshot, so they must be immutable.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testApplyExclusionsResultIsImmutable() throws Exception {
        Map<String, String> res = SafeToStopSetsSupport.applyExclusions(
            new ExcludingStrategy(Collections.singletonMap("a", "no cell")), Collections.emptyMap(), groups("a"));

        try {
            res.put("b", "added");

            fail("The exclusions of the snapshot must be immutable");
        }
        catch (UnsupportedOperationException ignored) {
            // Expected.
        }
    }

    /** Strategy that declares a fixed set of options and never splits: the options are checked before it. */
    private static class OptionsStrategy implements SafeToStopSetsStrategy {
        /** Declared option names. */
        private final Set<String> supported;

        /**
         * @param supported Declared option names.
         */
        OptionsStrategy(String... supported) {
            this.supported = new LinkedHashSet<>(Arrays.asList(supported));
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return "with-options";
        }

        /** {@inheritDoc} */
        @Override public Set<String> supportedOptions() {
            return supported;
        }

        /** {@inheritDoc} */
        @Override public List<List<UUID>> split(SafeToStopSetsContext ctx) {
            throw new AssertionError("The options must be checked before the split");
        }
    }

    /** Strategy that declares the given exclusions and records the arguments of the filter. */
    private static class ExcludingStrategy implements SafeToStopSetsStrategy {
        /** Exclusion to declare, {@code null} to simulate a broken implementation. */
        private final Map<String, String> declared;

        /** Options seen by the filter. */
        private Map<String, String> seenOptions;

        /** Groups seen by the filter. */
        private Map<String, CacheGroupInfo> seenGroups;

        /**
         * @param declared Exclusion to declare, {@code null} to simulate a broken implementation.
         */
        ExcludingStrategy(Map<String, String> declared) {
            this.declared = declared;
        }

        /** {@inheritDoc} */
        @Override public String name() {
            return "excluding";
        }

        /** {@inheritDoc} */
        @Override public List<List<UUID>> split(SafeToStopSetsContext ctx) {
            throw new AssertionError("The exclusions must be applied before the split");
        }

        /** {@inheritDoc} */
        @Override public Map<String, String> excludeGroups(
            Map<String, String> options,
            Map<String, CacheGroupInfo> groups
        ) {
            seenOptions = options;
            seenGroups = groups;

            return declared;
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * @param names Group names, in the order of the map.
     * @return Configurations of {@code PARTITIONED} groups with one backup.
     */
    private static Map<String, CacheGroupInfo> groups(String... names) {
        Map<String, CacheGroupInfo> res = new LinkedHashMap<>();

        for (String name : names) {
            res.put(name, new CacheGroupInfo(name, CacheMode.PARTITIONED, 1,
                PartitionLossPolicy.READ_WRITE_SAFE, null));
        }

        return res;
    }

    /**
     * @param key Option key.
     * @param val Option value.
     * @return One-element options map.
     */
    private static Map<String, String> options(String key, String val) {
        return Collections.singletonMap(key, val);
    }

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
        return ctx(nodes, owners, 1);
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param owners Partition owners.
     * @param backups Number of backups of the group.
     * @return Fake snapshot with the one {@code PARTITIONED} group.
     */
    private static TestSafeToStopSetsContext ctx(List<UUID> nodes, List<PartitionOwners> owners, int backups) {
        Map<String, CacheGroupInfo> grps = new LinkedHashMap<>();
        grps.put(GRP, new CacheGroupInfo(GRP, CacheMode.PARTITIONED, backups, PartitionLossPolicy.READ_WRITE_SAFE, null));

        return new TestSafeToStopSetsContext(nodes, grps, new LinkedHashMap<>(), owners);
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
