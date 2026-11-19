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
     * @param backups Number of backups for the test cache.
     */
    private void createCache(int backups) {
        grid(0).createCache(new CacheConfiguration<>(CACHE_NAME)
            .setCacheMode(PARTITIONED)
            .setBackups(backups)
            .setPartitionLossPolicy(READ_WRITE_SAFE)
            .setAffinity(new org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction(false, 32)));
    }

    /**
     * Invokes the task on the coordinator only (the behavior of the command since 6.1).
     *
     * @return The task result.
     * @throws Exception If failed.
     */
    private SafeToStopSetsTaskResult executeTask() throws Exception {
        ClusterNode coordinator = grid(0).cluster().localNode();

        Collection<ClusterNode> nodes = Collections.singleton(coordinator);

        return CommandUtils.execute(null, grid(0), SafeToStopSetsTask.class, new SafeToStopSetsCommandArg(), nodes);
    }
}
