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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.CacheRebalanceMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.TestRecordingCommunicationSpi;
import org.apache.ignite.internal.processors.affinity.AffinityAssignment;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionSupplyMessage;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

/**
 * Tests the read-only cluster snapshot used to compute safe-to-stop sets.
 */
public class SafeToStopSetsContextSelfTest extends GridCommonAbstractTest {
    /** Partitioned cache name. */
    private static final String CACHE_NAME = "safe-to-stop-sets-cache";

    /** Replicated cache name. */
    private static final String REPL_CACHE_NAME = "safe-to-stop-sets-repl-cache";

    /** Cache without rebalance. */
    private static final String NO_REBALANCE_CACHE_NAME = "safe-to-stop-sets-no-rebalance-cache";

    /** Cache with the {@code IGNORE} partition loss policy. */
    private static final String IGNORE_LOSS_CACHE_NAME = "safe-to-stop-sets-ignore-loss-cache";

    /** Number of partitions of the test caches. */
    private static final int PARTS = 32;

    /** Whether the test cluster must be persistent. */
    private boolean persistence;

    /** Whether the test cluster must start in the inactive state. */
    private boolean inactiveOnStart;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setCommunicationSpi(new TestRecordingCommunicationSpi());

        if (inactiveOnStart) {
            cfg.setClusterStateOnStart(ClusterState.INACTIVE);
            cfg.setAutoActivationEnabled(false);
        }

        if (persistence) {
            cfg.setDataStorageConfiguration(new DataStorageConfiguration()
                .setDefaultDataRegionConfiguration(new DataRegionConfiguration()
                    .setMaxSize(200L * 1024 * 1024)
                    .setPersistenceEnabled(true)));
        }

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        persistence = false;
        inactiveOnStart = false;

        stopAllGrids();

        cleanPersistenceDir();

