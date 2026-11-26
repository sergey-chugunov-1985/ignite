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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.testframework.GridTestNode;

/**
 * Test-only second implementation of the snapshot contract. Strategies are tested against the contract, not
 * against the in-repo snapshot: a strategy that works only with the snapshot's specifics must fail here.
 * <p>
 * The configuration members are fixed at construction; the state members are test seams, so a test sets the
 * situation explicitly instead of reproducing it in a real cluster.
 */
final class TestSafeToStopSetsContext implements SafeToStopSetsContext {
    /** Topology version of the snapshot. */
    private static final String TOP_VER = "1";

    /** Nodes of the snapshot, in the given order. */
    private final List<ClusterNode> nodes;

    /** Group name to configuration mapping. */
    private final Map<String, CacheGroupInfo> groups;

    /** Group name to affinity node set size; a group without an entry defaults to the number of nodes. */
    private final Map<String, Integer> affinityNodes;

    /** Group name to configuration-only blocker reason. */
    private final Map<String, String> blockers = new LinkedHashMap<>();

    /** Owners served by {@link #partitionOwners()}. */
    private final List<PartitionOwners> owners;

    /** Whether the owners can be read. */
    private boolean ownersAvailable = true;

    /** Whether the actual ownership equals the affinity assignment. */
    private boolean ownershipMatches = true;

    /** Whether the topology is still stable. */
    private boolean stable = true;

    /** Current live topology version; a test seam for the job re-check. */
    private String currentVersion = TOP_VER;

    /** Whether {@link #partitionOwners()} was served at least once. */
    private boolean ownersRead;

    /**
     * @param nodeIds Node IDs of the snapshot, in snapshot order.
     * @param groups Group name to configuration mapping.
     * @param affinityNodes Group name to affinity node set size; groups without an entry default to
     *      {@code nodeIds.size()}.
     * @param owners Owners served by {@link #partitionOwners()}, may be empty.
     */
    TestSafeToStopSetsContext(
        List<UUID> nodeIds,
        Map<String, CacheGroupInfo> groups,
        Map<String, Integer> affinityNodes,
        List<PartitionOwners> owners) {
        List<ClusterNode> nodeList = new ArrayList<>(nodeIds.size());

        for (UUID id : nodeIds)
            nodeList.add(new GridTestNode(id));

        this.nodes = Collections.unmodifiableList(nodeList);
        this.groups = Collections.unmodifiableMap(groups);
        this.affinityNodes = Collections.unmodifiableMap(affinityNodes);
        this.owners = Collections.unmodifiableList(owners);
    }

    /**
     * @param available Whether {@link #partitionOwners()} may be read.
     */
    void ownersAvailable(boolean available) {
        this.ownersAvailable = available;
    }

    /**
     * @param matches Whether the actual ownership equals the affinity assignment.
     */
    void ownershipMatchesAffinity(boolean matches) {
        this.ownershipMatches = matches;
    }

    /**
     * @param stable Whether the topology is still stable.
     */
    void topologyStable(boolean stable) {
        this.stable = stable;
    }

    /**
     * @param version Current live topology version, a test seam for the job re-check.
     */
    void currentTopologyVersion(String version) {
        this.currentVersion = version;
    }

    /**
     * @param groupName Group name.
     * @param reason Blocker reason.
     */
    void blocker(String groupName, String reason) {
        blockers.put(groupName, reason);
    }

    /** {@inheritDoc} */
    @Override public String topologyVersion() {
        return TOP_VER;
    }

    /** {@inheritDoc} */
    @Override public String currentTopologyVersion() {
        return currentVersion;
    }

    /** {@inheritDoc} */
    @Override public List<ClusterNode> nodes() {
        return nodes;
    }

    /** {@inheritDoc} */
    @Override public List<UUID> baselineNodes() {
        return nodes.stream().map(ClusterNode::id).collect(Collectors.toList());
    }

    /** {@inheritDoc} */
    @Override public Map<String, CacheGroupInfo> cacheGroups() {
        return groups;
    }

    /** {@inheritDoc} */
    @Override public int affinityNodesCount(String groupName) {
        Integer size = affinityNodes.get(groupName);

        return size == null ? nodes.size() : size;
    }

    /** {@inheritDoc} */
    @Override public Map<String, String> configOnlyBlockers() {
        return Collections.unmodifiableMap(blockers);
    }

    /** {@inheritDoc} */
    @Override public boolean partitionOwnersAvailable() {
        return ownersAvailable;
    }

    /** {@inheritDoc} */
    @Override public List<PartitionOwners> partitionOwners() {
        if (!ownersAvailable)
            throw new IgniteException("Partition owners are not available");

        ownersRead = true;

        return owners;
    }

    /** {@inheritDoc} */
    @Override public boolean ownersRead() {
        return ownersRead;
    }

    /** {@inheritDoc} */
    @Override public boolean ownershipMatchesAffinity() {
        return ownershipMatches;
    }

    /** {@inheritDoc} */
    @Override public boolean topologyStable() {
        return stable;
    }
}
