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
import java.util.TreeMap;
import java.util.UUID;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cache.affinity.AffinityFunction;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
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
import org.apache.ignite.lang.IgniteBiPredicate;
import org.jetbrains.annotations.Nullable;

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

    /** Reason why a cache group without backups is excluded from the snapshot on the operator's request. */
    static final String NO_BACKUP_REASON = "has no backups: every partition exists in a single copy";

    /** Ignite instance the snapshot is taken from. */
    private final IgniteEx ignite;

    /** Topology version the snapshot was taken at. */
    private final AffinityTopologyVersion startTopVer;

    /** Server nodes of the snapshot, ordered by consistent ID. */
    private final List<ClusterNode> nodes;

    /** Baseline node IDs of the snapshot. */
    private final List<UUID> baselineNodes;

    /** Options of this run, ordered by key; empty when the operator gave none. */
    private final Map<String, String> strategyOptions;

    /** Cache group configurations, ordered by group name. */
    private final Map<String, CacheGroupInfo> cacheGroups;

    /** Cache group contexts of the snapshot, ordered by group name. */
    private final List<CacheGroupContext> grpCtxs;

    /** Ready affinity assignment of every cache group. */
    private final Map<Integer, AffinityAssignment> affByGrpId;

    /** Size of the affinity node set of every cache group, by group name. */
    private final Map<String, Integer> affinityNodes;

    /** Reasons why the configuration-only proof is not legal, by group name. */
    private final Map<String, String> configOnlyBlockers;

    /** Nodes that are already being stopped gracefully and are therefore excluded from {@link #nodes}. */
    private final List<UUID> gracefulShutdownNodes;

    /**
     * Cache groups that were excluded from the snapshot, mapped to the reason why, ordered by name; empty when
     * nothing was excluded. The answer does not cover these groups.
     */
    private final Map<String, String> excludedGroups;

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
        this(ignite, false, Collections.emptyMap(), null);
    }

    /**
     * @param ignite Ignite instance to take the snapshot from.
     * @param noBackup Whether the cache groups that have no backups leave the snapshot: they then leave every view
     *      of it, so neither the strategies nor the verification see them.
     */
    public SafeToStopSetsContextImpl(IgniteEx ignite, boolean noBackup) {
        this(ignite, noBackup, Collections.emptyMap(), null);
    }

    /**
     * @param ignite Ignite instance to take the snapshot from.
     * @param noBackup Whether the cache groups without backups leave the snapshot: they then leave every view of
     *      it, so neither the strategies nor the verification see them.
     * @param strategyOptions Options of this run, ordered by key; empty when the operator gave none. The
     *      snapshot does not interpret them, it only carries them to
     *      {@link SafeToStopSetsContext#strategyOptions()}.
     * @param strategy Strategy that declares the cache groups it does not cover, {@code null} to cover every
     *      group that is left after the cluster-level exclusions. The declaration is applied before the snapshot
     *      is built, so an excluded group is neither split nor verified.
     */
    public SafeToStopSetsContextImpl(
        IgniteEx ignite,
        boolean noBackup,
        Map<String, String> strategyOptions,
        @Nullable SafeToStopSetsStrategy strategy
    ) {
        this(ignite, collect(ignite, noBackup, strategyOptions, strategy), strategyOptions);
    }

    /**
     * @param ignite Ignite instance.
     * @param snap Collected snapshot data.
     * @param strategyOptions Options of this run, ordered by key.
     */
    private SafeToStopSetsContextImpl(IgniteEx ignite, Snapshot snap, Map<String, String> strategyOptions) {
        this.ignite = ignite;
        this.startTopVer = snap.startTopVer;
        this.nodes = snap.nodes;
        this.baselineNodes = snap.baselineNodes;
        this.strategyOptions = strategyOptions.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(strategyOptions));
        this.cacheGroups = snap.cacheGroups;
        this.grpCtxs = snap.grpCtxs;
        this.affByGrpId = snap.affByGrpId;
        this.affinityNodes = snap.affinityNodes;
        this.configOnlyBlockers = snap.configOnlyBlockers;
        this.gracefulShutdownNodes = snap.gracefulShutdownNodes;
        this.excludedGroups = snap.excludedGroups;
        this.ownersAvailable = snap.ownersAvailable;
        this.reasons = new ArrayList<>(snap.reasons);
        this.notReady = !snap.ownersAvailable;
    }

    /** {@inheritDoc} */
    @Override public String topologyVersion() {
        return startTopVer.toString();
    }

    /** {@inheritDoc} */
    @Override public String currentTopologyVersion() {
        AffinityTopologyVersion cur = ignite.context().discovery().topologyVersionEx();

        return cur == null ? null : cur.toString();
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
    @Override public Map<String, String> strategyOptions() {
        return strategyOptions;
    }

    /** {@inheritDoc} */
    @Override public Map<String, CacheGroupInfo> cacheGroups() {
        return cacheGroups;
    }

    /** {@inheritDoc} */
    @Override public int affinityNodesCount(String groupName) {
        Integer size = affinityNodes.get(groupName);

        if (size == null)
            throw new IllegalArgumentException("Unknown cache group: " + groupName);

        return size;
    }

    /** {@inheritDoc} */
    @Override public Map<String, String> configOnlyBlockers() {
        return configOnlyBlockers;
    }

    /** {@inheritDoc} */
    @Override public boolean partitionOwnersAvailable() {
        return ownersAvailable && !notReady;
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

                if (!top.rebalanceFinished(startTopVer) || top.hasMovingPartitions() || !top.lostPartitions().isEmpty())
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
            if (!startTopVer.equals(ignite.context().discovery().topologyVersionEx()))
                return false;

            for (CacheGroupContext grp : grpCtxs) {
                GridDhtPartitionTopology top = grp.topology();

                if (!top.initialized() || top.readyTopologyVersion().compareTo(startTopVer) < 0)
                    return false;

                if (grp.affinity().lastVersion().compareTo(startTopVer) < 0)
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
        return startTopVer;
    }

    /**
     * @return IDs of the nodes that are already being stopped gracefully, therefore excluded from
     *      {@link #nodes()}; ordered by ID.
     */
    public List<UUID> gracefulShutdownNodes() {
        return gracefulShutdownNodes;
    }

    /**
     * @return Cache groups that were excluded from the snapshot, mapped to the reason why, ordered by name; empty
     *      when nothing was excluded. The answer does not cover these groups: their partitions are not read and
     *      are not part of {@link #partitionOwners()}.
     */
    Map<String, String> excludedCacheGroups() {
        return excludedGroups;
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
        if (!partitionOwnersAvailable())
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
                    if (node.order() > startTopVer.topologyVersion() || !srvIds.contains(node.id()))
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
     * @param noBackup Whether the cache groups without backups leave the snapshot.
     * @param strategyOptions Options of this run, passed to the strategy when it declares the groups it does not
     *      cover.
     * @param strategy Strategy that declares the groups it does not cover, {@code null} to cover every group.
     * @return Collected data, possibly marked as not ready.
     */
    private static Snapshot collect(
        IgniteEx ignite,
        boolean noBackup,
        Map<String, String> strategyOptions,
        @Nullable SafeToStopSetsStrategy strategy
    ) {
        Snapshot snap = new Snapshot();

        AffinityTopologyVersion startTopVer = null;
        DiscoCache disco = null;

        // The topology version and the discovery cache of that version are read as one pair: two
        // topologyVersionEx() calls with the same value around the discoCache() call prove the cache belongs to
        // the captured version.

        for (int i = 0; i < SNAPSHOT_ATTEMPTS; i++) {
            AffinityTopologyVersion ver0 = ignite.context().discovery().topologyVersionEx();
            DiscoCache disco0 = ignite.context().discovery().discoCache();

            if (ver0 != null && disco0 != null && ver0.equals(ignite.context().discovery().topologyVersionEx())) {
                startTopVer = ver0;
                disco = disco0;

                break;
            }
        }

        if (startTopVer == null || !startTopVer.initialized()) {
            snap.reasons.add("Failed to fix the topology version: the topology is changing");

            return snap;
        }

        snap.startTopVer = startTopVer;

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

            if (node.order() > startTopVer.topologyVersion())
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

        // The groups are narrowed in two steps before the snapshot is used. The cluster-level rule comes first: a
        // group without backups gives every partition a single copy, so it forbids stopping any node together
        // with another one, and the operator may exclude such groups. Then the strategy declares the groups its
        // answer does not cover, because only it knows what its split protects.
        //
        // An exclusion removes the group from every view of the snapshot at once: the group infos, the group
        // contexts, the affinity assignments and the configuration blockers. Neither the strategy nor the
        // verification can then see an excluded group, and only its reason survives for the output.

        Map<String, CacheGroupInfo> infos = new LinkedHashMap<>();
        Map<String, String> blockers = new LinkedHashMap<>();

        for (CacheGroupContext grp : grps) {
            String grpName = grp.cacheOrGroupName();

            CacheConfiguration<?, ?> ccfg = grp.config();

            IgniteBiPredicate<ClusterNode, List<ClusterNode>> affBackupFilter = null;

            if (ccfg.getAffinity() instanceof RendezvousAffinityFunction)
                affBackupFilter = ((RendezvousAffinityFunction)ccfg.getAffinity()).getAffinityBackupFilter();

            infos.put(grpName,
                new CacheGroupInfo(grpName, ccfg.getCacheMode(), ccfg.getBackups(),
                    ccfg.getPartitionLossPolicy(), affBackupFilter));

            // A pure function of the configuration, so it is evaluated for every group, ready or not:
            // the configuration path is either legal or not, independent of the topology state.
            String blocker = configOnlyBlocker(ccfg.getAffinity());

            if (blocker != null)
                blockers.put(grpName, blocker);
        }

        Map<String, String> exclGrps = noBackup
            ? noBackupGroups(grps)
            : Collections.<String, String>emptyMap();

        grps = removeExcluded(grps, exclGrps, infos, blockers);

        // The strategy sees the groups the operator left in scope, never the ones the cluster-level rule removed:
        // a group without backups is already outside the answer, so the strategy must not report it.
        if (strategy != null) {
            Map<String, String> declared = SafeToStopSetsSupport.applyExclusions(strategy, strategyOptions, infos);

            if (!declared.isEmpty()) {
                exclGrps = merge(exclGrps, declared);

                grps = removeExcluded(grps, exclGrps, infos, blockers);
            }
        }

        snap.excludedGroups = Collections.unmodifiableMap(new TreeMap<>(exclGrps));

        Map<Integer, AffinityAssignment> affs = new HashMap<>();
        Map<String, Integer> affNodes = new LinkedHashMap<>();

        boolean available = true;

        for (CacheGroupContext grp : grps) {
            String grpName = grp.cacheOrGroupName();

            if (grp.persistenceEnabled() && !baselineSet) {
                snap.reasons.add("Baseline topology is not set although persistence is enabled " +
                    "[group=" + grpName + ']');

                available = false;

                continue;
            }

            if (!groupReady(grp, startTopVer, snap.reasons)) {
                available = false;

                continue;
            }

            try {
                AffinityAssignment aff = grp.affinity().readyAffinity(startTopVer);

                affs.put(grp.groupId(), aff);
                affNodes.put(grpName, aff.nodes().size());
            }
            catch (IllegalStateException e) {
                snap.reasons.add("Affinity is not initialized for the topology version [group=" + grpName +
                    ", startTopVer=" + startTopVer + ']');

                available = false;
            }
        }

        snap.cacheGroups = Collections.unmodifiableMap(infos);
        snap.grpCtxs = Collections.unmodifiableList(grps);
        snap.affByGrpId = Collections.unmodifiableMap(affs);
        snap.affinityNodes = Collections.unmodifiableMap(affNodes);
        snap.configOnlyBlockers = Collections.unmodifiableMap(blockers);
        snap.ownersAvailable = available;

        return snap;
    }

    /**
     * @param grps Cache groups of the snapshot, ordered by name.
     * @return The groups without backups mapped to the reason why they are excluded, ordered by name.
     */
    private static Map<String, String> noBackupGroups(List<CacheGroupContext> grps) {
        Map<String, String> res = new LinkedHashMap<>();

        for (CacheGroupContext grp : grps) {
            if (grp.config().getBackups() == 0)
                res.put(grp.cacheOrGroupName(), NO_BACKUP_REASON);
        }

        return res;
    }

    /**
     * Removes the excluded groups from the views of the snapshot: the group contexts, the group infos and the
     * configuration blockers.
     *
     * @param grps Cache groups of the snapshot, ordered by name.
     * @param excluded Excluded groups mapped to the reason why.
     * @param infos Group infos to narrow in place.
     * @param blockers Configuration blockers to narrow in place.
     * @return The group contexts that remain.
     */
    private static List<CacheGroupContext> removeExcluded(
        List<CacheGroupContext> grps,
        Map<String, String> excluded,
        Map<String, CacheGroupInfo> infos,
        Map<String, String> blockers
    ) {
        if (excluded.isEmpty())
            return grps;

        List<CacheGroupContext> res = new ArrayList<>(grps.size());

        for (CacheGroupContext grp : grps) {
            if (!excluded.containsKey(grp.cacheOrGroupName()))
                res.add(grp);
        }

        for (String grpName : excluded.keySet()) {
            infos.remove(grpName);
            blockers.remove(grpName);
        }

        return res;
    }

    /**
     * @param first Exclusions applied first.
     * @param second Exclusions applied second; the keys do not intersect the keys of {@code first}, because the
     *      strategy sees only the groups {@code first} left in scope.
     * @return The exclusions of both steps.
     */
    private static Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        if (first.isEmpty())
            return second;

        Map<String, String> res = new LinkedHashMap<>(first);

        res.putAll(second);

        return res;
    }

    /**
     * Checks that the DHT topology of a cache group is ready for the given topology version. The check is
     * assertion safe: {@link GridDhtPartitionTopology#readyTopologyVersion()} is called only after
     * {@link GridDhtPartitionTopology#initialized()} returned {@code true}.
     *
     * @param grp Cache group context.
     * @param startTopVer Topology version of the snapshot.
     * @param reasons Diagnostics to append the reason to.
     * @return {@code True} if the group is ready.
     */
    private static boolean groupReady(
        CacheGroupContext grp,
        AffinityTopologyVersion startTopVer,
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

        if (rdyVer.compareTo(startTopVer) < 0) {
            reasons.add("DHT topology is not ready for the snapshot topology version [group=" + grpName +
                ", readyTopVer=" + rdyVer + ", startTopVer=" + startTopVer + ']');

            return false;
        }

        return true;
    }

    /**
     * Checks the configuration-level conditions that make the number of copies of every partition equal to
     * {@code backups + 1}: the affinity must be exactly {@link RendezvousAffinityFunction}, and the copy
     * filters must not drop copies. A filter together with {@code excludeNeighbors} does not block: the
     * fill-up pass of the affinity function reaches the full number of copies. The class is compared with
     * {@code getClass()} rather than {@code instanceof}: a subclass may override the partition
     * assignment, and the guarantee of the affinity function no longer applies.
     *
     * @param aff Affinity function of the group, {@code null} for the default one.
     * @return The blocking reason, or {@code null} if the configuration path is legal.
     */
    private static String configOnlyBlocker(AffinityFunction aff) {
        if (aff == null)
            return null;

        if (aff.getClass() != RendezvousAffinityFunction.class)
            return "affinity is " + aff.getClass().getName() + ", not RendezvousAffinityFunction";

        RendezvousAffinityFunction r = (RendezvousAffinityFunction)aff;

        if (!r.isExcludeNeighbors() && (r.getAffinityBackupFilter() != null || r.getBackupFilter() != null))
            return "copy filters are set without excludeNeighbors";

        return null;
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
        private AffinityTopologyVersion startTopVer = AffinityTopologyVersion.NONE;

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

        /** Size of the affinity node set per cache group, by group name. */
        private Map<String, Integer> affinityNodes = Collections.emptyMap();

        /** Reasons why the configuration-only proof is not legal, by group name. */
        private Map<String, String> configOnlyBlockers = Collections.emptyMap();

        /** Nodes that are already being stopped gracefully. */
        private List<UUID> gracefulShutdownNodes = Collections.emptyList();

        /** Cache groups excluded from the snapshot, by group name, with the reason why. */
        private Map<String, String> excludedGroups = Collections.emptyMap();

        /** Whether the actual partition owners can be read. */
        private boolean ownersAvailable;

        /** Reasons why the snapshot is not usable. */
        private final List<String> reasons = new ArrayList<>();
    }
}
