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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cluster.BaselineNode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.managers.discovery.DiscoCache;
import org.apache.ignite.internal.processors.affinity.AffinityAssignment;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.CacheGroupContext;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cluster.DiscoveryDataClusterState;
import org.apache.ignite.internal.processors.metastorage.DistributedMetaStorage;
import org.apache.ignite.internal.util.typedef.internal.CU;

/**
 * Read-only snapshot of the cluster state taken on a server node.
 * <p>
 * The topology version is fixed once, in the constructor, and every later read (affinity, partition owners,
 * baseline) is performed for it. Nothing is modified in the cluster.
 * <p>
 * The snapshot fails closed: a non-ready affinity or DHT topology, a non-active cluster, an unreadable graceful
 * shutdown set and a partition without owners after the intersection with the snapshot nodes all make
 * {@link #partitionOwnersAvailable()} return {@code false} (or {@link #partitionOwners()} throw) instead of
 * producing a partial answer. {@link #notReadyReasons()} explains what exactly is not ready.
 */
public class SafeToStopSetsContextImpl implements SafeToStopSetsContext {
    /**
     * Key of the metastorage set of nodes that are already being stopped with {@code ShutdownPolicy.GRACEFUL}.
     * <p>
     * The symbolic constant of {@code IgnitionEx} is private, so the key is assembled from the public prefix
     * and the literal, exactly as the graceful shutdown itself does.
     */
    public static final String GRACEFUL_SHUTDOWN_NODES_KEY =
        DistributedMetaStorage.IGNITE_INTERNAL_KEY_PREFIX + "graceful.shutdown";

    /** Maximum number of attempts to read a topology version together with the discovery cache of that version. */
    private static final int SNAPSHOT_ATTEMPTS = 10;

    /** Ignite instance the snapshot is taken from. */
    private final IgniteEx ignite;

    /** Topology version the snapshot was taken at. */
    private final AffinityTopologyVersion topVer;

    /** Server nodes of the snapshot, ordered by consistent ID. */
    private final List<ClusterNode> nodes;

    /** Baseline node IDs of the snapshot. */
    private final List<UUID> baselineNodes;

    /** Cache group configurations, ordered by group name. */
    private final Map<String, CacheGroupInfo> cacheGroups;

    /** Cache group contexts of the snapshot, ordered by group name. */
    private final List<CacheGroupContext> grpCtxs;

    /** Ready affinity assignment of every cache group. */
    private final Map<Integer, AffinityAssignment> affByGrpId;

    /** Nodes that are already being stopped gracefully and are therefore excluded from {@link #nodes}. */
    private final List<UUID> gracefulShutdownNodes;

    /** Whether the actual partition owners can be read. */
    private final boolean ownersAvailable;

    /** Diagnostics of the snapshot, including the reasons why it is not usable. */
    private final List<String> reasons;

    /** Materialized partition owners, {@code null} until read. */
    private volatile List<PartitionOwners> owners;

    /** Whether {@link #partitionOwners()} was materialized in this run. */
    private volatile boolean ownersRead;

    /** Whether a fatal inconsistency was found while reading the owners. */
    private volatile boolean notReady;

    /**
     * @param ignite Ignite instance to take the snapshot from.
     */
    public SafeToStopSetsContextImpl(IgniteEx ignite) {
        this(ignite, collect(ignite));
    }

    /**
     * @param ignite Ignite instance.
     * @param snap Collected snapshot data.
     */
    private SafeToStopSetsContextImpl(IgniteEx ignite, Snapshot snap) {
        this.ignite = ignite;
        this.topVer = snap.topVer;
        this.nodes = snap.nodes;
        this.baselineNodes = snap.baselineNodes;
        this.cacheGroups = snap.cacheGroups;
        this.grpCtxs = snap.grpCtxs;
        this.affByGrpId = snap.affByGrpId;
        this.gracefulShutdownNodes = snap.gracefulShutdownNodes;
        this.ownersAvailable = snap.ownersAvailable;
        this.reasons = new ArrayList<>(snap.reasons);
        this.notReady = !snap.ownersAvailable;
    }

    /** {@inheritDoc} */
    @Override public String topologyVersion() {
        return topVer.toString();
    }

    /** {@inheritDoc} */
    @Override public List<ClusterNode> nodes() {
        return nodes;
    }

    /** {@inheritDoc} */
    @Override public List<UUID> baselineNodes() {
        return baselineNodes;
    }

    /** {@inheritDoc} */
    @Override public Map<String, CacheGroupInfo> cacheGroups() {
        return cacheGroups;
    }

    /** {@inheritDoc} */
    @Override public boolean partitionOwnersAvailable() {
        return ownersAvailable;
    }