        super.afterTest();
    }

    /**
     * The snapshot gives the owners that match the affinity assignment of the split nodes, excludes the client
     * nodes and reads the layout lazily.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPartitionOwnersOfInMemoryCluster() throws Exception {
        startGridsMultiThreaded(3);

        createCache(partitionedCache(CACHE_NAME, 1));

        fillCache(CACHE_NAME);

        startClientGrid(3);

        awaitPartitionMapExchange();

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        assertTrue(ctx.notReadyReasons().toString(), ctx.notReadyReasons().isEmpty());
        assertFalse(ctx.notReady());
        assertTrue(ctx.partitionOwnersAvailable());
        assertTrue(ctx.topologyStable());
        assertTrue(ctx.ownershipMatchesAffinity());

        List<ClusterNode> nodes = ctx.nodes();

        assertEquals(3, nodes.size());

        for (ClusterNode node : nodes)
            assertFalse(node.isClient());

        for (int i = 1; i < nodes.size(); i++) {
            assertTrue("Nodes are not ordered by consistent ID",
                String.valueOf(nodes.get(i - 1).consistentId()).compareTo(String.valueOf(nodes.get(i).consistentId())) <= 0);
        }

        assertFalse(ctx.ownersRead());

        List<PartitionOwners> owners = ctx.partitionOwners();

        assertTrue(ctx.ownersRead());
        assertSame(owners, ctx.partitionOwners());

        assertEquals(PARTS, groupOwners(ctx, CACHE_NAME).size());

        Set<UUID> nodeIds = ids(nodes);

        for (PartitionOwners partOwners : owners) {
            assertFalse(partOwners.owners().isEmpty());

            for (UUID owner : partOwners.owners())
                assertTrue("Owner " + owner + " is not a snapshot node", nodeIds.contains(owner));
        }

        AffinityAssignment aff = affinity(CU.cacheId(CACHE_NAME), ctx.affinityTopologyVersion());

        for (PartitionOwners partOwners : groupOwners(ctx, CACHE_NAME)) {
            assertEquals("Unexpected number of copies [part=" + partOwners.partition() + ']',
                2, partOwners.owners().size());

            assertEquals(primaryId(aff, partOwners.partition()), partOwners.owners().get(0));
        }
    }

    /**
     * A replicated group reports {@code Integer.MAX_VALUE} backups, and its copy set is the affinity node set,
     * that is all server nodes of the snapshot.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testReplicatedGroup() throws Exception {
        startGridsMultiThreaded(3);

        createCache(new CacheConfiguration<>(REPL_CACHE_NAME)
            .setCacheMode(CacheMode.REPLICATED)
            .setPartitionLossPolicy(PartitionLossPolicy.READ_WRITE_SAFE)
            .setAffinity(new RendezvousAffinityFunction(false, PARTS)));

        fillCache(REPL_CACHE_NAME);

        awaitPartitionMapExchange();

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        CacheGroupInfo info = ctx.cacheGroups().get(REPL_CACHE_NAME);

        assertNotNull(info);
        assertEquals(CacheMode.REPLICATED, info.mode());
        assertTrue("Unexpected number of backups: " + info.backups(), info.backups() == Integer.MAX_VALUE);
        assertEquals(PartitionLossPolicy.READ_WRITE_SAFE, info.lossPolicy());

        List<PartitionOwners> owners = groupOwners(ctx, REPL_CACHE_NAME);

        assertEquals(PARTS, owners.size());

        for (PartitionOwners partOwners : owners)
            assertEquals(3, partOwners.owners().size());
    }

    /**
     * The nodes of the graceful shutdown kill set are excluded from the snapshot nodes and reported separately.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testGracefulShutdownNodesAreExcluded() throws Exception {
        startGridsMultiThreaded(3);

        createCache(partitionedCache(CACHE_NAME, 1));

        fillCache(CACHE_NAME);

        awaitPartitionMapExchange();

        UUID stoppingNodeId = grid(1).localNode().id();

        Set<UUID> killSet = new HashSet<>();
        killSet.add(stoppingNodeId);

        grid(0).context().distributedMetastorage().write(
            SafeToStopSetsContextImpl.GRACEFUL_SHUTDOWN_NODES_KEY,
            new HashSet<>(killSet));

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        assertEquals(killSet, new HashSet<>(ctx.gracefulShutdownNodes()));

        List<ClusterNode> nodes = ctx.nodes();

        assertEquals(2, nodes.size());

        for (ClusterNode node : nodes)
            assertFalse(stoppingNodeId.equals(node.id()));

        for (PartitionOwners partOwners : ctx.partitionOwners()) {
            if (CACHE_NAME.equals(partOwners.cacheGroupName()))
                assertFalse(partOwners.owners().contains(stoppingNodeId));
        }
    }

    /**
     * A snapshot of a non-activated cluster is marked as not ready instead of throwing from the constructor.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNotActiveClusterIsNotReady() throws Exception {
        inactiveOnStart = true;

        startGrid(0);

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        assertFalse(ctx.partitionOwnersAvailable());
        assertTrue(ctx.notReady());
        assertFalse(ctx.notReadyReasons().isEmpty());
        assertTrue(ctx.nodes().size() <= 1);

        try {
            ctx.partitionOwners();

            fail("Partition owners must not be readable on a non-ready snapshot");
        }
        catch (RuntimeException expected) {
            // Expected: the command fails closed instead of printing a partial answer.
        }
    }

    /**
     * A group with the disabled rebalance cannot have the actual ownership equal to the affinity assignment.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testRebalanceDisabledBreaksOwnershipMatch() throws Exception {
        startGridsMultiThreaded(3);

        createCache(partitionedCache(NO_REBALANCE_CACHE_NAME, 1)
            .setRebalanceMode(CacheRebalanceMode.NONE));

        awaitPartitionMapExchange();

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        assertTrue(ctx.partitionOwnersAvailable());
        assertFalse(ctx.ownershipMatchesAffinity());
    }

    /**
     * A group with the {@code IGNORE} partition loss policy cannot have the actual ownership equal to the
     * affinity assignment: the topology does not track the loss of such a group.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testIgnoreLossPolicyBreaksOwnershipMatch() throws Exception {
        startGridsMultiThreaded(3);

        createCache(partitionedCache(IGNORE_LOSS_CACHE_NAME, 1)
            .setPartitionLossPolicy(PartitionLossPolicy.IGNORE));

        awaitPartitionMapExchange();

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        assertTrue(ctx.partitionOwnersAvailable());
        assertFalse(ctx.ownershipMatchesAffinity());
    }

    /**
     * A persistent cluster reports the baseline nodes, and a replicated group is restricted by the baseline
     * affinity node set.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPersistentClusterWithBaseline() throws Exception {
        persistence = true;

        cleanPersistenceDir();

        startGridsMultiThreaded(3);

        createCache(new CacheConfiguration<>(REPL_CACHE_NAME)
            .setCacheMode(CacheMode.REPLICATED)
            .setPartitionLossPolicy(PartitionLossPolicy.READ_WRITE_SAFE)
            .setAffinity(new RendezvousAffinityFunction(false, PARTS)));

        createCache(partitionedCache(CACHE_NAME, 2));

        fillCache(REPL_CACHE_NAME);
        fillCache(CACHE_NAME);

        awaitPartitionMapExchange();

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        assertTrue(ctx.notReadyReasons().toString(), ctx.notReadyReasons().isEmpty());

        assertEquals(3, ctx.baselineNodes().size());
        assertEquals(ids(ctx.nodes()), new HashSet<>(ctx.baselineNodes()));

        for (PartitionOwners partOwners : groupOwners(ctx, REPL_CACHE_NAME))
            assertEquals(3, partOwners.owners().size());

        for (PartitionOwners partOwners : groupOwners(ctx, CACHE_NAME))
            assertEquals(3, partOwners.owners().size());
    }

    /**
     * The copies that are in the {@code MOVING} and {@code RENTING} states are the part of the owner set even
     * when the node that holds them is no longer an affinity node of the partition.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testOwnersIncludeMovingCopies() throws Exception {
        startGridsMultiThreaded(3);

        createCache(partitionedCache(CACHE_NAME, 1));

        fillCache(CACHE_NAME);

        awaitPartitionMapExchange();

        for (int i = 0; i < 3; i++)
            blockSupply(TestRecordingCommunicationSpi.spi(grid(i)));

        startGrid(3);

        int grpId = CU.cacheId(CACHE_NAME);

        assertTrue("Rebalance of the joining node has not started", GridTestUtils.waitForCondition(() -> {
            CacheGroupContext grp = grid(0).context().cache().cacheGroup(grpId);

            return grp != null
                && grp.topology().initialized()
                && grp.topology().readyTopologyVersion().compareTo(grid(0).context().discovery().topologyVersionEx()) >= 0
                && grp.topology().hasMovingPartitions();
        }, 30_000));

        TestRecordingCommunicationSpi.spi(grid(0)).waitForBlocked();

        SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(grid(0));

        assertTrue(ctx.partitionOwnersAvailable());
        assertFalse("A group with moving partitions must not match its affinity", ctx.ownershipMatchesAffinity());

        List<PartitionOwners> owners = groupOwners(ctx, CACHE_NAME);

        assertEquals(PARTS, owners.size());

        AffinityAssignment aff = affinity(CU.cacheId(CACHE_NAME), ctx.affinityTopologyVersion());

        boolean movingOwnerFound = false;
        boolean extraOwnerFound = false;

        for (PartitionOwners partOwners : owners) {
            Set<UUID> affIds = new HashSet<>(aff.get(partOwners.partition()).stream()
                .map(ClusterNode::id)
                .collect(Collectors.toList()));

            for (UUID owner : partOwners.owners()) {
                if (affIds.contains(owner))
                    continue;

                extraOwnerFound = true;
            }

            if (partOwners.owners().contains(grid(3).localNode().id()))
                movingOwnerFound = true;
        }

        assertTrue("The joining node is missing in the owner sets", movingOwnerFound);
        assertTrue("Extra copies of the previous owners are missing in the owner sets", extraOwnerFound);

        for (int i = 0; i < 3; i++)
            TestRecordingCommunicationSpi.spi(grid(i)).stopBlock();
    }

    /**
     * @param name Cache name.
     * @param backups Number of backups.
     * @return Partitioned cache configuration.
     */
    private static CacheConfiguration<Object, Object> partitionedCache(String name, int backups) {
        return new CacheConfiguration<>(name)
            .setCacheMode(CacheMode.PARTITIONED)
            .setBackups(backups)
            .setPartitionLossPolicy(PartitionLossPolicy.READ_WRITE_SAFE)
            .setAffinity(new RendezvousAffinityFunction(false, PARTS));
    }

    /**
     * @param ccfg Cache configuration.
     */
    private void createCache(CacheConfiguration<Object, Object> ccfg) {
        // A dynamic cache configuration is distributed over the cluster, so one call is enough.
        grid(0).createCache(ccfg);
    }

    /**
     * @param name Cache name.
     */
    private void fillCache(String name) {
        IgniteCache<Integer, Integer> cache = grid(0).cache(name);

        for (int i = 0; i < 1000; i++)
            cache.put(i, i);
    }

    /**
     * @param spi SPI to block in.
     */
    private static void blockSupply(TestRecordingCommunicationSpi spi) {
        int grpId = CU.cacheId(CACHE_NAME);

        spi.blockMessages((node, msg) ->
            msg instanceof GridDhtPartitionSupplyMessage && ((GridDhtPartitionSupplyMessage)msg).groupId() == grpId);
    }

    /**
     * @param grpId Cache group ID.
     * @param topVer Topology version of the snapshot.
     * @return Ready affinity assignment of the group.
     */
    private AffinityAssignment affinity(int grpId, AffinityTopologyVersion topVer) {
        CacheGroupContext grp = grid(0).context().cache().cacheGroup(grpId);

        assertNotNull(grp);

        return grp.affinity().readyAffinity(topVer);
    }

    /**
     * @param aff Affinity assignment.
     * @param part Partition number.
     * @return ID of the primary node of the partition.
     */
    private static UUID primaryId(AffinityAssignment aff, int part) {
        List<ClusterNode> affNodes = aff.get(part);

        assertFalse(affNodes.isEmpty());

        return affNodes.get(0).id();
    }

    /**
     * @param nodes Nodes.
     * @return IDs of the nodes.
     */
    private static Set<UUID> ids(List<ClusterNode> nodes) {
        return nodes.stream().map(ClusterNode::id).collect(Collectors.toSet());
    }

    /**
     * @param ctx Snapshot.
     * @param grpName Cache group name.
     * @return Owners of the partitions of the group.
     */
    private static List<PartitionOwners> groupOwners(SafeToStopSetsContext ctx, String grpName) {
        List<PartitionOwners> res = new ArrayList<>();

        for (PartitionOwners partOwners : ctx.partitionOwners()) {
            if (grpName.equals(partOwners.cacheGroupName()))
                res.add(partOwners);
        }

        return res;
    }
}
