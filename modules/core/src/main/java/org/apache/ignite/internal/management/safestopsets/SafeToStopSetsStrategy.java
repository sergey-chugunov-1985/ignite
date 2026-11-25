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
import org.apache.ignite.lang.IgniteExperimental;

/**
 * Pluggable strategy that splits server nodes into groups which may be stopped simultaneously without losing
 * any partition of any cache.
 * <p>
 * Implementations are resolved on a server node via {@code ServiceLoader}, by {@link #name()}. Implementations
 * must have a public no-arg constructor and must be stateless and deterministic: the same context must always
 * produce the same result.
 * <p>
 * Safety itself is not the responsibility of the implementation: the result is always verified by the core
 * against the actual partition owners. A strategy can only affect the quality (group sizes, number of
 * singletons), never the safety.
 *
 * @see SafeToStopSetsCommandArg#strategy()
 */
@IgniteExperimental
public interface SafeToStopSetsStrategy {
    /** Unique name, referred from the command line option. */
    String name();

    /**
     * @return Human-readable description used in diagnostics.
     */
    default String description() {
        return name();
    }

    /**
     * Called only when the snapshot is usable: {@code ctx.partitionOwnersAvailable()} is true. The result is
     * meaningful only if {@code ctx.topologyStable()} still holds afterwards; the task checks this and fails
     * with a computation error instead of printing groups.
     *
     * @param ctx Immutable read-only snapshot of the cluster state at a fixed topology version.
     * @return Exact partition of {@code ctx.nodes()} into groups: every node appears exactly once, no empty
     *      groups. Groups of size 1 mean "this node cannot be stopped together with anyone".
     * @throws IllegalArgumentException If the strategy refuses to answer for configuration reasons; the
     *      message must list the offending groups and suggest an alternative strategy. Any other exception is
     *      reported as an internal error (exit code 4) and must be reserved for genuine faults.
     */
    List<List<UUID>> split(SafeToStopSetsContext ctx);
}
