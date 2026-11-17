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
import java.util.List;
import java.util.UUID;
import org.apache.ignite.internal.util.typedef.internal.S;

/**
 * Immutable owners of a single partition copy set.
 * <p>
 * The set is the {@code H} of the safety invariant, already restricted to the nodes of the snapshot: the
 * owners that have left the topology or that were excluded from the snapshot are dropped.
 */
public final class PartitionOwners {
    /** Cache group name. */
    private final String grpName;

    /** Partition number. */
    private final int part;

    /** Owner node IDs. */
    private final List<UUID> owners;

    /**
     * @param grpName Cache group name.
     * @param part Partition number.
     * @param owners Owner node IDs, the primary node first.
     */
    public PartitionOwners(String grpName, int part, List<UUID> owners) {
        this.grpName = grpName;
        this.part = part;
        this.owners = Collections.unmodifiableList(new ArrayList<>(owners));
    }

    /**
     * @return Cache group name.
     */
    public String cacheGroupName() {
        return grpName;
    }

    /**
     * @return Partition number.
     */
    public int partition() {
        return part;
    }

    /**
     * @return Read-only list of the owner node IDs, the primary node first.
     */
    public List<UUID> owners() {
        return owners;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(PartitionOwners.class, this);
    }
}
