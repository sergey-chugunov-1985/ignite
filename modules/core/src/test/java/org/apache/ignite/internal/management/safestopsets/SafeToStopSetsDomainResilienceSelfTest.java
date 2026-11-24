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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.management.api.CommandUtils;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.cache.PartitionLossPolicy.READ_WRITE_SAFE;

/**
 * Integration test of the hypergraph extension point on a real cluster: an out-of-tree strategy with a domain
 * constraint is resolved through the service file, runs the full task path on the coordinator and produces an
 * answer verified as {@link SafeToStopSetsVerification.Mode#EXACT}.
 * <p>
 * The test cluster has four server nodes, that is two domains of two nodes (see
 * {@link TestDomainResilienceSafeToStopSetsStrategy}), and every partition of the test cache is replicated to
 * every server node: any group takes one node of every domain and keeps at least one copy of every partition in
 * both domains. The core verifies only the default invariant "no group holds a whole owner set", so the domain
 * property itself is checked by the test.
 */
public class SafeToStopSetsDomainResilienceSelfTest extends GridCommonAbstractTest {
    /** Test cache name. */
    private static final String CACHE_NAME = "safe-to-stop-sets-domain-cache";

    /** Number of server nodes: two domains of two nodes. */
    private static final int NODES = 4;

    /** Number of partitions of the test cache. */
    private static final int PARTS = 32;

