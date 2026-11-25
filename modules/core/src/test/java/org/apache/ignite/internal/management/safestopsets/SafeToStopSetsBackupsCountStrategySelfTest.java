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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the default {@code backups-count} strategy against the snapshot contract, without a cluster: the
 * answer is derived from the configuration, the group bound is {@code min(backups + 1, |affinityNodes|) - 1}
 * over all groups, and the partition owners are read only when the ownership is not yet equal to the
 * affinity assignment.
 */
public class SafeToStopSetsBackupsCountStrategySelfTest {
    /** Name of the group used in the configuration. */
    private static final String GRP = "grp";

    /** Name of the second group used in the configuration. */
    private static final String GRP2 = "grp2";

    /**
     * The strategy name is the value of the {@code --strategy} command line option.
     */
    @Test
    public void testName() {
        assertEquals("backups-count", new BackupsCountSafeToStopSetsStrategy().name());
    }

    /**
     * With one backup a partition has two copies, so no two nodes may be stopped together: the answer is all
     * singletons and the partition owners are not read.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testOneBackupIsSingletons() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 1), aff(GRP, 6), Collections.emptyList());

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        // copies = 2, maxGroupSize = 1.
        assertEquals(nodes, singletons(res));
        assertFalse("The partition owners must not be read on the configuration path", ctx.ownersRead());
    }

    /**
     * With several groups the bound is the smallest one, and the round-robin deal is deterministic: the
     * group sizes differ by at most one and every node appears exactly once.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testMinBoundOverGroups() throws Exception {
        List<UUID> nodes = ids(7);

        Map<String, CacheGroupInfo> groups = new LinkedHashMap<>();
        groups.put(GRP, grp(GRP, 2));
        groups.put(GRP2, repl(GRP2));

        // The partitioned group bounds the answer: backups = 2, |affinityNodes| = 7, copies = 3.
        // The replicated group has 4 affinity nodes, copies = 4, and does not change the bound.
        TestSafeToStopSetsContext ctx = ctx(nodes, groups, aff(GRP, 7, GRP2, 4), Collections.emptyList());

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        // maxGroupSize = 3 - 1 = 2: 7 nodes over 4 groups, sizes 2, 2, 2, 1.
        assertEquals(4, res.size());
        assertEquals(Arrays.asList(nodes.get(0), nodes.get(4)), res.get(0));
        assertEquals(Arrays.asList(nodes.get(1), nodes.get(5)), res.get(1));
        assertEquals(Arrays.asList(nodes.get(2), nodes.get(6)), res.get(2));
        assertEquals(Arrays.asList(nodes.get(3)), res.get(3));

        assertExactPartition(res, nodes);
        assertFalse("The partition owners must not be read on the configuration path", ctx.ownersRead());
    }

    /**
     * Without backups a partition has a single copy, so no two nodes may be stopped together: the answer is
     * all singletons and the partition owners are still not read.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testZeroBackupsAreSingletons() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 0), aff(GRP, 6), Collections.emptyList());

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        assertEquals(nodes, singletons(res));
        assertFalse(ctx.ownersRead());
    }

    /**
     * For a replicated group the copy set is the affinity node set, not the server nodes: a persistent
     * cluster with the baseline smaller than the node set is bounded by the baseline.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testReplicatedGroupIsBoundedByAffinityNodes() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, repl(GRP), aff(GRP, 3), Collections.emptyList());

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        // copies = |affinityNodes| = 3, maxGroupSize = 2: three groups of two nodes.
        assertEquals(3, res.size());
        assertEquals(Arrays.asList(nodes.get(0), nodes.get(3)), res.get(0));
        assertEquals(Arrays.asList(nodes.get(1), nodes.get(4)), res.get(1));
        assertEquals(Arrays.asList(nodes.get(2), nodes.get(5)), res.get(2));

        assertExactPartition(res, nodes);
        assertFalse(ctx.ownersRead());
    }

    /**
     * A single node is always a valid answer: the one singleton, nothing else.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSingleNode() throws Exception {
        List<UUID> nodes = ids(1);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 1), aff(GRP, 1), Collections.emptyList());

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        assertEquals(1, res.size());
        assertEquals(nodes, singletons(res));
    }

    /**
     * A replicated group stores {@code backups == Integer.MAX_VALUE}; the sum {@code backups + 1} must not
     * be computed, otherwise it overflows into a negative group bound.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testReplicatedBackupsDoNotOverflow() throws Exception {
        List<UUID> nodes = ids(8);

        TestSafeToStopSetsContext ctx = ctx(nodes, repl(GRP), aff(GRP, 4), Collections.emptyList());

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        // copies = 4, maxGroupSize = 3: three groups, sizes 3, 3, 2.
        assertEquals(3, res.size());
        assertEquals(Arrays.asList(nodes.get(0), nodes.get(3), nodes.get(6)), res.get(0));
        assertEquals(Arrays.asList(nodes.get(1), nodes.get(4), nodes.get(7)), res.get(1));
        assertEquals(Arrays.asList(nodes.get(2), nodes.get(5)), res.get(2));

        assertExactPartition(res, nodes);
    }

    /**
     * The configuration-level conditions are materialized by the snapshot: a non-empty blocker map is a
     * refusal with the group names and the reasons, and the partition owners are not read.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testStructureOnlyBlockersRefuse() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 2), aff(GRP, 6), Collections.emptyList());

        ctx.blocker(GRP, "affinity is org.example.CustomAffinity, not RendezvousAffinityFunction");
        ctx.blocker(GRP2, "copy filters are set without excludeNeighbors");

        try {
            new BackupsCountSafeToStopSetsStrategy().split(ctx);

            fail("A blocked configuration must be refused");
        }
        catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains(GRP));
            assertTrue(msg, msg.contains("not RendezvousAffinityFunction"));
            assertTrue(msg, msg.contains(GRP2));
            assertTrue(msg, msg.contains("copy filters are set without excludeNeighbors"));
            assertTrue(msg, msg.contains("--strategy"));
        }

        assertFalse("A refusal must not read the partition owners", ctx.ownersRead());
    }

    /**
     * A cluster without a single user cache group has no copy bound at all, so every node may be stopped
     * together. The "no limit" sentinel must not be used to compute the number of groups: the ceiling of
     * {@code nodes / sentinel} overflows into a negative capacity.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNoCacheGroupsIsSingleGroup() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = new TestSafeToStopSetsContext(
            nodes, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        assertEquals(1, res.size());
        assertEquals(nodes, res.get(0));
        assertFalse("There is no layout to read without cache groups", ctx.ownersRead());
    }

    /**
     * The state guard is not a refusal: when the ownership is not yet equal to the affinity assignment the
     * strategy reads the partition owners and computes from the actual layout. The engine itself is wired by
     * the next stage, so the read is observed here and the layout computation is not.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testOwnershipMismatchReadsOwners() throws Exception {
        List<UUID> nodes = ids(6);

        // A node was drained, the rebalance is not finished: three partitions are down to the two copies
        // {A,B}, the rest still have the regular three copies {A,B,C}.
        List<PartitionOwners> owners = owners(nodes,
            new int[][] {{0, 1}, {0, 1}, {0, 1}, {0, 1, 2}, {0, 1, 2}, {0, 1, 2}});

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 2), aff(GRP, 6), owners);

        ctx.ownershipMatchesAffinity(false);

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        assertTrue("The state guard must read the partition owners", ctx.ownersRead());
        assertExactPartition(res, nodes);

        // No group may contain the pair {A,B}: those two nodes hold both remaining copies of three
        // partitions.
        for (List<UUID> grp : res)
            assertFalse("The group " + grp + " holds both copies of the drained partitions",
                grp.contains(nodes.get(0)) && grp.contains(nodes.get(1)));

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
    }

    /**
     * A state mismatch that comes from the loss policy or the rebalance mode (modeled by the
     * {@code ownershipMatchesAffinity} seam) funnels into the same engine path: the owners are read and the
     * answer is verified exact.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testLossPolicyIgnoreReadsOwners() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 2), aff(GRP, 6),
            owners(nodes, new int[][] {{0, 1, 2}, {3, 4, 5}, {1, 3, 4}, {2, 4, 5}}));

        // lossPolicy = IGNORE or rebalanceMode = NONE makes the snapshot report a mismatch.
        ctx.ownershipMatchesAffinity(false);

        List<List<UUID>> res = new BackupsCountSafeToStopSetsStrategy().split(ctx);

        assertTrue("A loss-policy mismatch must read the partition owners", ctx.ownersRead());
        assertExactPartition(res, nodes);

        SafeToStopSetsVerification ver = SafeToStopSetsSupport.verify(res, ctx);

        assertTrue(ver.valid());
        assertEquals(SafeToStopSetsVerification.Mode.EXACT, ver.mode());
    }

    /**
     * The state guard cannot read the layout of a non-ready snapshot: the read fails and the strategy does
     * not hide it behind its own failure.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testOwnershipMismatchOnNonReadySnapshot() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 2), aff(GRP, 6), Collections.emptyList());

        ctx.ownershipMatchesAffinity(false);
        ctx.ownersAvailable(false);

        try {
            new BackupsCountSafeToStopSetsStrategy().split(ctx);

            fail("The layout must not be computed from a non-ready snapshot");
        }
        catch (IgniteException expected) {
            // Expected: the read itself fails closed.
        }
    }

    /**
     * A healthy configuration with the ownership matching the affinity is answered from the configuration
     * only: the same snapshot must always give the same answer and never read the partition owners.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDeterministicConfigurationAnswer() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 2), aff(GRP, 6), Collections.emptyList());

        BackupsCountSafeToStopSetsStrategy strategy = new BackupsCountSafeToStopSetsStrategy();

        assertEquals(strategy.split(ctx), strategy.split(ctx));
        assertFalse(ctx.ownersRead());
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
     * @param name Group name.
     * @param backups Number of backups.
     * @return Partitioned group configuration.
     */
    private static CacheGroupInfo grp(String name, int backups) {
        return new CacheGroupInfo(name, CacheMode.PARTITIONED, backups, PartitionLossPolicy.READ_WRITE_SAFE);
    }

