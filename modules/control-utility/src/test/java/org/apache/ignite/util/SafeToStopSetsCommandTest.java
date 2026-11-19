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

package org.apache.ignite.util;

import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheRebalanceMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.management.safestopsets.BackupsCountSafeToStopSetsStrategy;
import org.apache.ignite.internal.management.safestopsets.SafeToStopSetsContextImpl;
import org.apache.ignite.testframework.GridTestUtils;
import org.junit.Test;

import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.internal.commandline.CommandHandler.EXIT_CODE_INVALID_ARGUMENTS;
import static org.apache.ignite.internal.commandline.CommandHandler.EXIT_CODE_OK;

/**
 * Integration tests for the {@code --safe-to-stop-sets} command: the full path
 * {@code CommandHandler} -> {@code SafeToStopSetsCommand.nodes()} (coordinator) ->
 * {@code SafeToStopSetsTask} -> result, executed on a real cluster.
 */
public class SafeToStopSetsCommandTest extends GridCommandHandlerAbstractTest {
    /** Command name. */
    private static final String SAFE_TO_STOP_SETS = "--safe-to-stop-sets";

    /** Test cache name. */
    private static final String CACHE_NAME = "safe-to-stop-sets-cache";

    /** Number of partitions of the test cache. */
    private static final int PARTS = 32;

    /** {@inheritDoc} */
    @Override public void beforeTest() throws Exception {
        super.beforeTest();

        injectTestSystemOut();

        persistenceEnable(false);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        stopAllGrids();
    }

    /**
     * A 4-node cluster with a partitioned cache (backups = 2): the command completes with code 0 and
     * prints two groups of two nodes (any pair can be stopped together with backups = 2).
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSuccessOnMultiNodeCluster() throws Exception {
        startGrids(4);

        createCache(CACHE_NAME, 2);

        String out = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, out, "Topology version: ");
        GridTestUtils.assertContains(log, out, "Strategy: " + BackupsCountSafeToStopSetsStrategy.NAME);
        GridTestUtils.assertContains(log, out,
            "Groups (stop the nodes of one group at a time, then re-run this command):");

        // With backups = 2 and 4 rendezvous nodes the greedy pass produces exactly two groups of two.
        int groupLines = 0;

        for (String line : out.split("\\r?\\n")) {
            if (line.matches(" *\\[\\d+\\] \\S+, \\S+"))
                groupLines++;
        }

        assertEquals("Expected two groups of two nodes, output:\n" + out, 2, groupLines);
        assertFalse("Singletons must be absent, output:\n" + out, out.contains("Singletons"));
    }

    /**
     * Two nodes with a cache of {@code backups = 0} (no copies at all) and then a second cache of
     * {@code backups = 1}: in both cases no group of more than one node can be stopped together, the
     * command rejects with the operator error (exit code 1) and lists every node.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNoSafeGroup() throws Exception {
        startGrids(2);

        createCache(CACHE_NAME, 0);

        checkNoSafeGroup();

        createCache(CACHE_NAME + "-backups-1", 1);

        checkNoSafeGroup();
    }

    /**
     * A single-node cluster: the only node cannot be stopped together with any other node, the command
     * rejects with the operator error (exit code 1) naming the reason.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSingleNodeCluster() throws Exception {
        startGrids(1);

        createCache(CACHE_NAME, 1);

        String out = exec(EXIT_CODE_INVALID_ARGUMENTS, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, out, "Check arguments.");
        GridTestUtils.assertContains(log, out, "No safe group of more than one node");
        GridTestUtils.assertContains(log, out, "the cluster has a single server node");
    }

    /**
     * An unknown strategy name: the command rejects with the list of available names, exit code 1.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testUnknownStrategy() throws Exception {
        startGrids(4);

        createCache(CACHE_NAME, 2);

        String out = exec(EXIT_CODE_INVALID_ARGUMENTS, SAFE_TO_STOP_SETS, "--strategy", "bogus");

        GridTestUtils.assertContains(log, out, "Check arguments.");
        GridTestUtils.assertContains(log, out, "Unknown safe-to-stop-sets strategy [name=bogus]");
        GridTestUtils.assertContains(log, out, BackupsCountSafeToStopSetsStrategy.NAME);
    }

    /**
     * A stable cluster: the ownership matches the affinity assignment, so the default strategy does not read
     * the partition owners and the answer is derived from the configuration only. The verification is marked
     * structure-only with the reason, exit code 0. The cache uses a non-{@code IGNORE} loss policy: with the
     * default policy the topology does not track partition loss and the structure-only proof is not legal.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testStructureOnlyOnStableCluster() throws Exception {
        startGrids(4);

        createCache(CACHE_NAME, 2, null, PartitionLossPolicy.READ_WRITE_SAFE);

        fillCache(CACHE_NAME);

        awaitPartitionMapExchange();

        waitForOwnershipMatched();

        String out = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, out, "Verified: structure-only");
        GridTestUtils.assertContains(log, out, "Reason: The partition owners were not read");
        GridTestUtils.assertContains(log, out,
            "Groups (stop the nodes of one group at a time, then re-run this command):");
        assertFalse("exact must be absent, output:\n" + out, out.contains("Verified: exact"));
    }

    /**
     * A cache with rebalance disabled cannot prove that the ownership matches the affinity, so the default
     * strategy reads the actual partition owners and the answer is verified semantically: exact, exit code 0.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testExactWhenOwnersAreRead() throws Exception {
        startGrids(4);

        createCache(CACHE_NAME, 2, CacheRebalanceMode.NONE);

        String out = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, out, "Verified: exact");
        GridTestUtils.assertContains(log, out,
            "Groups (stop the nodes of one group at a time, then re-run this command):");
        assertFalse("structure-only must be absent, output:\n" + out, out.contains("Verified: structure-only"));
    }

    /**
     * Executes the command and checks the no-safe-group operator rejection: exit code 1 and the message
     * that lists every node as a singleton.
     *
     * @throws Exception If failed.
     */
    private void checkNoSafeGroup() throws Exception {
        String out = exec(EXIT_CODE_INVALID_ARGUMENTS, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, out, "Check arguments.");
        GridTestUtils.assertContains(log, out, "No safe group of more than one node");
        GridTestUtils.assertContains(log, out, "every node can be stopped only on its own");
    }