    /** {@inheritDoc} */
    @Override public List<PartitionOwners> partitionOwners() {
        List<PartitionOwners> res = owners;

        if (res != null)
            return res;

        synchronized (this) {
            res = owners;

            if (res == null) {
                res = readPartitionOwners();

                owners = res;
                ownersRead = true;
            }
        }

        return res;
    }

    /** {@inheritDoc} */
    @Override public boolean ownersRead() {
        return ownersRead;
    }

    /** {@inheritDoc} */
    @Override public boolean ownershipMatchesAffinity() {
        if (!ownersAvailable)
            return false;

        for (CacheGroupContext grp : grpCtxs) {
            try {
                if (!grp.rebalanceEnabled())
                    return false;

                if (grp.config().getPartitionLossPolicy() == PartitionLossPolicy.IGNORE)
                    return false;

                GridDhtPartitionTopology top = grp.topology();

                if (!top.rebalanceFinished(topVer) || top.hasMovingPartitions() || !top.lostPartitions().isEmpty())
                    return false;
            }
            catch (IllegalStateException e) {
                return false;
            }
        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean topologyStable() {
        try {
            if (!topVer.equals(ignite.context().discovery().topologyVersionEx()))
                return false;

            for (CacheGroupContext grp : grpCtxs) {
                GridDhtPartitionTopology top = grp.topology();

                if (!top.initialized() || top.readyTopologyVersion().compareTo(topVer) < 0)
                    return false;

                if (grp.affinity().lastVersion().compareTo(topVer) < 0)
                    return false;
            }

            return true;
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * @return Topology version the snapshot was taken at.
     */
    public AffinityTopologyVersion affinityTopologyVersion() {
        return topVer;
    }

    /**
     * @return IDs of the nodes that are already being stopped gracefully, therefore excluded from
     *      {@link #nodes()}; ordered by ID.
     */
    public List<UUID> gracefulShutdownNodes() {
        return gracefulShutdownNodes;
    }

    /**
     * @return Reasons why the snapshot is not usable, with the coordinates of the affected groups; empty if the
     *      snapshot is usable.
     */
    public List<String> notReadyReasons() {
        synchronized (this) {
            return Collections.unmodifiableList(new ArrayList<>(reasons));
        }
    }

    /**
     * @return {@code True} if the snapshot cannot be used to compute safe-to-stop sets.
     */
    public boolean notReady() {
        return notReady;
    }

    /**
     * Reads the actual owners of every partition of every cache group.
     * <p>
     * For a replicated group the affinity node set is the whole copy set; for a partitioned group
     * {@link GridDhtPartitionTopology#nodes(int, AffinityAssignment, List)} adds the current extra owners in the
     * {@code OWNING}, {@code MOVING} and {@code RENTING} states, which is exactly what is needed while the
     * topology is changing. The owners of the current discovery cache are intersected with the nodes of the
     * snapshot, so a node that has already left cannot keep a group safe.
     *
     * @return Owned partitions, ordered by group name and partition number.
     */
    private List<PartitionOwners> readPartitionOwners() {
        if (!ownersAvailable)
            throw new IgniteException("Partition owners are not available: " + String.join("; ", notReadyReasons()));

        Set<UUID> srvIds = new HashSet<>();

        for (ClusterNode node : nodes)
            srvIds.add(node.id());

        List<PartitionOwners> res = new ArrayList<>();

        for (CacheGroupContext grp : grpCtxs) {
            String grpName = grp.cacheOrGroupName();

            AffinityAssignment aff = affByGrpId.get(grp.groupId());

            if (aff == null)
                throw new IgniteException("Affinity is not initialized for the cache group [group=" + grpName + ']');

            GridDhtPartitionTopology top = grp.topology();

            List<List<ClusterNode>> assignment = aff.assignment();

            for (int p = 0; p < assignment.size(); p++) {
                List<ClusterNode> affNodes = assignment.get(p);

                List<ClusterNode> responsible = grp.isReplicated() ? affNodes : top.nodes(p, aff, affNodes);

                if (responsible == null)
                    responsible = affNodes;

                LinkedHashSet<UUID> partOwners = new LinkedHashSet<>(responsible.size());

                for (ClusterNode node : responsible) {
                    if (node.order() > topVer.topologyVersion() || !srvIds.contains(node.id()))
                        continue;

                    partOwners.add(node.id());
                }

                if (partOwners.isEmpty()) {
                    String reason = "No owner is left after intersecting the actual owners with the snapshot nodes " +
                        "[group=" + grpName +
                        ", part=" + p +
                        ", affinityNodes=" + affNodes.size() +
                        ", snapshotNodes=" + nodes.size() +
                        ", lostPartitions=" + top.lostPartitions() + ']';

                    markNotReady(reason);

                    throw new IgniteException(reason);
                }

                res.add(new PartitionOwners(grpName, p, new ArrayList<>(partOwners)));
            }
        }

        return Collections.unmodifiableList(res);
    }

    /**
     * @param reason Reason to add to the snapshot diagnostics.
     */
    private void markNotReady(String reason) {
        synchronized (this) {
            reasons.add(reason);

            notReady = true;
        }
    }

    /**
     * Collects the snapshot data. Every failure is recorded in {@link Snapshot#reasons} and leaves the snapshot
     * unusable instead of throwing: the caller decides how to report the reasons.
     *
     * @param ignite Ignite instance.
     * @return Collected data, possibly marked as not ready.
     */
    private static Snapshot collect(IgniteEx ignite) {
        Snapshot snap = new Snapshot();

        AffinityTopologyVersion topVer = null;
        DiscoCache disco = null;

        // The topology version and the discovery cache of that version are read as one pair: two
        // topologyVersionEx() calls with the same value around the discoCache() call prove the cache belongs to
        // the captured version.

        for (int i = 0; i < SNAPSHOT_ATTEMPTS; i++) {
            AffinityTopologyVersion ver0 = ignite.context().discovery().topologyVersionEx();
            DiscoCache disco0 = ignite.context().discovery().discoCache();

            if (ver0 != null && disco0 != null && ver0.equals(ignite.context().discovery().topologyVersionEx())) {
                topVer = ver0;
                disco = disco0;

                break;
            }
        }

        if (topVer == null || !topVer.initialized()) {
            snap.reasons.add("Failed to fix the topology version: the topology is changing");

            return snap;
        }

        snap.topVer = topVer;

        // The affinity of a non-active cluster is not initialized: GridAffinityAssignmentV2 is created with a
        // null assignment and the first get(part) fails. The activation guard is therefore placed before any
        // affinity read.

        try {
            if (!ignite.context().state().publicApiActiveState(true)) {
                snap.reasons.add("Cluster is not active");

                return snap;
            }
        }
        catch (RuntimeException e) {
            snap.reasons.add("Failed to check the cluster state: " + e);

            return snap;
        }

        List<UUID> killSet;

        try {
            killSet = gracefulShutdownNodes(ignite);
        }
        catch (IgniteCheckedException e) {
            snap.reasons.add("Failed to read " + GRACEFUL_SHUTDOWN_NODES_KEY + ": " + e);

            return snap;
        }

        snap.gracefulShutdownNodes = killSet;

        DiscoveryDataClusterState clusterState = disco.state();

        boolean baselineSet = clusterState != null && clusterState.hasBaselineTopology();

        snap.baselineNodes = baselineNodes(disco);

        if (!baselineSet && CU.isPersistenceEnabled(ignite.configuration())) {
            snap.reasons.add("Baseline topology is not set although persistence is enabled");

            return snap;
        }

        List<ClusterNode> srvNodes = new ArrayList<>();

        for (ClusterNode node : disco.serverNodes()) {
            if (killSet.contains(node.id()))
                continue;

            if (node.order() > topVer.topologyVersion())
                continue;

            srvNodes.add(node);
        }

        srvNodes.sort(Comparator.comparing(node -> String.valueOf(node.consistentId())));

        snap.nodes = Collections.unmodifiableList(srvNodes);

        // The command is the group version of ShutdownPolicy.GRACEFUL, which skips the same groups. A system
        // group holds service data (its default partition loss policy is IGNORE), and counting it would both
        // forbid stopping the whole cluster and make the configuration-only proof unreachable.

        List<CacheGroupContext> grps = new ArrayList<>();

        for (CacheGroupContext grp : ignite.context().cache().cacheGroups()) {
            if (!grp.systemCache())
                grps.add(grp);
        }

        grps.sort(Comparator.comparing(CacheGroupContext::cacheOrGroupName));

        Map<String, CacheGroupInfo> infos = new LinkedHashMap<>();
        Map<Integer, AffinityAssignment> affs = new HashMap<>();

        boolean available = true;

        for (CacheGroupContext grp : grps) {
            String grpName = grp.cacheOrGroupName();

            CacheConfiguration<?, ?> ccfg = grp.config();

            infos.put(grpName,
                new CacheGroupInfo(grpName, ccfg.getCacheMode(), ccfg.getBackups(), ccfg.getPartitionLossPolicy()));

            if (grp.persistenceEnabled() && !baselineSet) {
                snap.reasons.add("Baseline topology is not set although persistence is enabled " +
                    "[group=" + grpName + ']');

                available = false;

                continue;
            }

            if (!groupReady(grp, topVer, snap.reasons)) {
                available = false;

                continue;
            }

            try {
                affs.put(grp.groupId(), grp.affinity().readyAffinity(topVer));
            }
            catch (IllegalStateException e) {
                snap.reasons.add("Affinity is not initialized for the topology version [group=" + grpName +
                    ", topVer=" + topVer + ']');

                available = false;
            }
        }

        snap.cacheGroups = Collections.unmodifiableMap(infos);
        snap.grpCtxs = Collections.unmodifiableList(grps);
        snap.affByGrpId = Collections.unmodifiableMap(affs);
        snap.ownersAvailable = available;

        return snap;
    }

    /**
     * Checks that the DHT topology of a cache group is ready for the given topology version. The check is
     * assertion safe: {@link GridDhtPartitionTopology#readyTopologyVersion()} is called only after
     * {@link GridDhtPartitionTopology#initialized()} returned {@code true}.
     *
     * @param grp Cache group context.
     * @param topVer Topology version of the snapshot.
     * @param reasons Diagnostics to append the reason to.
     * @return {@code True} if the group is ready.
     */
    private static boolean groupReady(
        CacheGroupContext grp,
        AffinityTopologyVersion topVer,
        List<String> reasons
    ) {
        String grpName = grp.cacheOrGroupName();

        GridDhtPartitionTopology top;

        try {
            top = grp.topology();
        }
        catch (IllegalStateException e) {
            reasons.add("DHT topology is not initialized [group=" + grpName + ']');

            return false;
        }

        if (!top.initialized()) {
            reasons.add("DHT topology is not initialized [group=" + grpName + ']');

            return false;
        }

        AffinityTopologyVersion rdyVer = top.readyTopologyVersion();

        if (rdyVer.compareTo(topVer) < 0) {
            reasons.add("DHT topology is not ready for the snapshot topology version [group=" + grpName +
                ", readyTopVer=" + rdyVer + ", topVer=" + topVer + ']');

            return false;
        }

        return true;
    }

    /**
     * @param ignite Ignite instance.
     * @return IDs of the nodes that are already being stopped gracefully, ordered by ID.
     * @throws IgniteCheckedException If the metastorage cannot be read. The value is never treated as an empty
     *      set: an unreadable set means that the copies of the stopping nodes would be counted as alive.
     */
    private static List<UUID> gracefulShutdownNodes(IgniteEx ignite) throws IgniteCheckedException {
        HashSet<UUID> ids = ignite.context().distributedMetastorage().read(GRACEFUL_SHUTDOWN_NODES_KEY);

        if (ids == null)
            return Collections.emptyList();

        List<UUID> res = new ArrayList<>(ids);

        Collections.sort(res);

        return Collections.unmodifiableList(res);
    }

    /**
     * @param disco Discovery cache of the snapshot.
     * @return IDs of the baseline nodes present in the snapshot; empty if the baseline topology is not set.
     */
    private static List<UUID> baselineNodes(DiscoCache disco) {
        DiscoveryDataClusterState state = disco.state();
        List<? extends BaselineNode> baselineNodes = disco.baselineNodes();

        if (state == null || !state.hasBaselineTopology() || baselineNodes == null)
            return Collections.emptyList();

        List<UUID> res = new ArrayList<>();

        for (BaselineNode node : baselineNodes) {
            if (node instanceof ClusterNode)
                res.add(((ClusterNode)node).id());
        }

        Collections.sort(res);

        return Collections.unmodifiableList(res);
    }

    /** Mutable holder of the collected snapshot data. */
    private static class Snapshot {
        /** Topology version of the snapshot. */
        private AffinityTopologyVersion topVer = AffinityTopologyVersion.NONE;

        /** Server nodes of the snapshot. */
        private List<ClusterNode> nodes = Collections.emptyList();

        /** Baseline node IDs of the snapshot. */
        private List<UUID> baselineNodes = Collections.emptyList();

        /** Cache group configurations. */
        private Map<String, CacheGroupInfo> cacheGroups = Collections.emptyMap();

        /** Cache group contexts. */
        private List<CacheGroupContext> grpCtxs = Collections.emptyList();

        /** Ready affinity assignment per cache group. */
        private Map<Integer, AffinityAssignment> affByGrpId = Collections.emptyMap();

        /** Nodes that are already being stopped gracefully. */
        private List<UUID> gracefulShutdownNodes = Collections.emptyList();

        /** Whether the actual partition owners can be read. */
        private boolean ownersAvailable;

        /** Reasons why the snapshot is not usable. */
        private final List<String> reasons = new ArrayList<>();
    }
}
