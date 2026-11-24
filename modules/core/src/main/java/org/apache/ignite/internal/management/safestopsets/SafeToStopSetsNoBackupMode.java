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

/**
 * What the command does with the cache groups that have no backups ({@code backups == 0}): such a group gives
 * every partition a single copy, so no node may be stopped together with any other node while the group is in
 * the computation, and one such group makes the answer useless.
 * <p>
 * Both modes exclude the groups from the snapshot: the strategy does not see them, their partitions are not
 * read, and their configuration cannot block the answer. The modes differ only in the output: {@link #WARNING}
 * lists the excluded groups, so the operator sees which data the answer does not cover, {@link #IGNORE} stays
 * silent.
 */
public enum SafeToStopSetsNoBackupMode {
    /** Exclude the cache groups without backups from the computation, do not mention them in the output. */
    IGNORE,

    /** Exclude the cache groups without backups from the computation and print them as a separate list. */
    WARNING
}
