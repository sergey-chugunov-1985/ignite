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
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cluster.ClusterNode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the consumption contract of {@link AbstractSafeToStopSetsStrategy}: a strategy that only needs its own
 * visiting order overrides {@link AbstractSafeToStopSetsStrategy#orderNodes} alone, and the group bound is the
 * one {@link SafeToStopSetsSupport#maxSafeGroupSize(SafeToStopSetsContext)} derives from the configuration.
 */
public class SafeToStopSetsAbstractStrategySelfTest {
    /** Name of the group used in the configuration. */
    private static final String GRP = "grp";

    /** Name of the second group used in the configuration. */
    private static final String GRP2 = "grp2";

    /**
     * A subclass that overrides the visiting order alone: the shape the abstract base is meant to be consumed
     * with (a private plugin overrides {@code orderNodes} and, optionally, the domain hypergraph).
     */
    private static class OrderOnlyStrategy extends AbstractSafeToStopSetsStrategy {
        /** {@inheritDoc} */
        @Override public String name() {
            return "test-order-only";
        }

        /** {@inheritDoc} */
        @Override protected List<ClusterNode> orderNodes(SafeToStopSetsContext ctx) {
            return ctx.nodes();
        }
    }

    /**
     * A subclass that violates the contract of the hook: the base class must reject the answer instead of
     * clamping the bound, otherwise the defect is hidden behind a plausible result.
     */
    private static class ZeroBoundStrategy extends AbstractSafeToStopSetsStrategy {
        /** {@inheritDoc} */
        @Override public String name() {
            return "test-zero-bound";
        }

        /** {@inheritDoc} */
        @Override protected List<ClusterNode> orderNodes(SafeToStopSetsContext ctx) {
            return ctx.nodes();
        }

        /** {@inheritDoc} */
        @Override protected int maxGroupSize(SafeToStopSetsContext ctx) {
            return 0;
        }
    }

    /**
     * A subclass that overrides the order only compiles and answers with the configuration bound: no group holds
     * all copies of a partition.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testOrderOnlySubclassUsesConfigurationBound() throws Exception {
        List<UUID> nodes = ids(7);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 2), aff(GRP, 7));

        List<List<UUID>> res = new OrderOnlyStrategy().split(ctx);

        // copies = min(2 + 1, 7) = 3, bound = 3 - 1 = 2: the nodes are packed two per group.
        assertEquals(4, res.size());
        assertEquals(Arrays.asList(nodes.get(0), nodes.get(1)), res.get(0));
        assertEquals(Arrays.asList(nodes.get(2), nodes.get(3)), res.get(1));
        assertEquals(Arrays.asList(nodes.get(4), nodes.get(5)), res.get(2));
        assertEquals(Collections.singletonList(nodes.get(6)), res.get(3));

        assertExactPartition(res, nodes);
        assertTrue("The abstract base reads the partition owners", ctx.ownersRead());
    }

    /**
     * Without a user cache group there is no copy bound at all: the bound is "no limit" and the whole cluster is
     * one group.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDefaultBoundWithoutCacheGroupsIsUnlimited() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = new TestSafeToStopSetsContext(
            nodes, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());

        assertEquals(Integer.MAX_VALUE, SafeToStopSetsSupport.maxSafeGroupSize(ctx));

        List<List<UUID>> res = new OrderOnlyStrategy().split(ctx);

        assertEquals(1, res.size());
        assertEquals(nodes, res.get(0));
    }

    /**
     * With zero backups a partition has a single copy, so the bound is {@code 1}: no two nodes may be stopped
     * together.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDefaultBoundWithSingleCopyIsOne() throws Exception {
        List<UUID> nodes = ids(4);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 0), aff(GRP, 4));

        assertEquals(1, SafeToStopSetsSupport.maxSafeGroupSize(ctx));

        List<List<UUID>> res = new OrderOnlyStrategy().split(ctx);

        assertEquals(nodes, singletons(res));
    }

    /**
     * The copy set of a replicated group is its affinity node set, so the bound is computed without
     * {@code backups + 1}: the {@code Integer.MAX_VALUE} backups of the real configuration must not overflow.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testReplicatedGroupBoundIsAffinityNodesMinusOne() throws Exception {
        List<UUID> nodes = ids(6);

        TestSafeToStopSetsContext ctx = ctx(nodes, repl(GRP), aff(GRP, 3));

        assertEquals(2, SafeToStopSetsSupport.maxSafeGroupSize(ctx));

        List<List<UUID>> res = new OrderOnlyStrategy().split(ctx);

        assertEquals(3, res.size());
        assertExactPartition(res, nodes);

        for (List<UUID> grp : res)
            assertTrue(grp + " is larger than the bound", grp.size() <= 2);
    }

    /**
     * The smallest copy bound over the groups is the binding one, and the affinity node set of a group is used
     * instead of the number of server nodes.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDefaultBoundIsSmallestOverGroups() throws Exception {
        List<UUID> nodes = ids(6);

        Map<String, CacheGroupInfo> groups = new LinkedHashMap<>();
        groups.put(GRP, grp(GRP, 2));
        groups.put(GRP2, repl(GRP2));

        // The partitioned group has min(2 + 1, 6) = 3 copies, the replicated one has 2: the replicated group
        // binds the answer, so no two nodes may be stopped together.
        TestSafeToStopSetsContext ctx = new TestSafeToStopSetsContext(
            nodes, groups, aff(GRP, 6, GRP2, 2), Collections.emptyList());

        assertEquals(1, SafeToStopSetsSupport.maxSafeGroupSize(ctx));
    }

    /**
     * A bound below {@code 1} violates the contract of the hook: the base class fails with a usage error naming
     * the offending strategy instead of silently clamping the value to {@code 1}.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNonPositiveBoundIsRejected() throws Exception {
        List<UUID> nodes = ids(3);

        TestSafeToStopSetsContext ctx = ctx(nodes, grp(GRP, 2), aff(GRP, 3));

        try {
            new ZeroBoundStrategy().split(ctx);

            fail("A bound below 1 violates the contract of the hook");
        }
        catch (IllegalArgumentException expected) {
            String msg = expected.getMessage();

            assertTrue(msg, msg.contains(ZeroBoundStrategy.class.getName()));
            assertTrue(msg, msg.contains("maxGroupSize"));
            assertTrue(msg, msg.contains("0"));
        }
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
        return new CacheGroupInfo(name, CacheMode.PARTITIONED, backups, PartitionLossPolicy.READ_WRITE_SAFE, null);
    }

    /**
     * @param name Group name.
     * @return Replicated group configuration, with the {@code Integer.MAX_VALUE} backups of the real
     *      configuration.
     */
    private static CacheGroupInfo repl(String name) {
        return new CacheGroupInfo(name, CacheMode.REPLICATED, Integer.MAX_VALUE, PartitionLossPolicy.READ_WRITE_SAFE, null);
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
     * @param grp Single group configuration.
     * @param affinityNodes Affinity node set sizes.
     * @return Fake snapshot with the one group and no partition owners.
     */
    private static TestSafeToStopSetsContext ctx(
        List<UUID> nodes,
        CacheGroupInfo grp,
        Map<String, Integer> affinityNodes) {
        Map<String, CacheGroupInfo> groups = new LinkedHashMap<>();
        groups.put(grp.name(), grp);

        return new TestSafeToStopSetsContext(nodes, groups, affinityNodes, Collections.emptyList());
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
     * @param res Groups of node IDs.
     * @param all Expected node IDs.
     */
    private static void assertExactPartition(List<List<UUID>> res, List<UUID> all) {
        List<UUID> flat = new ArrayList<>();

        for (List<UUID> grp : res) {
            assertTrue("Empty groups are not allowed", !grp.isEmpty());
            flat.addAll(grp);
        }

        assertEquals(new HashSet<>(all), new HashSet<>(flat));
        assertEquals(all.size(), flat.size());
    }
}
