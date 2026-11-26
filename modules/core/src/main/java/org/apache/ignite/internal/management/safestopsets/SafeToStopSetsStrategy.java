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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Safety itself is not the responsibility of the implementation: the result is always verified by the core —
 * against the actual partition owners, or, when the strategy did not read them, against the copy bound of the
 * configuration. A strategy can only affect the quality (group sizes, number of singletons), never the safety.
 * <p>
 * A strategy may skip the owner read only when the configuration allows a configuration-only answer
 * ({@code ctx.configOnlyBlockers()} is empty, {@code ctx.ownershipMatchesAffinity()} is true and
 * {@code ctx.partitionOwnersAvailable()} holds). The core then accepts the result only if every group fits into
 * {@code SafeToStopSetsSupport.maxSafeGroupSize(ctx)}: the copy count of the configuration is the only safety
 * argument available without the layout. A strategy whose argument depends on the placement of the copies (a
 * cell, a data center, an affinity filter) must read the partition owners — the size bound says nothing about
 * where the copies live, and skipping the read would weaken the verification instead of improving the quality.
 * <p>
 * The three conditions are necessary, not sufficient: reading the owners is always allowed and always the
 * stronger option.
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
     * Options the strategy understands, passed by the operator via {@code --strategy-options}. The core checks
     * the given options against this set before the snapshot is taken, so an option that is not declared here
     * is rejected with an {@link IllegalArgumentException} and exit code 1 without any cluster read.
     * <p>
     * The values of the options are never interpreted by the core: they are only checked for syntax and carried
     * to the strategy in {@link SafeToStopSetsContext#strategyOptions()}.
     *
     * @return Names of the supported options, empty when the strategy takes no option.
     */
    default Set<String> supportedOptions() {
        return Collections.emptySet();
    }

    /**
     * Groups the strategy refuses to cover, computed from the options and from the group configurations. The
     * returned map is a declaration, not a modification of the snapshot: the core applies it before the
     * snapshot is built, so the groups leave the snapshot entirely and are neither split nor verified.
     * <p>
     * The invocation happens once, before any affinity or partition data is read, therefore only the
     * configuration of a group is available here: {@link CacheGroupInfo#name()}, {@link CacheGroupInfo#mode()},
     * {@link CacheGroupInfo#backups()}, {@link CacheGroupInfo#lossPolicy()} and
     * {@link CacheGroupInfo#affinityBackupFilter()}. The filter must be a pure function of these arguments and
     * must not depend on the number of affinity nodes or on the placement of the copies — neither is known at
     * this point, and an exclusion that could only be justified by the layout would be a guess.
     * <p>
     * An excluded group is no longer protected by this run: the answer does not cover it, the verification does
     * not check it, and every exclusion is printed to the operator with the reason given here. Excluding a group
     * can therefore turn a rejected answer into an accepted one, which is exactly why the reason must be
     * specific.
     *
     * @param options Options of this run, as given on the command line and validated against
     *      {@link #supportedOptions()}.
     * @param groups Configurations of the groups in scope, ordered by group name.
     * @return Group name to the reason why the group is excluded, empty to keep every group.
     * @throws IllegalArgumentException If the strategy refuses to answer for the given options; the message is
     *      reported to the operator with exit code 1. Any other exception is reported as an internal error (exit
     *      code 4) and must be reserved for genuine faults.
     */
    default Map<String, String> excludeGroups(Map<String, String> options, Map<String, CacheGroupInfo> groups) {
        return Collections.emptyMap();
    }

    /**
     * Called only when the snapshot is usable: {@code ctx.partitionOwnersAvailable()} is true. The result is
     * meaningful only if {@code ctx.topologyStable()} still holds afterwards; the task checks this and fails
     * with a computation error instead of printing groups.
     * <p>
     * An implementation that has not read the partition owners gets a configuration-only verification: every
     * group must fit into {@code SafeToStopSetsSupport.maxSafeGroupSize(ctx)}, otherwise the result is refused
     * as {@code UNPROVABLE}. Only a strategy that reads the owners may exceed the configuration bound, because
     * only the actual layout can justify a bigger group.
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
