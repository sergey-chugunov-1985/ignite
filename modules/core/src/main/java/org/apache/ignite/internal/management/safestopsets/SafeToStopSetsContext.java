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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.lang.IgniteExperimental;

/**
 * Read-only snapshot of the cluster state that a strategy builds its answer from.
 * <p>
 * The snapshot is taken on a server node at a single topology version, and the answer is valid only for that
 * version: a concurrent node start, node stop or rebalance makes it stale. The snapshot never modifies the
 * cluster and is not transferred between nodes.
 * <p>
 * The whole set of inputs intentionally lives behind this interface instead of being passed as a list of
 * arguments: the interface is the parameter type of the strategy SPI, and the set is expected to grow. The
 * members are abstract on purpose: the snapshot always knows the answer, so a missing input must fail
 * compilation instead of turning into a runtime heuristic that silently degrades the typical run to the
 * expensive path.
 */
@IgniteExperimental
public interface SafeToStopSetsContext {
    /**
     * @return Topology version the snapshot was taken at; the result is valid only for it.
     */
    String topologyVersion();

    /**
     * Current live topology version at the moment of the call, read from the discovery of the executing node.
     * The job compares it with {@link #topologyVersion()} right before producing the answer: a mismatch means
     * the topology changed during the computation and the answer is void. The executing node is the
     * coordinator, the first to observe topology changes, so this check is sufficient.
     *
     * @return Current topology version, {@code null} if it cannot be read.
     */
    String currentTopologyVersion();

    /**
     * Server nodes to split, in deterministic order. The set is computed on the server from its own discovery
     * cache and is reduced by the graceful shutdown kill set: the nodes that are already being stopped are
     * excluded, because their copies are scheduled to disappear.
     *
     * @return Server nodes of the snapshot, ordered by consistent ID.
     */
    List<ClusterNode> nodes();

    /**
     * @return IDs of the baseline nodes present in the snapshot, empty if the baseline topology is not set.
     */
    List<UUID> baselineNodes();

    /**
     * Options of this run, exactly as the operator gave them on the command line: a key is present with its
     * value, and a key without a value is mapped to an empty string. The snapshot does not interpret the
     * values: it only carries them, so a strategy reads its own options and nothing else happens to them.
     * <p>
     * The set of options a strategy accepts is declared by
     * {@link SafeToStopSetsStrategy#supportedOptions()} and checked before the snapshot is taken, so an
     * unsupported option never reaches this point.
     *
     * @return Option key to value mapping, ordered by key; empty when no option was given.
     */
    Map<String, String> strategyOptions();

    /**
     * Cache group configurations of the snapshot, ordered by group name. This is the cheapest input: no
     * partition data is needed to build it.
     * <p>
     * The mapping holds the groups in scope of the answer only: a group that is excluded from the computation
     * (by the operator under {@code --no-backup}, or by the strategy via
     * {@link SafeToStopSetsStrategy#excludeGroups(Map, Map)}) is absent here and in every other view of the
     * snapshot, so a strategy never has to know about the exclusion.
     *
     * @return Cache group name to configuration mapping.
     */
    Map<String, CacheGroupInfo> cacheGroups();

    /**
     * Size of the affinity node set of the group, that is {@code |affinityNodes(group)|} — the upper bound of
     * the number of partition copies of any partition of that group. Cheap: one call per group, no partition
     * data is read, so it never raises {@link #ownersRead()}.
     * <p>
     * This is a <em>configuration-level nominal</em>, not an observation of the layout: it does not shrink
     * when a baseline node leaves, and it does not prove {@code |H| = backups + 1} — that is
     * {@link #ownershipMatchesAffinity()}. For a {@code REPLICATED} group the value is the copy count itself,
     * while {@link CacheGroupInfo#backups()} is {@code Integer.MAX_VALUE}, so {@code backups + 1} must not be
     * computed for it.
     * <p>
     * Abstract on purpose: the snapshot always knows the value, so a missing answer must fail compilation
     * instead of silently degrading the typical run to the expensive path. A group that is not in
     * {@link #cacheGroups()} is a caller bug, not a state of the cluster.
     *
     * @param groupName Name of a group from {@link #cacheGroups()}.
     * @return Size of the affinity node set, at least {@code 1}.
     */
    int affinityNodesCount(String groupName);

