package org.apache.ignite.internal.processors.cache;

/**
 * Common predicate type.
 */
public enum CacheEntryPredicateType {
    /**
     * Other custom predicate.
     */
    OTHER,
    /**
     * Entry has certain equal value.
     */
    VALUE,
    /**
     * Entry has any value.
     */
    HAS_VALUE,
    /**
     * Entry has no value.
     */
    HAS_NO_VALUE,
    /**
     * Is always false.
     */
    ALWAYS_FALSE
}
