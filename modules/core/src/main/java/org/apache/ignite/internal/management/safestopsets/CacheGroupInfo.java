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

import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cache.PartitionLossPolicy;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.lang.IgniteBiPredicate;

import java.util.List;

/**
 * Immutable per-group cache configuration of a snapshot.
 */
public final class CacheGroupInfo {
    /** Cache group name. */
    private final String name;

    /** Cache mode. */
    private final CacheMode mode;

    /** Number of backups as it is stored in the configuration. */
    private final int backups;

    /** Partition loss policy. */
    private final PartitionLossPolicy lossPolicy;

    /** Affinity backup filter as configured, or {@code null}. */
    private final IgniteBiPredicate<ClusterNode, List<ClusterNode>> affinityBackupFilter;

    /**
     * @param name Cache group name.
     * @param mode Cache mode.
     * @param backups Number of backups as it is stored in the configuration.
     * @param lossPolicy Partition loss policy.
     * @param affinityBackupFilter Affinity backup filter as configured, or {@code null} if absent or the
     *      affinity is not {@code RendezvousAffinityFunction}.
     */
    public CacheGroupInfo(String name, CacheMode mode, int backups, PartitionLossPolicy lossPolicy,
        IgniteBiPredicate<ClusterNode, List<ClusterNode>> affinityBackupFilter) {
        this.name = name;
        this.mode = mode;
        this.backups = backups;
        this.lossPolicy = lossPolicy;
        this.affinityBackupFilter = affinityBackupFilter;
    }

    /**
     * @return Cache group name.
     */
    public String name() {
        return name;
    }

    /**
     * @return Cache mode. Only {@link CacheMode#REPLICATED} and {@link CacheMode#PARTITIONED} are possible.
     */
    public CacheMode mode() {
        return mode;
    }

    /**
     * Number of backups as it is stored in the configuration. For a replicated group it is
     * {@link Integer#MAX_VALUE}, therefore {@code backups + 1} overflows: the number of copies of a replicated
     * group equals the size of its affinity node set, not {@code backups + 1} and not the number of server nodes.
     *
     * @return Configured number of backups.
     */
    public int backups() {
        return backups;
    }

    /**
     * @return Partition loss policy. {@link PartitionLossPolicy#IGNORE} means that the topology does not track
     *      the partition loss, so the actual ownership cannot be assumed to equal the affinity assignment.
     */
    public PartitionLossPolicy lossPolicy() {
        return lossPolicy;
    }

    /**
     * The affinity backup filter as configured via {@code RendezvousAffinityFunction.setAffinityBackupFilter}.
     * The core does not interpret the filter; a strategy that needs to distinguish cache groups by their
     * affinity-to-attribute binding (cell, data center) examines the returned object directly.
     *
     * @return The filter, or {@code null} if it is absent or the affinity is not {@code RendezvousAffinityFunction}.
     */
    public IgniteBiPredicate<ClusterNode, List<ClusterNode>> affinityBackupFilter() {
        return affinityBackupFilter;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(CacheGroupInfo.class, this);
    }
}
