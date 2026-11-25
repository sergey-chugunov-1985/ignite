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
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.lang.IgniteExperimental;

/**
 * Outcome of the verification performed by {@code SafeToStopSetsSupport.verify}. Immutable.
 * <p>
 * The {@link #mode()} tells the operator what was actually checked: {@code EXACT} — the group list was
 * verified against the actual partition owners; {@code STRUCTURE_ONLY} — the group list was checked for
 * structural correctness (coverage, no duplicates, no empty groups) only, because the context does not
 * carry partition owners (the answer is still safe by construction, but the core could not prove it — the
 * reasons are in {@link #notVerified()}). {@code NOT_READY}, {@code UNSTABLE} and {@code UNPROVABLE} are
 * failures: the command must not print any groups.
 * <p>
 * {@code UNPROVABLE} is a strategy fault, not a cluster fault: a strategy produced a group list without
 * reading partition owners on a context that does not allow a structure-only answer.
 */
@IgniteExperimental
public final class SafeToStopSetsVerification {
    /** What the verification actually checked. */
    public enum Mode {
        /** The group list was verified against the actual partition owners. */
        EXACT,
        /**
         * The group list was checked for structural correctness only. The answer is safe by construction
         * but not proven against the actual layout; see {@link SafeToStopSetsVerification#notVerified()}.
         */
        STRUCTURE_ONLY,
        /** The cluster topology or affinity is not ready; no answer can be formed. */
        NOT_READY,
        /** The topology changed between the context snapshot and the verification; the answer is void. */
        UNSTABLE,
        /**
         * The strategy produced a group list the core cannot accept: either it did not read the partition
         * owners on a context that requires it, or the list is structurally broken (not a partition of the
         * snapshot nodes), or a group holds a whole set of partition owners. A strategy fault, not a cluster
         * fault; the reasons are in {@link #violations()}.
         */
        UNPROVABLE
    }

    /** Whether the group list is safe (or safe by construction) and may be printed. */
    private final boolean valid;

    /** What the verification actually checked. */
    private final Mode mode;

    /** Human readable reasons for a failed or structure-only result; empty when {@link #valid} and mode is {@code EXACT}. */
    private final List<String> violations;

    /** Reasons why the result is {@code STRUCTURE_ONLY} instead of {@code EXACT}; empty otherwise. */
    private final List<String> notVerified;

    /**
     * @param valid Whether the group list is safe (or safe by construction) and can be printed.
     * @param mode What was checked.
     * @param violations Human readable reasons for a failure or the structure-only qualification.
     * @param notVerified Reasons why the answer is {@code STRUCTURE_ONLY} instead of {@code EXACT}.
     */
    public SafeToStopSetsVerification(
        boolean valid,
        Mode mode,
        List<String> violations,
        List<String> notVerified
    ) {
        this.valid = valid;
        this.mode = mode;
        this.violations = Collections.unmodifiableList(violations);
        this.notVerified = Collections.unmodifiableList(notVerified);
    }

    /**
     * @return {@code true} when the group list can be printed to the operator.
     */
    public boolean valid() {
        return valid;
    }

    /**
     * @return What was checked.
     */
    public Mode mode() {
        return mode;
    }

    /**
     * @return Human readable violations; empty when the result is valid and {@link #mode()} is {@code EXACT}.
     */
    public List<String> violations() {
        return violations;
    }

    /**
     * @return Reasons why the result is {@code STRUCTURE_ONLY}; empty otherwise.
     */
    public List<String> notVerified() {
        return notVerified;
    }

    /**
     * @return {@code true} when the command must fail instead of printing groups:
     *         {@link Mode#NOT_READY}, {@link Mode#UNSTABLE} or {@link Mode#UNPROVABLE}.
     */
    public boolean failed() {
        return mode == Mode.NOT_READY || mode == Mode.UNSTABLE || mode == Mode.UNPROVABLE;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(SafeToStopSetsVerification.class, this);
    }
}
