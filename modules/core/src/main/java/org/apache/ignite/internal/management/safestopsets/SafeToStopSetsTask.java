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
            SafeToStopSetsContextImpl ctx = new SafeToStopSetsContextImpl(ignite, arg.noBackup());

            if (!ctx.partitionOwnersAvailable())
                throw notReady(ctx);

            if (ctx.cacheGroups().isEmpty() && !ctx.excludedNoBackupGroups().isEmpty())
                throw nothingToCompute(ctx);

            SafeToStopSetsStrategy strategy = SafeToStopSetsStrategies.resolve(arg.strategy());

            List<List<UUID>> groups;

            try {
                groups = strategy.split(ctx);
            }
            catch (RuntimeException e) {
                // Reading the owners may reveal a partition that has no owner left (H(p) = ∅). That is the
                // snapshot being not ready, not a computation error: it is already recorded in the reasons.
                if (ctx.notReady())
                    throw notReady(ctx);

                throw e;
            }

            SafeToStopSetsVerification verification = SafeToStopSetsSupport.verify(groups, ctx);

            if (verification.failed())
                throw new IllegalArgumentException("verification failed [" + verification.mode() + "]: "
                    + String.join("; ", verification.violations()));

            String start = ctx.topologyVersion();
            String current = ctx.currentTopologyVersion();

            if (!start.equals(current))
                throw new IllegalArgumentException("unstable: topology changed during computation: "
                    + start + " -> " + current + ", retry");

            if (groups.stream().noneMatch(group -> group.size() > 1))
                throw noSafeGroup(ctx);

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
                strategy.getClass().getName(),
                ctx.gracefulShutdownNodes(),
                ctx.excludedNoBackupGroups()
            );
        }

        /**
         * Operator rejection for the case when every cache group was excluded from the computation: there is no
         * layout left to protect, and printing the whole cluster as one group would invite the operator to stop
         * every node that holds the only copy of its data.
         *
         * @param ctx Snapshot with no groups in scope.
         * @return The operator rejection, exit code 1.
         */
        private static IllegalArgumentException nothingToCompute(SafeToStopSetsContextImpl ctx) {
            return new IllegalArgumentException("Nothing to compute: every cache group was excluded from the "
                + "computation as a group without backups " + ctx.excludedNoBackupGroups()
                + "; re-run the command without --no-backup to compute over every group");
        }

        /**
         * Operator rejection for a snapshot that cannot be used: the message carries the snapshot's reasons,
         * with the coordinates of the affected groups and the lost partitions.
         *
         * @param ctx Not-ready snapshot.
         * @return The operator rejection, exit code 1.
         */
        private static IllegalArgumentException notReady(SafeToStopSetsContextImpl ctx) {
            return new IllegalArgumentException("not-ready: " + String.join("; ", ctx.notReadyReasons()));
        }

        /**
         * Operator rejection for the case when no two nodes may be stopped together: the message carries every
         * node as a singleton and the reason.
         *
         * @param ctx Snapshot whose nodes cannot be grouped.
         * @return The operator rejection, exit code 1.
         */
        private static IllegalArgumentException noSafeGroup(SafeToStopSetsContextImpl ctx) {
            List<String> singletons = new ArrayList<>(ctx.nodes().size());

            for (ClusterNode node : ctx.nodes())
                singletons.add(String.valueOf(node.consistentId()));

            String reason = ctx.nodes().size() == 1
                ? "the cluster has a single server node"
                : "no cache group allows more than one node to be stopped together";

            return new IllegalArgumentException("No safe group of more than one node exists; every node can be "
                + "stopped only on its own: " + singletons + " (" + reason + ')');
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(SafeToStopSetsJob.class, this);
        }
    }
}
