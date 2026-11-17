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

import java.util.UUID;
import org.apache.ignite.internal.Order;
import org.apache.ignite.internal.dto.IgniteDataTransferObject;
import org.apache.ignite.internal.util.typedef.internal.S;

/**
 * Identity of a server node inside {@link SafeToStopSetsTaskResult}.
 * <p>
 * Only marshallable types are transferred, the {@code ClusterNode} itself never crosses the task boundary.
 */
public class SafeToStopSetsNodeInfo extends IgniteDataTransferObject {
    /** */
    private static final long serialVersionUID = 0L;

    /** Node id. */
    @Order(0)
    UUID id;

    /** Node consistent id. */
    @Order(1)
    String consistentId;

    /** Node host. */
    @Order(2)
    String host;

    /**
     * For externalization only.
     */
    public SafeToStopSetsNodeInfo() {
    }

    /**
     * @param id Node id.
     * @param consistentId Node consistent id.
     * @param host Node host.
     */
    public SafeToStopSetsNodeInfo(UUID id, String consistentId, String host) {
        this.id = id;
        this.consistentId = consistentId;
        this.host = host;
    }

    /** @return Node id. */
    public UUID id() {
        return id;
    }

    /** @return Node consistent id. */
    public String consistentId() {
        return consistentId;
    }

    /** @return Node host. */
    public String host() {
        return host;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SafeToStopSetsNodeInfo.class, this);
    }
}
