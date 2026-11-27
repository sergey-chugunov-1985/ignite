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

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Test-only strategy that declares options and records the ones it was given: the options must travel from the
 * command line to {@link SafeToStopSetsContext#strategyOptions()} unchanged, after the syntax and the names are
 * checked by the core.
 * <p>
 * The split is delegated to the default strategy, so the answer of the run stays valid on a real cluster and the
 * test observes the full task path, not a stub: the recording is the only thing this implementation adds.
 */
public class TestOptionsSafeToStopSetsStrategy implements SafeToStopSetsStrategy {
    /** Strategy name, test-only. */
    public static final String NAME = "test-options";

    /** Options the strategy declares. */
    private static final Set<String> SUPPORTED =
        Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("bar", "baz")));

    /** Options of the last run, recorded before the split. */
    private static volatile Map<String, String> seenOptions;

    /** {@inheritDoc} */
    @Override public String name() {
        return NAME;
    }

    /** {@inheritDoc} */
    @Override public Set<String> supportedOptions() {
        return SUPPORTED;
    }

    /**
     * Records the options of the run, so the test can check that they reached the strategy.
     *
     * @param options Options of this run.
     * @param groups Configurations of the groups in scope.
     * @return Nothing to exclude.
     */
    @Override public Map<String, String> excludeGroups(Map<String, String> options, Map<String, CacheGroupInfo> groups) {
        seenOptions = options;

        return Collections.emptyMap();
    }

    /** {@inheritDoc} */
    @Override public List<List<UUID>> split(SafeToStopSetsContext ctx) {
        return new BackupsCountSafeToStopSetsStrategy().split(ctx);
    }

    /** @return Options of the last run, {@code null} when the strategy was not called. */
    public static Map<String, String> seenOptions() {
        return seenOptions;
    }

    /** Forgets the recorded options. */
    public static void reset() {
        seenOptions = null;
    }
}
