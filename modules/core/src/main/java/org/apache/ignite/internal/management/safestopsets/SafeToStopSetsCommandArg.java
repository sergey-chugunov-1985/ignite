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

import org.apache.ignite.internal.Order;
import org.apache.ignite.internal.dto.IgniteDataTransferObject;
import org.apache.ignite.internal.management.api.Argument;
import org.apache.ignite.internal.util.typedef.internal.S;

/**
 * Argument of {@link SafeToStopSetsCommand}.
 */
public class SafeToStopSetsCommandArg extends IgniteDataTransferObject {
    /** */
    private static final long serialVersionUID = 0L;

    /** Name of the strategy that computes safe-to-stop sets. */
    @Order(0)
    @Argument(example = "backups-count", optional = true,
        description = "Name of the strategy that computes safe-to-stop sets. " +
        "'backups-count' is used by default")
    String strategy;

    /**
     * Whether the cache groups that have no backups leave the computation; they leave every view of the snapshot
     * and are reported with the reason, so the operator sees which data the answer does not cover.
     */
    @Order(1)
    @Argument(optional = true,
        description = "Exclude the cache groups without backups from the computation and report them with the "
            + "reason")
    boolean noBackup;

    /**
     * Options of the strategy, each one a name or a {@code name=value} pair. The core does not interpret the
     * values, it only checks the syntax and that the strategy declares the name in
     * {@link SafeToStopSetsStrategy#supportedOptions()}.
     */
    @Order(2)
    @Argument(example = "option1,option2=value", optional = true,
        description = "Comma separated options of the strategy, each one 'name' or 'name=value'")
    String[] strategyOptions;

    /** @return Name of the strategy. */
    public String strategy() {
        return strategy;
    }

    /** @param strategy Name of the strategy. */
    public void strategy(String strategy) {
        this.strategy = strategy;
    }

    /** @return Whether the cache groups that have no backups leave the computation. */
    public boolean noBackup() {
        return noBackup;
    }

    /** @param noBackup Whether the cache groups that have no backups leave the computation. */
    public void noBackup(boolean noBackup) {
        this.noBackup = noBackup;
    }

    /** @return Options of the strategy as they were given on the command line. */
    public String[] strategyOptions() {
        return strategyOptions;
    }

    /** @param strategyOptions Options of the strategy as given on the command line. */
    public void strategyOptions(String[] strategyOptions) {
        this.strategyOptions = strategyOptions;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SafeToStopSetsCommandArg.class, this);
    }
}