    /**
     * @param name Group name.
     * @return Replicated group configuration, with the {@code Integer.MAX_VALUE} backups of the real
     *      configuration.
     */
    private static CacheGroupInfo repl(String name) {
        return new CacheGroupInfo(name, CacheMode.REPLICATED, Integer.MAX_VALUE, PartitionLossPolicy.READ_WRITE_SAFE);
    }

    /**
     * @param name Group name.
     * @param affinityNodes Size of the affinity node set.
     * @return Affinity node set size of the one group.
     */
    private static Map<String, Integer> aff(String name, int affinityNodes) {
        Map<String, Integer> res = new LinkedHashMap<>();
        res.put(name, affinityNodes);

        return res;
    }

    /**
     * @param first First group name.
     * @param firstAffinityNodes Size of the affinity node set of the first group.
     * @param second Second group name.
     * @param secondAffinityNodes Size of the affinity node set of the second group.
     * @return Affinity node set sizes of the two groups.
     */
    private static Map<String, Integer> aff(String first, int firstAffinityNodes, String second, int secondAffinityNodes) {
        Map<String, Integer> res = aff(first, firstAffinityNodes);
        res.put(second, secondAffinityNodes);

        return res;
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param groups Group configurations.
     * @param affinityNodes Affinity node set sizes.
     * @param owners Partition owners.
     * @return Fake snapshot.
     */
    private static TestSafeToStopSetsContext ctx(
        List<UUID> nodes,
        Map<String, CacheGroupInfo> groups,
        Map<String, Integer> affinityNodes,
        List<PartitionOwners> owners) {
        return new TestSafeToStopSetsContext(nodes, new LinkedHashMap<>(groups), affinityNodes, owners);
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param grp Single group configuration.
     * @param affinityNodes Affinity node set sizes.
     * @param owners Partition owners.
     * @return Fake snapshot with the one group.
     */
    private static TestSafeToStopSetsContext ctx(
        List<UUID> nodes,
        CacheGroupInfo grp,
        Map<String, Integer> affinityNodes,
        List<PartitionOwners> owners) {
        Map<String, CacheGroupInfo> groups = new LinkedHashMap<>();
        groups.put(grp.name(), grp);

        return new TestSafeToStopSetsContext(nodes, groups, affinityNodes, owners);
    }

    /**
     * @param res Groups of node IDs.
     * @return Node IDs of the singleton groups, in group order; a group of another size is a failure.
     */
    private static List<UUID> singletons(List<List<UUID>> res) {
        List<UUID> flat = new ArrayList<>();

        for (List<UUID> grp : res) {
            assertEquals(1, grp.size());
            flat.add(grp.get(0));
        }

        return flat;
    }

    /**
     * @param nodes Node IDs, in snapshot order.
     * @param sets Owner sets as indices into {@code nodes}; one partition per entry.
     * @return Partition owners, all for the group {@value #GRP}.
     */
    private static List<PartitionOwners> owners(List<UUID> nodes, int[][] sets) {
        List<PartitionOwners> res = new ArrayList<>(sets.length);

        for (int i = 0; i < sets.length; i++) {
            List<UUID> os = new ArrayList<>(sets[i].length);

            for (int idx : sets[i])
                os.add(nodes.get(idx));

            res.add(new PartitionOwners(GRP, i, os));
        }

        return res;
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

        assertEquals(new HashSet<>(all), new HashSet<>(flat));
        assertEquals(all.size(), flat.size());
    }
}
