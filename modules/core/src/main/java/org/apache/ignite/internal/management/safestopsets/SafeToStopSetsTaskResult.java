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
import java.util.UUID;
import org.apache.ignite.internal.Order;
import org.apache.ignite.internal.dto.IgniteDataTransferObject;
import org.apache.ignite.internal.util.typedef.internal.S;

/**
 * Result of {@link SafeToStopSetsTask}.
 * <p>
 * The result is only formed when the verification succeeded ({@code EXACT} or {@code STRUCTURE_ONLY});
 * failed verifications surface as a task error instead of a partial result. It describes the cluster
 * snapshot the answer was computed for (the server nodes and the topology version they were read at),
 * the groups themselves, what the verification actually checked and the strategy that produced the
 * groups.
 */
public class SafeToStopSetsTaskResult extends IgniteDataTransferObject {
    /** */
    private static final long serialVersionUID = 0L;

    /** Server nodes of the snapshot, ordered by consistent id. */
    @Order(0)
    List<SafeToStopSetsNodeInfo> nodes;

    /** Topology version the snapshot was taken at. */
    @Order(1)
    long topVer;

    /** Groups of server node ids: an exact partition of {@link #nodes}, every node in exactly one group. */
    @Order(2)
    List<List<UUID>> groups;

    /**
     * What the verification checked: {@link SafeToStopSetsVerification.Mode#EXACT} or
     * {@link SafeToStopSetsVerification.Mode#STRUCTURE_ONLY} ({@link SafeToStopSetsVerification.Mode}
     * name).
     */
    @Order(3)
    String mode;

    /**
     * Reasons why the result is {@code structure-only} instead of {@code exact}; empty when the mode is
     * {@code exact}.
     */
    @Order(4)
    List<String> notVerified;

    /** Name of the strategy that produced the groups. */
    @Order(5)
    String strategyName;

    /** Fully qualified class name of the strategy implementation. */
    @Order(6)
    String strategyClass;

    /** Nodes planned to stop gracefully (the kill set); excluded from {@link #nodes} and the groups. */
    @Order(7)
    List<UUID> gracefulShutdownNodes;

    /**
     * Cache groups excluded from the computation as groups without backups, ordered by name; empty when the
     * exclusion was not requested or no group matched. The answer does not cover these groups.
     */
    @Order(8)
    List<String> excludedNoBackupGroups;

    /**
     * For externalization only.
     */
    public SafeToStopSetsTaskResult() {
    }

    /**
     * @param nodes Server nodes of the snapshot, ordered by consistent id.
     * @param topVer Topology version the snapshot was taken at.
     */
    public SafeToStopSetsTaskResult(List<SafeToStopSetsNodeInfo> nodes, long topVer) {
        this.nodes = nodes;
        this.topVer = topVer;
    }

    /**
     * @param nodes Server nodes of the snapshot, ordered by consistent id.
     * @param topVer Topology version the snapshot was taken at.
     * @param groups Groups of server node ids.
     * @param mode What the verification checked.
     * @param notVerified Reasons why the result is structure-only; empty for {@code exact}.
     * @param strategyName Name of the strategy that produced the groups.
     * @param strategyClass Fully qualified class name of the strategy implementation.
     * @param gracefulShutdownNodes Nodes planned to stop gracefully.
     * @param excludedNoBackupGroups Cache groups excluded from the computation as groups without backups.
     */
    public SafeToStopSetsTaskResult(
        List<SafeToStopSetsNodeInfo> nodes,
        long topVer,
        List<List<UUID>> groups,
        String mode,
        List<String> notVerified,
        String strategyName,
        String strategyClass,
        List<UUID> gracefulShutdownNodes,
        List<String> excludedNoBackupGroups
    ) {
        this.nodes = nodes;
        this.topVer = topVer;
        this.groups = groups;
        this.mode = mode;
        this.notVerified = notVerified;
        this.strategyName = strategyName;
        this.strategyClass = strategyClass;
        this.gracefulShutdownNodes = gracefulShutdownNodes;
        this.excludedNoBackupGroups = excludedNoBackupGroups;
    }

    /** @return Server nodes of the snapshot, ordered by consistent id. */
    public List<SafeToStopSetsNodeInfo> nodes() {
        return nodes;
    }

    /** @return Topology version the snapshot was taken at. */
    public long topVer() {
        return topVer;
    }

    /** @return Groups of server node ids. */
    public List<List<UUID>> groups() {
        return groups;
    }

    /** @return What the verification checked. */
    public String mode() {
        return mode;
    }

    /** @return Reasons why the result is structure-only; empty for {@code exact}. */
    public List<String> notVerified() {
        return notVerified;
    }

    /** @return Name of the strategy that produced the groups. */
    public String strategyName() {
        return strategyName;
    }

    /** @return Fully qualified class name of the strategy implementation. */
    public String strategyClass() {
        return strategyClass;
    }

    /** @return Nodes planned to stop gracefully. */
    public List<UUID> gracefulShutdownNodes() {
        return gracefulShutdownNodes;
    }

    /** @return Cache groups excluded from the computation as groups without backups, ordered by name. */
    public List<String> excludedNoBackupGroups() {
        return excludedNoBackupGroups;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SafeToStopSetsTaskResult.class, this);
    }
}
