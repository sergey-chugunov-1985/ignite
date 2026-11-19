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

/**
 * Test-only strategy whose constructor always fails, simulating a broken {@code ServiceLoader} provider
 * (a linkage error or an SPI version mismatch). It is deliberately not registered in the shared test
 * service file: {@link SafeToStopSetsStrategiesSelfTest} writes isolated service files that include it,
 * so the broken provider does not break the other resolver scenarios.
 */
public class BrokenSafeToStopSetsStrategy implements SafeToStopSetsStrategy {
    /** Fails on purpose, as a broken provider must. */
    public BrokenSafeToStopSetsStrategy() {
        throw new IllegalStateException("Simulated provider failure");
    }

    /**
     * @return A test-only name, reachable only when the provider can be instantiated (it cannot).
     */
    @Override public String name() {
        return "test-broken";
    }

    /**
     * Never called: the resolver must fail on the broken provider before the strategy is used.
     *
     * @param ctx Snapshot of the cluster state.
     * @return Never returned.
     */
    @Override public List<List<UUID>> split(SafeToStopSetsContext ctx) {
        throw new UnsupportedOperationException("Test strategy, not callable");
    }
}
