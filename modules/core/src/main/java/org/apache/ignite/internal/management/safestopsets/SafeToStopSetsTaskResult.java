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
import org.apache.ignite.internal.Order;
import org.apache.ignite.internal.dto.IgniteDataTransferObject;
import org.apache.ignite.internal.util.typedef.internal.S;

/**
 * Result of {@link SafeToStopSetsTask}.
 * <p>
 * Describes the cluster snapshot the answer was computed for: the list of server nodes and the topology version they
 * were read at.
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

    /**
     * For externalization only.
     */
    public SafeToStopSetsTaskResult() {
    }

    /**
     * @param nodes Server nodes of the snapshot.
     * @param topVer Topology version the snapshot was taken at.
     */
    public SafeToStopSetsTaskResult(List<SafeToStopSetsNodeInfo> nodes, long topVer) {
        this.nodes = nodes;
        this.topVer = topVer;
    }

    /** @return Server nodes of the snapshot, ordered by consistent id. */
    public List<SafeToStopSetsNodeInfo> nodes() {
        return nodes;
    }

    /** @return Topology version the snapshot was taken at. */
    public long topVer() {
        return topVer;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SafeToStopSetsTaskResult.class, this);
    }
}
