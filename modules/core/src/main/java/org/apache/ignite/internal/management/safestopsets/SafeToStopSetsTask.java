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
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.compute.ComputeJobResult;
import org.apache.ignite.internal.processors.task.GridInternal;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.visor.VisorJob;
import org.apache.ignite.internal.visor.VisorMultiNodeTask;
import org.apache.ignite.plugin.security.SecurityPermissionSet;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.plugin.security.SecurityPermissionSetBuilder.NO_PERMISSIONS;

/**
 * Task that collects the read-only cluster snapshot used by {@link SafeToStopSetsCommand}.
 * <p>
 * A job is mapped to every server node, so that a later version of the command is able to compare the topology
 * version observed by each node. The answer is the snapshot reported by the node with the lowest
 * {@link ClusterNode#order()}, which makes the result independent of the reduce order.
 */
@GridInternal
public class SafeToStopSetsTask
    extends VisorMultiNodeTask<SafeToStopSetsCommandArg, SafeToStopSetsTaskResult, SafeToStopSetsTaskResult> {
    /** */
    private static final long serialVersionUID = 0L;

    /** {@inheritDoc} */
    @Override protected VisorJob<SafeToStopSetsCommandArg, SafeToStopSetsTaskResult> job(SafeToStopSetsCommandArg arg) {
        return new SafeToStopSetsJob(arg, debug);
    }

    /** {@inheritDoc} */
    @Nullable @Override protected SafeToStopSetsTaskResult reduce0(List<ComputeJobResult> results) throws IgniteException {
        if (results.isEmpty())
            throw new IgniteException("Empty job results");

        ComputeJobResult oldest = null;

        for (ComputeJobResult res : results) {
            if (res.getException() != null)
                throw res.getException();

            if (oldest == null || res.getNode().order() < oldest.getNode().order())
                oldest = res;
        }

        SafeToStopSetsTaskResult data = oldest.getData();

        if (data == null)
            throw new IgniteException("Empty result from node [nodeId=" + oldest.getNode().id() + ']');

        return data;
    }

    /**
     * @param node Server node.
     * @return Node identity to transfer to the client.
     */
    private static SafeToStopSetsNodeInfo nodeInfo(ClusterNode node) {
        return new SafeToStopSetsNodeInfo(
            node.id(),
            String.valueOf(node.consistentId()),
            nodeHost(node)
        );
    }

    /**
     * @param node Server node.
     * @return Host of the node, {@code null} if the node reports neither host names nor addresses.
     */
    @Nullable private static String nodeHost(ClusterNode node) {
        Collection<String> hosts = node.hostNames();

        if (F.isEmpty(hosts))
            hosts = node.addresses();

        return F.isEmpty(hosts) ? null : hosts.stream().sorted().findFirst().orElse(null);
    }

    /** */
    private static class SafeToStopSetsJob extends VisorJob<SafeToStopSetsCommandArg, SafeToStopSetsTaskResult> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * @param arg Task argument.
         * @param debug Debug flag.
         */
        private SafeToStopSetsJob(SafeToStopSetsCommandArg arg, boolean debug) {
            super(arg, debug);
        }

        /** {@inheritDoc} */
        @Override public SecurityPermissionSet requiredPermissions() {
            // This task is read-only.
            return NO_PERMISSIONS;
        }

        /** {@inheritDoc} */
        @Override protected SafeToStopSetsTaskResult run(SafeToStopSetsCommandArg arg) {
            long topVer = ignite.cluster().topologyVersion();

            List<SafeToStopSetsNodeInfo> nodes = ignite.cluster().forServers().nodes().stream()
                .map(SafeToStopSetsTask::nodeInfo)
                .sorted(Comparator.comparing(SafeToStopSetsNodeInfo::consistentId))
                .collect(Collectors.toList());

            return new SafeToStopSetsTaskResult(nodes, topVer);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(SafeToStopSetsJob.class, this);
        }
    }
}