    /**
     * Per-group reasons why {@code |H| = backups + 1} is not provable from the configuration, that is why the
     * configuration-level conditions are not met: an affinity class other than
     * {@code RendezvousAffinityFunction}, or copy filters without {@code excludeNeighbors}. An empty map means
     * the configuration path is legal. Cost — one call per group, no partition data is read.
     * <p>
     * The strategy uses the map to refuse with a readable per-group message instead of guessing, and the
     * verification uses it independently to reject a {@code configuration-only} answer that the configuration does
     * not support: the check is duplicated by the core, not delegated to the strategy.
     *
     * @return Group name to reason mapping; empty if every group allows the configuration path.
     */
    Map<String, String> configOnlyBlockers();

    /**
     * Whether the actual partition owners can be read right now. {@code false} means that the affinity or the
     * DHT topology is not ready (the cluster is not activated, the node is starting, a cache is being started
     * or destroyed), never that the cluster is in-memory. Safety is not provable on a non-ready topology, so
     * the value is terminal for the command.
     * <p>
     * The value also turns {@code false} when a read revealed a partition with no owner left: the snapshot is
     * unusable after that, even if the strategy caught the failure of {@link #partitionOwners()} and produced
     * an answer from the configuration. The verification checks this flag first, so a strategy cannot turn an
     * unreadable layout into a {@code configuration-only} answer.
     *
     * @return {@code True} if {@link #partitionOwners()} can be read and no fatal inconsistency was found.
     */
    boolean partitionOwnersAvailable();

    /**
     * Actual partition owners of every cache group at {@link #topologyVersion()}: the primary owner first, the
     * duplicates removed, the result already intersected with {@link #nodes()}. The read is expensive, so the
     * result is cached in the snapshot and reused by the hypergraph, the greedy pass and the verification.
     * <p>
     * The returned list is the source of truth for the safety invariant. An empty owner set of a partition is
     * not a harmless loss: it makes the invariant unsatisfiable for any group, therefore the snapshot refuses
     * to produce it.
     *
     * @return Owned partitions, ordered by group name and partition number.
     * @throws org.apache.ignite.IgniteException If the owners are not available or a partition has no owner left.
     */
    List<PartitionOwners> partitionOwners();

    /**
     * Whether {@link #partitionOwners()} was materialized in this run. This is the observed state of the
     * snapshot, not a claim of a strategy: the verification uses it to distinguish an answer proved against
     * the actual layout from an answer derived from the configuration only. Reading the affinity or the DHT
     * topology outside of this context does not change the value.
     *
     * @return {@code True} if the owners were read from this snapshot.
     */
    boolean ownersRead();

    /**
     * Whether the actual ownership of every group already equals its affinity assignment at
     * {@link #topologyVersion()}. This is the only state-level precondition that makes the number of copies
     * equal to {@code backups + 1}: rebalance is enabled, the partition loss policy is not
     * {@code IGNORE}, the rebalance is finished, there are no moving partitions and no lost partitions.
     *
     * @return {@code True} if the configuration-only derivation of the number of copies is sound.
     */
    boolean ownershipMatchesAffinity();

    /**
     * Whether the cluster is still at {@link #topologyVersion()} and the affinity of every group is ready for
     * it. The verification checks the value before the answer is produced, because a partial answer or an
     * answer "for the previous topology" is not a valid result.
     *
     * @return {@code False} if the topology version or the readiness of the groups has changed.
     */
    boolean topologyStable();
}
