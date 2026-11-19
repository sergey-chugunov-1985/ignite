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
import java.util.UUID;
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
 * Task that computes the safe-to-stop sets on the coordinator node.
 * <p>
 * The job is mapped to a single node — the coordinator, selected by the command: it is the first to observe
 * topology changes, so a repeated topology version check on that node is sufficient for the answer to be
 * valid. The pipeline of a job: a read-only cluster snapshot is taken, the strategy is resolved by name,
 * the groups are computed and the result is always verified against the snapshot by
 * {@link SafeToStopSetsSupport}. A failed verification or a snapshot that is not usable fails the job:
 * no partial answer is ever transferred to the client.
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

        ComputeJobResult res = results.get(0);

        if (res.getException() != null)
            throw res.getException();

        SafeToStopSetsTaskResult data = res.getData();

        if (data == null)
            throw new IgniteException("Empty result from node [nodeId=" + res.getNode().id() + ']');

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
            SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(ignite);

            if (!ctx.partitionOwnersAvailable())
                throw new IgniteException("Cannot compute safe-to-stop sets: the cluster is not ready: "
                    + ctx.notReadyReasons());

            SafeToStopSetsStrategy strategy = SafeToStopSetsStrategies.resolve(arg.strategy());

            List<List<UUID>> groups = strategy.split(ctx);

            SafeToStopSetsVerification verification = SafeToStopSetsSupport.verify(groups, ctx);

            if (verification.failed())
                throw new IgniteException("Cannot compute safe-to-stop sets: verification failed ["
                    + verification.mode() + "]: " + verification.violations());

            List<SafeToStopSetsNodeInfo> nodes = ctx.nodes().stream()
                .map(SafeToStopSetsTask::nodeInfo)
                .collect(Collectors.toList());

            return new SafeToStopSetsTaskResult(
                nodes,
                ctx.affinityTopologyVersion().topologyVersion(),
                groups,
                verification.mode().name(),
                verification.notVerified(),
                strategy.name(),
                strategy.version(),
                strategy.getClass().getName(),
                ctx.gracefulShutdownNodes()
            );
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(SafeToStopSetsJob.class, this);
        }
    }
}
