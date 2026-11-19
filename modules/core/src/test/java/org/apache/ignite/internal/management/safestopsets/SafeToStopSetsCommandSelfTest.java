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
import java.util.List;
import java.util.UUID;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests the operator-facing output of {@link SafeToStopSetsCommand}: the diagnostics header (topology
 * version, strategy identity, verification mode with its reasons), the group listing with singletons
 * separated, the all-singletons message and the graceful-shutdown block.
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
     * When every group is a singleton the command does not print a group listing at all, only the
     * explicit message and the singleton block.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testPrintResultAllSingletons() {
        SafeToStopSetsTaskResult res = result(Arrays.asList(singleton("a"), singleton("b")),
            SafeToStopSetsVerification.Mode.EXACT.name(), Collections.emptyList(), Collections.emptyList());

        List<String> out = print(res);

        assertTrue(out.toString(), out.contains("No group of more than one node is safe: every node can be stopped only on its own."));
        assertFalse(out.toString(), out.stream().anyMatch(line -> line.startsWith("Groups")));
        assertTrue(out.toString(), out.contains("  a"));
        assertTrue(out.toString(), out.contains("  b"));
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
