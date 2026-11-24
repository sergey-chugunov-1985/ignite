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
import org.apache.ignite.internal.management.api.EnumDescription;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.jetbrains.annotations.Nullable;

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
     * What to do with the cache groups that have no backups, {@code null} when the groups must stay in the
     * computation.
     */
    @Order(1)
    @Argument(example = "ignore|warning", optional = true,
        description = "What to do with the caches that have no backups: 'ignore' excludes them from the "
            + "computation, 'warning' excludes them and prints the list")
    @EnumDescription(
        names = {
            "IGNORE",
            "WARNING"
        },
        descriptions = {
            "Exclude the caches without backups from the computation without mentioning them",
            "Exclude the caches without backups from the computation and print them as a separate list"
        }
    )
    SafeToStopSetsNoBackupMode noBackup;

    /** @return Name of the strategy. */
    public String strategy() {
        return strategy;
    }

    /** @param strategy Name of the strategy. */
    public void strategy(String strategy) {
        this.strategy = strategy;
    }

    /** @return What to do with the cache groups that have no backups, {@code null} to keep them. */
    public @Nullable SafeToStopSetsNoBackupMode noBackup() {
        return noBackup;
    }

    /** @param noBackup What to do with the cache groups that have no backups, {@code null} to keep them. */
    public void noBackup(@Nullable SafeToStopSetsNoBackupMode noBackup) {
        this.noBackup = noBackup;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SafeToStopSetsCommandArg.class, this);
    }
}
