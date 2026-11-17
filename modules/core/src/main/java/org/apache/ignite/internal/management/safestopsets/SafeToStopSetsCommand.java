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
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.management.SystemViewCommand;
import org.apache.ignite.internal.management.SystemViewTask;
import org.apache.ignite.internal.management.api.CommandUtils;
import org.apache.ignite.internal.management.api.ComputeCommand;

import static java.util.Arrays.asList;
import static java.util.Collections.nCopies;

/**
 * Command that prints groups of server nodes which can be stopped simultaneously without losing data.
 * <p>
 * The command is read-only and advisory: cluster, baseline and cache state are never changed.
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
        return CommandUtils.servers(nodes);
    }

    /** {@inheritDoc} */
    @Override public void printResult(SafeToStopSetsCommandArg arg, SafeToStopSetsTaskResult res, Consumer<String> printer) {
        List<String> titles = asList("Node ID", "Consistent ID", "Host");

        List<SystemViewTask.SimpleType> types = nCopies(titles.size(), SystemViewTask.SimpleType.STRING);

        List<List<?>> rows = res.nodes().stream()
            .map(node -> asList(
                String.valueOf(node.id()),
                String.valueOf(node.consistentId()),
                String.valueOf(node.host())
            ))
            .collect(Collectors.toList());

        printer.accept("Topology version: " + res.topVer());

        SystemViewCommand.printTable(titles, types, rows, printer);
    }
}
