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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceConfigurationError;
import org.apache.ignite.IgniteException;
import org.apache.ignite.internal.processors.task.GridInternal;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the {@link SafeToStopSetsStrategy} for the {@code --strategy} command line option.
 * <p>
 * A {@code null} or empty name yields the default {@code backups-count} strategy without touching the
 * classpath. Any other name is resolved by scanning the {@code ServiceLoader} providers registered under
 * {@code META-INF/services/...SafeToStopSetsStrategy} on the server node.
 * <p>
 * Failure semantics are fail-closed and deterministic: an unknown name fails with
 * {@link IllegalArgumentException} (so the operator gets a usage error, not a runtime one) listing the
 * available names; two providers sharing a name fail with {@link IllegalArgumentException} as well,
 * regardless of the requested name; a provider that cannot be loaded or instantiated fails with
 * {@link IgniteException} carrying the provider's fully qualified class name and the cause.
 */
@GridInternal
public final class SafeToStopSetsStrategies {
    /** */
    private SafeToStopSetsStrategies() {
        // No instances.
    }

    /**
     * @param name Strategy name from the command line; {@code null} or empty means the default
     *      {@code backups-count} strategy.
     * @return The resolved strategy.
     * @throws IllegalArgumentException If the name is not {@code null} or empty and no registered strategy
     *      has that name; the message lists the available names. Also thrown when two registered strategies
     *      share a name, whatever the requested name is; the message names both implementations. Both cases
     *      are operator errors of the command line or of the deployment, so the command reports them as a
     *      usage error, not as an internal one.
     * @throws IgniteException If a registered provider cannot be loaded or instantiated; the message
     *      carries the provider's fully qualified class name and the cause.
     */
    public static SafeToStopSetsStrategy resolve(@Nullable String name) {
        if (name == null || name.isEmpty())
            return new BackupsCountSafeToStopSetsStrategy();

        SafeToStopSetsStrategy found = null;
        Map<String, String> byName = new LinkedHashMap<>();

        try {
            // The iterable is lazy: provider classes are loaded and instantiated during iteration, so the
            // load-time errors surface here, not in loadService.
            for (SafeToStopSetsStrategy strategy : U.loadService(SafeToStopSetsStrategy.class)) {
                String strategyName = strategy.name();
                String fqcn = strategy.getClass().getName();
                String previous = byName.put(strategyName, fqcn);

                if (previous != null)
                    throw new IllegalArgumentException(
                        "Duplicate safe-to-stop-sets strategy name [name=" + strategyName + "]: [" + previous
                            + "] and [" + fqcn + "]");

                if (found == null && strategyName.equals(name))
                    found = strategy;
            }
        }
        catch (ServiceConfigurationError | LinkageError e) {
            throw new IgniteException("Failed to load safe-to-stop-sets strategies: " + e.getMessage(), e);
        }

        if (found == null)
            throw new IllegalArgumentException(
                "Unknown safe-to-stop-sets strategy [name=" + name + "], available: " + byName.keySet());

        return found;
    }
}
