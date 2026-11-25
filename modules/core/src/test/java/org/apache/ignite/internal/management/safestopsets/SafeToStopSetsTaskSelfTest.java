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

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.management.api.CommandUtils;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.cache.PartitionLossPolicy.READ_WRITE_SAFE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the safe-to-stop-sets task job: the success path on the coordinator, the operator rejection when
 * no safe group exists, and the not-ready rejection on a non-activated cluster.
 */
public class SafeToStopSetsTaskSelfTest extends GridCommonAbstractTest {
    /** Test cache name. */
    private static final String CACHE_NAME = "safe-to-stop-sets-task-cache";

    /** Test cache name of the group without backups. */
    private static final String NO_BACKUP_CACHE_NAME = "safe-to-stop-sets-task-no-backup-cache";

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        return super.getConfiguration(igniteInstanceName);
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        super.afterTest();
    }

    /**
     * A healthy 3-node cluster with a partitioned cache (backups = 2) produces groups of size 2:
     * the job runs on the coordinator and the result is a valid partition.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSuccessOnCoordinator() throws Exception {
        startGridsMultiThreaded(3);

        createCache(2);

        awaitPartitionMapExchange();

        SafeToStopSetsTaskResult res = executeTask();

        assertNotNull(res);
        assertEquals(3, res.nodes().size());
        assertFalse("The result must contain at least one group of size > 1", res.groups().isEmpty());

        for (List<UUID> group : res.groups())
            assertTrue("Groups must not exceed the bound: " + group, group.size() <= 2);
    }

    /**
     * A 2-node cluster with backups = 1 has no safe group of size > 1: the job fails with an operator
     * rejection listing every node as a singleton.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNoSafeGroup() throws Exception {
        startGridsMultiThreaded(2);

        createCache(1);

        awaitPartitionMapExchange();

        try {
            executeTask();

            fail("Expected an operator rejection");
        }
        catch (Exception e) {
            assertTrue("Expected IAE in cause chain: " + e, X.hasCause(e, IllegalArgumentException.class));

            String msg = X.cause(e, IllegalArgumentException.class).getMessage();

            assertTrue("Message must identify the no-safe-group case: " + msg,
                msg.contains("No safe group of more than one node"));
        }
    }

    /**
     * The operator rejection from the job is correctly wrapped by the Visor framework: the cause chain
     * contains an IllegalArgumentException (the marker the handleException in the command uses to
     * identify operator rejections).
     *
     * @throws Exception If failed.
     */
    @Test
    public void testOperatorRejectionIsInCauseChain() throws Exception {
        startGridsMultiThreaded(2);

        createCache(1);

        awaitPartitionMapExchange();

        try {
            executeTask();

            fail("Expected an operator rejection");
        }
        catch (Exception e) {
            // The Visor framework wraps the job exception in ComputeUserUndeclaredException;
            // the original IAE must be in the cause chain for the command's handleException to
            // identify it as an operator rejection and produce exit code 1.
            assertTrue("IAE must be in cause chain: " + e, X.hasCause(e, IllegalArgumentException.class));

            String msg = X.cause(e, IllegalArgumentException.class).getMessage();

            assertTrue("Message must not contain internal wrapper text: " + msg,
                !msg.contains("ComputeUserUndeclared"));
        }
    }

    /**
     * A cache group without backups forbids stopping any two nodes together, so without the exclusion the
     * cluster is rejected as having no safe group; with the exclusion the answer covers the remaining groups,
     * and its report names the excluded ones.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNoBackupCacheIsExcludedOnRequest() throws Exception {
        startGridsMultiThreaded(4);

        createCache(2);

        grid(0).createCache(new CacheConfiguration<>(NO_BACKUP_CACHE_NAME)
            .setCacheMode(PARTITIONED)
            .setBackups(0)
            .setPartitionLossPolicy(READ_WRITE_SAFE)
            .setAffinity(new RendezvousAffinityFunction(false, 32)));

        awaitPartitionMapExchange();

        try {
            executeTask();

            fail("A cache without backups must forbid every group of more than one node");
        }
        catch (Exception e) {
            assertTrue("Expected IAE in cause chain: " + e, X.hasCause(e, IllegalArgumentException.class));
            assertTrue(X.cause(e, IllegalArgumentException.class).getMessage().contains("No safe group"));
        }

        for (SafeToStopSetsNoBackupMode mode : SafeToStopSetsNoBackupMode.values()) {
            SafeToStopSetsCommandArg arg = new SafeToStopSetsCommandArg();

            arg.noBackup(mode);

            SafeToStopSetsTaskResult res = executeTask(arg);

            assertEquals(Collections.singletonList(NO_BACKUP_CACHE_NAME), res.excludedNoBackupGroups());
            assertTrue("The remaining groups allow a pair: " + res.groups(),
                res.groups().stream().anyMatch(grp -> grp.size() > 1));

            // The remaining group has backups = 2 among 4 nodes, so a group of two is the largest safe one.
            for (List<UUID> grp : res.groups())
                assertTrue("Groups are bounded by the remaining group's copy bound: " + grp, grp.size() <= 2);
        }
    }

    /**
     * A configuration that makes the number of copies unprovable is a refusal of the strategy, not a fault of
     * the command: the cause chain must carry an {@link IllegalArgumentException}, the marker that turns the
     * failure into a usage error (exit code 1) listing the offending groups and the strategy hint.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testStructureOnlyRefusalIsUsageError() throws Exception {
        startGridsMultiThreaded(3);

        grid(0).createCache(new CacheConfiguration<>(CACHE_NAME)
            .setCacheMode(PARTITIONED)
            .setBackups(2)
            .setPartitionLossPolicy(READ_WRITE_SAFE)
            .setAffinity(new CustomAffinityFunction(false, 32)));

        awaitPartitionMapExchange();

        try {
            executeTask();

            fail("A configuration that does not prove the number of copies must be refused");
        }
        catch (Exception e) {
            assertTrue("IAE must be in cause chain: " + e, X.hasCause(e, IllegalArgumentException.class));

            String msg = X.cause(e, IllegalArgumentException.class).getMessage();

            assertTrue(msg, msg.contains(CACHE_NAME));
            assertTrue(msg, msg.contains("not RendezvousAffinityFunction"));
            assertTrue(msg, msg.contains("--strategy"));
        }
    }

    /**
     * When every cache group is excluded, there is no layout left to protect: the task fails closed instead of
     * printing the whole cluster as one group.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testAllGroupsExcludedFailsClosed() throws Exception {
        startGridsMultiThreaded(3);

        grid(0).createCache(new CacheConfiguration<>(NO_BACKUP_CACHE_NAME)
            .setCacheMode(PARTITIONED)
            .setBackups(0)
            .setPartitionLossPolicy(READ_WRITE_SAFE)
            .setAffinity(new RendezvousAffinityFunction(false, 32)));

        awaitPartitionMapExchange();

        SafeToStopSetsCommandArg arg = new SafeToStopSetsCommandArg();

        arg.noBackup(SafeToStopSetsNoBackupMode.IGNORE);

        try {
            executeTask(arg);

            fail("Expected an operator rejection");
        }
        catch (Exception e) {
            assertTrue("Expected IAE in cause chain: " + e, X.hasCause(e, IllegalArgumentException.class));

            String msg = X.cause(e, IllegalArgumentException.class).getMessage();

            assertTrue(msg, msg.contains("Nothing to compute"));
            assertTrue(msg, msg.contains(NO_BACKUP_CACHE_NAME));
            assertTrue(msg, msg.contains("without --no-backup"));
        }
    }

    /**
     * @param backups Number of backups for the test cache.
     */
    private void createCache(int backups) {
        grid(0).createCache(new CacheConfiguration<>(CACHE_NAME)
            .setCacheMode(PARTITIONED)
            .setBackups(backups)
            .setPartitionLossPolicy(READ_WRITE_SAFE)
            .setAffinity(new RendezvousAffinityFunction(false, 32)));
    }

    /**
     * Invokes the task on the coordinator only (the behavior of the command since 6.1).
     *
     * @return The task result.
     * @throws Exception If failed.
     */
    private SafeToStopSetsTaskResult executeTask() throws Exception {
        return executeTask(new SafeToStopSetsCommandArg());
    }

    /**
     * Invokes the task on the coordinator only (the behavior of the command since 6.1).
     *
     * @param arg Task argument.
     * @return The task result.
     * @throws Exception If failed.
     */
    private SafeToStopSetsTaskResult executeTask(SafeToStopSetsCommandArg arg) throws Exception {
        ClusterNode coordinator = grid(0).cluster().localNode();

        Collection<ClusterNode> nodes = Collections.singleton(coordinator);

        return CommandUtils.execute(null, grid(0), SafeToStopSetsTask.class, arg, nodes);
    }

    /** A subclass of the default affinity: a subclass may change the assignment, so the proof is blocked. */
    private static class CustomAffinityFunction extends RendezvousAffinityFunction {
        /**
         * @param exclNeighbors Exclude neighbors.
         * @param parts Partitions.
         */
        CustomAffinityFunction(boolean exclNeighbors, int parts) {
            super(exclNeighbors, parts);
        }
    }
}