    /**
     * Executes the command and checks its exit code.
     *
     * @param expExitCode Expected exit code.
     * @param args Command arguments.
     * @return The command output.
     */
    private String exec(int expExitCode, String... args) {
        return executeCommand(expExitCode, args);
    }

    /**
     * @param name Cache name.
     * @param backups Number of backups for the test cache.
     * @throws Exception If failed.
     */
    private void createCache(String name, int backups) throws Exception {
        createCache(name, backups, null, null);
    }

    /**
     * @param name Cache name.
     * @param backups Number of backups for the test cache.
     * @param rebalanceMode Rebalance mode, {@code null} for the default.
     * @throws Exception If failed.
     */
    private void createCache(String name, int backups, CacheRebalanceMode rebalanceMode) throws Exception {
        createCache(name, backups, rebalanceMode, null);
    }

    /**
     * @param name Cache name.
     * @param backups Number of backups for the test cache.
     * @param rebalanceMode Rebalance mode, {@code null} for the default.
     * @param lossPolicy Partition loss policy, {@code null} for the default.
     * @throws Exception If failed.
     */
    private void createCache(String name, int backups, CacheRebalanceMode rebalanceMode,
        PartitionLossPolicy lossPolicy) throws Exception {
        CacheConfiguration<Object, Object> cfg = new CacheConfiguration<>()
            .setName(name)
            .setCacheMode(PARTITIONED)
            .setBackups(backups)
            .setAffinity(new org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction(false, PARTS));

        if (rebalanceMode != null)
            cfg.setRebalanceMode(rebalanceMode);

        if (lossPolicy != null)
            cfg.setPartitionLossPolicy(lossPolicy);

        grid(0).createCache(cfg);

        awaitPartitionMapExchange();
    }

    /**
     * Writes enough entries to touch every partition and settle the DHT: the partition supply completes and
     * the ownership matches the affinity assignment, which is the precondition of the structure-only proof.
     *
     * @param name Cache name.
     * @throws Exception If failed.
     */
    private void fillCache(String name) throws Exception {
        IgniteCache<Object, Object> cache = grid(0).cache(name);

        for (int i = 0; i < 1000; i++)
            cache.put(i, i);
    }

    /**
     * Waits until a fresh snapshot of the cluster reports that the actual ownership matches the affinity
     * assignment: the precondition of the structure-only proof. The DHT settles only after the partition
     * supply completes, which may take a moment beyond {@link #awaitPartitionMapExchange()}.
     *
     * @throws Exception If the condition is not met within the timeout.
     */
    private void waitForOwnershipMatched() throws Exception {
        assertTrue("The ownership did not settle to match the affinity", GridTestUtils.waitForCondition(() -> {
            SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl((IgniteEx) grid(0));

            return ctx.partitionOwnersAvailable() && ctx.ownershipMatchesAffinity();
        }, 15_000));
    }
}
