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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheRebalanceMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.management.safestopsets.BackupsCountSafeToStopSetsStrategy;
import org.apache.ignite.internal.management.safestopsets.SafeToStopSetsContextImpl;
import org.apache.ignite.internal.management.safestopsets.SafeToStopSetsNodeInfo;
import org.apache.ignite.internal.management.safestopsets.SafeToStopSetsTaskResult;
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

    /** Test cache name of the cache without backups. */
    private static final String NO_BACKUP_CACHE_NAME = "safe-to-stop-sets-no-backup-cache";

    /** Number of partitions of the test cache. */
    private static final int PARTS = 32;

    /** Number of nodes of the persistent cluster. */
    private static final int PERSISTENT_NODES = 6;

    /** Whether the test cluster must start in the inactive state. */
    private boolean inactiveOnStart;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        if (inactiveOnStart) {
            cfg.setClusterStateOnStart(ClusterState.INACTIVE);
            cfg.setAutoActivationEnabled(false);
        }

        return cfg;
    }

    /** {@inheritDoc} */
    @Override public void beforeTest() throws Exception {
        super.beforeTest();

        injectTestSystemOut();

        persistenceEnable(false);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        inactiveOnStart = false;

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
     * A non-activated cluster: the snapshot is not usable, the command fails closed with the not-ready
     * operator rejection, exit code 1, and no answer is printed.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testInactiveClusterIsNotReady() throws Exception {
        inactiveOnStart = true;

        startGrids(4);

        String out = exec(EXIT_CODE_INVALID_ARGUMENTS, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, out, "Check arguments.");
        GridTestUtils.assertContains(log, out, "not-ready:");
        GridTestUtils.assertContains(log, out, "Cluster is not active");
        assertFalse("No answer must be printed, output:\n" + out, out.contains("Topology version:"));
    }

    /**
     * A client node lives in the topology alongside the servers: it holds no partitions, so the command
     * succeeds, prints the server groups, and the client is absent both from the snapshot and from every
     * group.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testClientNodeExcludedFromGroups() throws Exception {
        startGrids(4);

        startClientGrid(CLIENT_NODE_NAME_PREFIX);

        createCache(CACHE_NAME, 2);

        String out = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, out,
            "Groups (stop the nodes of one group at a time, then re-run this command):");

        SafeToStopSetsTaskResult res = (SafeToStopSetsTaskResult) lastOperationResult;

        UUID clientId = grid(CLIENT_NODE_NAME_PREFIX).localNode().id();

        for (SafeToStopSetsNodeInfo node : res.nodes())
            assertFalse("The client node must not be part of the snapshot", clientId.equals(node.id()));

        for (List<UUID> grp : res.groups())
            assertFalse("The client node must not belong to any group", grp.contains(clientId));
    }

    /**
     * A persistent cluster: once the baseline topology is set (at activation) the command completes with
     * exit code 0, and two consecutive runs on the unchanged cluster produce an identical answer
     * (idempotency): the command never changes the cluster, the baseline or the cache state.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testIdempotentOnPersistentCluster() throws Exception {
        // The consistent ids are stable across the parameterized runs, so remove the per-node data left
        // by the previous run: otherwise the nodes would restore the cache created there.
        for (int i = 0; i < PERSISTENT_NODES; i++)
            cleanPersistenceDir(getTestIgniteInstanceName(i));

        persistenceEnable(true);

        IgniteEx crd = startGrids(PERSISTENT_NODES);

        crd.cluster().baselineAutoAdjustEnabled(false);

        crd.cluster().state(ClusterState.ACTIVE);

        createCache(CACHE_NAME, 2);

        String out1 = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS);

        String out2 = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS);

        assertEquals("The answer must be idempotent, first run:\n" + answer(out1) +
            "\nsecond run:\n" + answer(out2), answer(out1), answer(out2));

        GridTestUtils.assertContains(log, out1, "Topology version: ");
        GridTestUtils.assertContains(log, out1,
            "Groups (stop the nodes of one group at a time, then re-run this command):");
    }

    /**
     * The computation is always mapped to the coordinator (the server node with the lowest {@code order()}),
     * so the answer must not depend on which node the command is issued against: connecting to the
     * coordinator and to each non-coordinator yields the same topology version, the same groups and the
     * same verification mode ({@code D25}).
     *
     * @throws Exception If failed.
     */
    @Test
    public void testAnswerIdenticalRegardlessOfConnectionNode() throws Exception {
        startGrids(3);

        createCache(CACHE_NAME, 2);

        // grid(0) is the coordinator (lowest order()); the computation is mapped there in every run.
        exec(EXIT_CODE_OK, "--port", connectorPort(grid(0)), SAFE_TO_STOP_SETS);

        SafeToStopSetsTaskResult viaCoordinator = (SafeToStopSetsTaskResult) lastOperationResult;

        for (int i = 1; i < 3; i++) {
            exec(EXIT_CODE_OK, "--port", connectorPort(grid(i)), SAFE_TO_STOP_SETS);

            SafeToStopSetsTaskResult res = (SafeToStopSetsTaskResult) lastOperationResult;

            assertEquals("topVer must not depend on the connection node",
                viaCoordinator.topVer(), res.topVer());
            assertEquals("mode must not depend on the connection node",
                viaCoordinator.mode(), res.mode());
            assertEquals("groups must not depend on the connection node",
                sortedGroups(viaCoordinator.groups()), sortedGroups(res.groups()));
        }
    }

    /**
     * A cache without backups forbids every group of more than one node, so a cluster that has one is rejected
     * unless the operator excludes such caches. With {@code --no-backup ignore} the answer covers the remaining
     * groups and the excluded caches are not mentioned; with {@code --no-backup warning} they are listed.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNoBackupCachesAreExcludedOnRequest() throws Exception {
        startGrids(4);

        createCache(CACHE_NAME, 2);
        createCache(NO_BACKUP_CACHE_NAME, 0);

        String rejected = exec(EXIT_CODE_INVALID_ARGUMENTS, SAFE_TO_STOP_SETS);

        GridTestUtils.assertContains(log, rejected, "No safe group of more than one node");

        String ignored = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS, "--no-backup", "ignore");

        GridTestUtils.assertContains(log, ignored,
            "Groups (stop the nodes of one group at a time, then re-run this command):");
        assertFalse("The ignore mode must stay silent about the excluded caches, output:\n" + ignored,
            ignored.contains("without backups"));

        String warned = exec(EXIT_CODE_OK, SAFE_TO_STOP_SETS, "--no-backup", "warning");

        GridTestUtils.assertContains(log, warned,
            "Caches without backups (excluded from the computation, the answer does not cover them):");
        GridTestUtils.assertContains(log, warned, "  " + NO_BACKUP_CACHE_NAME);

        SafeToStopSetsTaskResult res = (SafeToStopSetsTaskResult) lastOperationResult;

        assertEquals(Collections.singletonList(NO_BACKUP_CACHE_NAME), res.excludedNoBackupGroups());
    }

    /**
     * An unknown value of {@code --no-backup} is an operator error: exit code 1 and the expected value in the
     * message.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testUnknownNoBackupMode() throws Exception {
        startGrids(4);

        createCache(CACHE_NAME, 2);

        String out = exec(EXIT_CODE_INVALID_ARGUMENTS, SAFE_TO_STOP_SETS, "--no-backup", "bogus");

        GridTestUtils.assertContains(log, out, "Check arguments.");
        GridTestUtils.assertContains(log, out, "bogus");
    }

    /**
     * A copy of the groups normalized for an order-insensitive comparison: the node ids within each group
     * are sorted, and the groups themselves are sorted by their first node.
     *
     * @param grps Groups to normalize.
     * @return The normalized groups.
     */
    private static List<List<UUID>> sortedGroups(List<List<UUID>> grps) {
        List<List<UUID>> res = new ArrayList<>(grps.size());

        for (List<UUID> grp : grps) {
            List<UUID> copy = new ArrayList<>(grp);

            Collections.sort(copy);

            res.add(copy);
        }

        res.sort(Comparator.comparing(grp -> grp.get(0).toString()));

        return res;
    }

    /**
     * Extracts the command answer from the output: the block between the argument banner and the
     * "finished" line. The banner itself carries the start timestamp, the completion time and the
     * execution time, which differ on every run and are not part of the answer.
     *
     * @param out Full command output.
     * @return The answer block.
     */
    private static String answer(String out) {
        List<String> lines = Arrays.asList(out.split("\\r?\\n"));

        int from = 0;
        int to = lines.size();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (from == 0 && line.startsWith("Arguments:"))
                from = i + 2;

            if (line.startsWith("Command [") && line.contains("finished"))
                to = i;
        }

        return String.join("\n", lines.subList(from, to));
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
