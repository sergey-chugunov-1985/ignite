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
import org.apache.ignite.internal.util.typedef.internal.S;

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

    /**
     * @param name Cache group name.
     * @param mode Cache mode.
     * @param backups Number of backups as it is stored in the configuration.
     * @param lossPolicy Partition loss policy.
     */
    public CacheGroupInfo(String name, CacheMode mode, int backups, PartitionLossPolicy lossPolicy) {
        this.name = name;
        this.mode = mode;
        this.backups = backups;
        this.lossPolicy = lossPolicy;
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

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(CacheGroupInfo.class, this);
    }
}
