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

import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.management.safestopsets.BackupsCountSafeToStopSetsStrategy;
import org.apache.ignite.testframework.GridTestUtils;
import org.junit.Test;

import static org.apache.ignite.cache.CacheMode.PARTITIONED;
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

        createCache(2);

        awaitPartitionMapExchange();

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
     * @param backups Number of backups for the test cache.
     * @throws Exception If failed.
     */
    private void createCache(int backups) throws Exception {
        grid(0).createCache(new CacheConfiguration<>()
            .setName(CACHE_NAME)
            .setCacheMode(PARTITIONED)
            .setBackups(backups)
            .setAffinity(new org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction(false, PARTS)));

        awaitPartitionMapExchange();
    }
}
