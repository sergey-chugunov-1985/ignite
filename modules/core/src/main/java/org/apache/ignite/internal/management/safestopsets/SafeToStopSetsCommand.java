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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.management.api.CommandUtils;
import org.apache.ignite.internal.management.api.ComputeCommand;
import org.apache.ignite.internal.util.typedef.X;

/**
 * Command that prints groups of server nodes which can be stopped simultaneously without losing data.
 * <p>
 * The command is read-only and advisory: cluster, baseline and cache state are never changed.
 * <p>
 * The computation is mapped to a single node — the coordinator, the server node with the lowest
 * {@link ClusterNode#order()}: it is the first to observe topology changes (it drives the exchange),
 * so a single repeated topology version check on that node is sufficient for the answer to be valid.
 */
public class SafeToStopSetsCommand implements ComputeCommand<SafeToStopSetsCommandArg, SafeToStopSetsTaskResult> {
    /** {@inheritDoc} */
    @Override public String description() {
        return "Print groups of server nodes that can be stopped simultaneously without data loss";
    }

    /** {@inheritDoc} */
    @Override public Class<SafeToStopSetsCommandArg> argClass() {
        return SafeToStopSetsCommandArg.class;
    }

    /** {@inheritDoc} */
    @Override public Class<SafeToStopSetsTask> taskClass() {
        return SafeToStopSetsTask.class;
    }

    /** {@inheritDoc} */
    @Override public Collection<ClusterNode> nodes(Collection<ClusterNode> nodes, SafeToStopSetsCommandArg arg) {
        Collection<ClusterNode> coordinator = CommandUtils.coordinatorOrNull(nodes);

        if (coordinator == null)
            throw new IllegalArgumentException("The command requires at least one server node in the topology");

        return coordinator;
    }

    /** {@inheritDoc} */
    @Override public SafeToStopSetsTaskResult handleException(Exception e, Consumer<String> printer) throws Exception {
        if (X.hasCause(e, IllegalArgumentException.class))
            throw X.cause(e, IllegalArgumentException.class);

        throw e;
    }

    /** {@inheritDoc} */
    @Override public void printResult(SafeToStopSetsCommandArg arg, SafeToStopSetsTaskResult res, Consumer<String> printer) {
        printer.accept("Topology version: " + res.topVer());
        printer.accept("Strategy: " + res.strategyName()
            + " [version=" + res.strategyVersion() + ", class=" + res.strategyClass() + "]");

        if (SafeToStopSetsVerification.Mode.EXACT.name().equals(res.mode()))
            printer.accept("Verified: exact");
        else {
            printer.accept("Verified: structure-only");

            for (String reason : res.notVerified())
                printer.accept("  Reason: " + reason);
        }

        Map<UUID, String> consistentId = new HashMap<>();

        for (SafeToStopSetsNodeInfo node : res.nodes())
            consistentId.put(node.id(), node.consistentId());

        List<List<UUID>> groups = new ArrayList<>(res.groups());

        groups.sort(Comparator.comparing(group -> consistentId.get(group.get(0))));

        List<String> groupLines = new ArrayList<>();
        List<String> singletons = new ArrayList<>();

        for (List<UUID> group : groups) {
            if (group.size() == 1) {
                singletons.add(consistentId.get(group.get(0)));
            }
            else {
                List<String> ids = new ArrayList<>(group.size());

                for (UUID id : group)
                    ids.add(consistentId.get(id));

                groupLines.add("  [" + (groupLines.size() + 1) + "] " + String.join(", ", ids));
            }
        }

        if (!groupLines.isEmpty()) {
            printer.accept("Groups (stop the nodes of one group at a time, then re-run this command):");
            groupLines.forEach(printer);
        }

        if (!singletons.isEmpty()) {
            printer.accept("Singletons (cannot be stopped together with any other node without data loss):");
            singletons.forEach(id -> printer.accept("  " + id));
        }

        if (!res.gracefulShutdownNodes().isEmpty()) {
            printer.accept("Nodes being stopped gracefully (excluded from the computation):");

            for (UUID id : res.gracefulShutdownNodes())
                printer.accept("  " + id);
        }
    }
}
