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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.cluster.ClusterNode;

/**
 * Test-only strategy that produces a group list without reading the partition owners. It is a
 * deliberately faulty SPI implementation: the verification must reject its result with
 * {@link SafeToStopSetsVerification.Mode#UNPROVABLE} when the context does not allow a configuration-only
 * answer, and no OSS strategy can rely on its output. It exists so the verification's
 * {@code UNPROVABLE} branch is covered by a test without a live cluster.
 */
public class TestOwnersSafeToStopSetsStrategy implements SafeToStopSetsStrategy {
    /** Strategy name, test-only. */
    public static final String NAME = "test-owners";

    /**
     * @return {@value #NAME}.
     */
    @Override public String name() {
        return NAME;
    }

    /**
     * Deals the snapshot nodes round-robin over pairs, without reading the partition owners: the result
     * violates the configuration-only preconditions whenever the context requires the owners.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Pairs of consecutive snapshot nodes.
     */
    @Override public List<List<UUID>> split(SafeToStopSetsContext ctx) {
        List<UUID> ids = new ArrayList<>();

        for (ClusterNode node : ctx.nodes())
            ids.add(node.id());

        List<List<UUID>> res = new ArrayList<>();

        for (int i = 0; i + 1 < ids.size(); i += 2)
            res.add(Arrays.asList(ids.get(i), ids.get(i + 1)));

        if (ids.size() % 2 == 1)
            res.add(Collections.singletonList(ids.get(ids.size() - 1)));

        return res;
    }
}
