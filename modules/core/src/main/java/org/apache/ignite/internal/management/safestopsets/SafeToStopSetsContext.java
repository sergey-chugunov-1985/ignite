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
 * arguments: the set is expected to grow, and an interface is extended by new {@code default} methods, which
 * keeps already compiled implementations working.
 */
@IgniteExperimental
public interface SafeToStopSetsContext {
    /**
     * @return Topology version the snapshot was taken at; the result is valid only for it.
     */
    String topologyVersion();

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
     * Cache group configurations of the snapshot, ordered by group name. This is the cheapest input: no
     * partition data is needed to build it.
     *
     * @return Cache group name to configuration mapping.
     */
    Map<String, CacheGroupInfo> cacheGroups();

    /**
     * Whether the actual partition owners can be read right now. {@code false} means that the affinity or the
     * DHT topology is not ready (the cluster is not activated, the node is starting, a cache is being started
     * or destroyed), never that the cluster is in-memory. Safety is not provable on a non-ready topology, so
     * the value is terminal for the command.
     *
     * @return {@code True} if {@link #partitionOwners()} can be read.
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
     * snapshot, not a claim of a strategy: the verification uses it to distinguish an answer proved against the
     * actual layout from an answer derived from the configuration only. Reading the affinity or the DHT
     * topology outside of this context does not change the value.
     *
     * @return {@code True} if the owners were read from this snapshot.
     */
    default boolean ownersRead() {
        return false;
    }

    /**
     * Whether the actual ownership of every group already equals its affinity assignment at
     * {@link #topologyVersion()}. This is the only state-level precondition that makes the number of copies
     * equal to {@code backups + 1}: rebalance is enabled, the partition loss policy is not
     * {@code IGNORE}, the rebalance is finished, there are no moving partitions and no lost partitions.
     * <p>
     * The default is {@code false}: an implementation that does not answer must force strategies to read the
     * actual owners.
     *
     * @return {@code True} if the configuration-only derivation of the number of copies is sound.
     */
    default boolean ownershipMatchesAffinity() {
        return false;
    }

    /**
     * Whether the cluster is still at {@link #topologyVersion()} and the affinity of every group is ready for
     * it. The verification checks the value before the answer is produced, because a partial answer or an
     * answer "for the previous topology" is not a valid result.
     * <p>
     * The default is {@code true}, so that synthetic test contexts need no boilerplate.
     *
     * @return {@code False} if the topology version or the readiness of the groups has changed.
     */
    default boolean topologyStable() {
        return true;
    }
}
