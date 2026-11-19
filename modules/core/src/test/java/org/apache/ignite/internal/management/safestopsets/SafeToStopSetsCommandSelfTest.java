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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.testframework.GridTestNode;
import org.junit.Test;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the operator-facing output of {@link SafeToStopSetsCommand}: the diagnostics header (topology
 * version, strategy identity, verification mode with its reasons), the group listing with singletons
 * separated and the graceful-shutdown block; and the executor selection:
 * the job is mapped to the coordinator, the server node with the lowest order.
 */
public class SafeToStopSetsCommandSelfTest {
    /** Command under test. */
    private final SafeToStopSetsCommand cmd = new SafeToStopSetsCommand();

    /**
     * An {@code exact} result prints the topology version, the strategy identity, the groups in a
     * stable order and the singletons in a separate block; nothing about the graceful shutdown when
     * the kill set is empty.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPrintResultExact() {
        SafeToStopSetsTaskResult res = result(Arrays.asList(Arrays.asList("a", "b"), singleton("c")),
            SafeToStopSetsVerification.Mode.EXACT.name(), Collections.emptyList(), Collections.emptyList());

        List<String> out = print(res);

        assertTrue(out.toString(), out.contains("Topology version: 7"));
        assertTrue(out.toString(), out.contains(
            "Strategy: backups-count [version=n/a, class=" + BackupsCountSafeToStopSetsStrategy.class.getName() + "]"));
        assertTrue(out.toString(), out.contains("Verified: exact"));
        assertTrue(out.toString(), out.contains("Groups (stop the nodes of one group at a time, then re-run this command):"));
        assertTrue(out.toString(), out.contains("  [1] a, b"));
        assertTrue(out.toString(), out.contains("Singletons (cannot be stopped together with any other node without data loss):"));
        assertTrue(out.toString(), out.contains("  c"));
        assertFalse(out.toString(), out.stream().anyMatch(line -> line.contains("stopped gracefully")));
    }

    /**
     * A {@code structure-only} result prints its mode and every reason on its own line.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPrintResultStructureOnly() {
        SafeToStopSetsTaskResult res = result(Arrays.asList(Arrays.asList("a", "b")),
            SafeToStopSetsVerification.Mode.STRUCTURE_ONLY.name(),
            Arrays.asList("The partition owners were not read", "The ownership does not match the affinity"),
            Collections.emptyList());

        List<String> out = print(res);

        assertTrue(out.toString(), out.contains("Verified: structure-only"));
        assertTrue(out.toString(), out.contains("  Reason: The partition owners were not read"));
        assertTrue(out.toString(), out.contains("  Reason: The ownership does not match the affinity"));
    }

    /**
     * Groups are printed ordered by the consistent ID of their first node, independently of the order
     * the task returned them in.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPrintResultGroupsAreOrdered() {
        // The first group holds the nodes with the larger consistent IDs.
        SafeToStopSetsTaskResult res = result(Arrays.asList(Arrays.asList("c", "d"), Arrays.asList("a", "b")),
            SafeToStopSetsVerification.Mode.EXACT.name(), Collections.emptyList(), Collections.emptyList());

        List<String> out = print(res);

        int first = out.indexOf("  [1] a, b");
        int second = out.indexOf("  [2] c, d");

        assertTrue("Group 1 must be the group whose first node has the smallest consistent ID", first >= 0 && first < second);
    }

    /**
     * Nodes being stopped gracefully are printed in a separate block.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPrintResultGracefulShutdownNodes() {
        UUID killSet = UUID.randomUUID();

        SafeToStopSetsTaskResult res = result(Arrays.asList(singleton("a")),
            SafeToStopSetsVerification.Mode.EXACT.name(), Collections.emptyList(), Collections.singletonList(killSet));

        List<String> out = print(res);

        assertTrue(out.toString(), out.contains("Nodes being stopped gracefully (excluded from the computation):"));
        assertTrue(out.toString(), out.contains("  " + killSet));
    }

    /**
     * The job is mapped to a single node: the server node with the lowest order. A client node with a
     * lower order is not eligible, so the answer does not depend on the connection point.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNodesSelectsCoordinator() {
        GridTestNode server0 = server(1L);
        GridTestNode server1 = server(2L);
        GridTestNode client = new GridTestNode(UUID.randomUUID()) {
            /** {@inheritDoc} */
            @Override public boolean isClient() {
                return true;
            }
        };
        client.order(0L);

        Collection<ClusterNode> nodes = cmd.nodes(asList(server0, server1, client), new SafeToStopSetsCommandArg());

        assertEquals(1, nodes.size());
        assertTrue("The coordinator is the server node with the lowest order", nodes.contains(server0));
    }

    /**
     * A topology without server nodes is an operator rejection, not a fallback to the default node.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNodesWithoutServersFails() {
        GridTestNode client = new GridTestNode(UUID.randomUUID()) {
            /** {@inheritDoc} */
            @Override public boolean isClient() {
                return true;
            }
        };

        try {
            cmd.nodes(Collections.singletonList(client), new SafeToStopSetsCommandArg());

            fail("Expected an operator rejection");
        }
        catch (IllegalArgumentException expected) {
            // No-op.
        }
    }

    /**
     * An operator rejection (IllegalArgumentException) wrapped in a client exception is unwrapped: the
     * handler re-throws the inner IAE so the command handler prints the operator message and returns code 1.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testHandleExceptionUnwrapsIae() throws Exception {
        IllegalArgumentException opReject = new IllegalArgumentException("not-ready: cluster is not active");
        RuntimeException wrapped = new RuntimeException("Compute task failed", opReject);

        try {
            cmd.handleException(wrapped, s -> { });

            fail("Expected IllegalArgumentException to be rethrown");
        }
        catch (IllegalArgumentException expected) {
            assertEquals(opReject, expected);
        }
    }

    /**
     * A non-operator exception is rethrown as-is: the command handler prints the generic message and
     * returns code 4.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testHandleExceptionRethrowsOther() throws Exception {
        RuntimeException internal = new RuntimeException("NPE in DHT");

        try {
            cmd.handleException(internal, s -> { });

            fail("Expected the exception to be rethrown");
        }
        catch (RuntimeException expected) {
            assertEquals(internal, expected);
        }
    }

    /**
     * @param order Node order.
     * @return A server test node with that order.
     */
    private static GridTestNode server(long order) {
        GridTestNode node = new GridTestNode(UUID.randomUUID());

        node.order(order);

        return node;
    }

    /**
     * @param res Result to print.
     * @return The lines the command printed.
     */
    private List<String> print(SafeToStopSetsTaskResult res) {
        List<String> out = new ArrayList<>();

        cmd.printResult(new SafeToStopSetsCommandArg(), res, out::add);

        return out;
    }

    /**
     * @param id A consistent ID.
     * @return A singleton group with that ID.
     */
    private static List<String> singleton(String id) {
        return Collections.singletonList(id);
    }

    /**
     * Builds a result: the node of each group gets its consistent ID from the group contents in order.
     *
     * @param groups Groups of consistent IDs (the IDs are synthetic, one per node).
     * @param mode Verification mode.
     * @param notVerified Reasons of the structure-only qualification.
     * @param gracefulShutdownNodes Kill set.
     * @return The result.
     */
    private static SafeToStopSetsTaskResult result(
        List<List<String>> groups,
        String mode,
        List<String> notVerified,
        List<UUID> gracefulShutdownNodes
    ) {
        List<SafeToStopSetsNodeInfo> nodes = new ArrayList<>();
        List<List<UUID>> nodeGroups = new ArrayList<>();

        for (List<String> group : groups) {
            List<UUID> ids = new ArrayList<>(group.size());

            for (String id : group) {
                UUID nodeId = UUID.randomUUID();

                ids.add(nodeId);
                nodes.add(new SafeToStopSetsNodeInfo(nodeId, id, "127.0.0.1"));
            }

            nodeGroups.add(ids);
        }

        return new SafeToStopSetsTaskResult(
            nodes,
            7,
            nodeGroups,
            mode,
            notVerified,
            BackupsCountSafeToStopSetsStrategy.NAME,
            "n/a",
            BackupsCountSafeToStopSetsStrategy.class.getName(),
            gracefulShutdownNodes
        );
    }
}