    /** Number of entries written to the test cache, so every partition gets an owner record. */
    private static final int ENTRIES = 1000;

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        super.afterTest();
    }

    /**
     * The answer of the domain-resilience strategy is {@code exact}: the strategy reads the partition owners, the
     * groups respect its edges, and every group keeps a copy of every partition in every domain.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDomainResilienceAnswerIsExact() throws Exception {
        startGridsMultiThreaded(NODES);

        createCache();

        fillCache();

        awaitPartitionMapExchange();

        awaitOwnersAvailable();

        SafeToStopSetsContextImpl snapshot = context();

        List<PartitionOwners> owners = snapshot.partitionOwners();

        SafeToStopSetsTaskResult res = executeTask(TestDomainResilienceSafeToStopSetsStrategy.NAME);

        assertEquals("The answer of a strategy with a domain constraint must be verified exactly",
            SafeToStopSetsVerification.Mode.EXACT.name(), res.mode());
        assertEquals(TestDomainResilienceSafeToStopSetsStrategy.NAME, res.strategyName());
        assertEquals(TestDomainResilienceSafeToStopSetsStrategy.class.getName(), res.strategyClass());
        assertTrue("An exact answer must carry no reasons: " + res.notVerified(), res.notVerified().isEmpty());
        assertEquals(NODES, res.nodes().size());

        Map<UUID, Integer> domains = TestDomainResilienceSafeToStopSetsStrategy.domains(snapshot.nodes());

        assertGroupsTakeOneNodeOfEveryDomain(res, domains);

        // Every partition of the test cache is replicated to every server node, so each domain holds two copies and
        // the domain constraint is not trivially satisfied by the group size.
        for (PartitionOwners owned : owners)
            assertEquals("Partition [" + owned.cacheGroupName() + '/' + owned.partition() + "] must have a copy "
                + "at every server node, otherwise the domain check is vacuous: " + owned.owners(),
                NODES, owned.owners().size());

        assertDomainProperty(res.groups(), owners, domains);
    }

    /**
     * The domain property, checked by the test and not by the core: stopping a whole group must not leave a domain
     * without a copy of some partition.
     *
     * @param groups Groups of node ids.
     * @param owners Partition owners of the snapshot.
     * @param domains Node id to domain index mapping.
     */
    private static void assertDomainProperty(
        List<List<UUID>> groups,
        List<PartitionOwners> owners,
        Map<UUID, Integer> domains
    ) {
        for (List<UUID> grp : groups) {
            for (PartitionOwners owned : owners) {
                Map<Integer, List<UUID>> parts = new LinkedHashMap<>();

                for (UUID id : owned.owners())
                    parts.computeIfAbsent(domains.get(id), domain -> new ArrayList<>()).add(id);

                for (Map.Entry<Integer, List<UUID>> part : parts.entrySet()) {
                    assertFalse("Stopping " + grp + " loses the last copy of partition ["
                        + owned.cacheGroupName() + '/' + owned.partition() + "] in the domain " + part.getKey()
                        + ", the copies there: " + part.getValue(), grp.containsAll(part.getValue()));
                }
            }
        }
    }

    /**
     * Checks the shape of the answer the domain bound implies: the groups partition the snapshot nodes, every group
     * takes exactly one node of every domain, and no pair of nodes of the same domain shares a group.
     *
     * @param res Task result.
     * @param domains Node id to domain index mapping.
     */
    private static void assertGroupsTakeOneNodeOfEveryDomain(SafeToStopSetsTaskResult res, Map<UUID, Integer> domains) {
        Set<Integer> allDomains = new HashSet<>(domains.values());

        Set<UUID> snapshot = new HashSet<>();

        for (SafeToStopSetsNodeInfo node : res.nodes())
            snapshot.add(node.id());

        Set<UUID> seen = new HashSet<>();

        assertEquals("Every group must take one node of every domain", NODES / allDomains.size(), res.groups().size());

        for (List<UUID> grp : res.groups()) {
            Set<Integer> grpDomains = new HashSet<>();

            for (UUID id : grp) {
                assertTrue("The group must contain snapshot nodes only: " + grp, snapshot.contains(id));
                assertTrue("The node must belong to exactly one group: " + id, seen.add(id));
                assertTrue("The group must not take two nodes of the same domain: " + grp,
                    grpDomains.add(domains.get(id)));
            }

            assertEquals("The group must take one node of every domain: " + grp, allDomains, grpDomains);
        }

        assertEquals("The groups must partition the snapshot nodes", snapshot, seen);
    }

    /**
     * @return Snapshot of the cluster state as it is read on {@code grid(0)}, the coordinator.
     */
    private SafeToStopSetsContextImpl context() {
        return new SafeToStopSetsContextImpl((IgniteEx) grid(0));
    }

    /**
     * Waits until a fresh snapshot reports the partition owners as available: the DHT topology of the cache group
     * is initialized and ready at the snapshot topology version.
     *
     * @throws Exception If the condition is not met within the timeout.
     */
    private void awaitOwnersAvailable() throws Exception {
        assertTrue("The partition owners did not become available", GridTestUtils.waitForCondition(
            () -> new SafeToStopSetsContextImpl((IgniteEx) grid(0)).partitionOwnersAvailable(), 15_000));
    }

    /**
     * Creates the test cache: {@code backups = nodes - 1}, so every partition is replicated to every server node.
     */
    private void createCache() {
        grid(0).createCache(new CacheConfiguration<>(CACHE_NAME)
            .setCacheMode(PARTITIONED)
            .setBackups(NODES - 1)
            .setPartitionLossPolicy(READ_WRITE_SAFE)
            .setAffinity(new RendezvousAffinityFunction(false, PARTS)));
    }

    /**
     * Writes enough entries to touch every partition and settle the DHT.
     */
    private void fillCache() {
        IgniteCache<Object, Object> cache = grid(0).cache(CACHE_NAME);

        for (int i = 0; i < ENTRIES; i++)
            cache.put(i, i);
    }

    /**
     * Invokes the task on the coordinator only, the way the command does.
     *
     * @param strategy Name of the strategy to resolve through the service file.
     * @return The task result.
     * @throws Exception If failed.
     */
    private SafeToStopSetsTaskResult executeTask(String strategy) throws Exception {
        ClusterNode coordinator = grid(0).cluster().localNode();

        SafeToStopSetsCommandArg arg = new SafeToStopSetsCommandArg();

        arg.strategy(strategy);

        return CommandUtils.execute(null, grid(0), SafeToStopSetsTask.class, arg, Collections.singleton(coordinator));
    }
}
