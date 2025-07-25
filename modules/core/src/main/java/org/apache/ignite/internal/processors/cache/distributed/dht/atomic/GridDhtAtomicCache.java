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

package org.apache.ignite.internal.processors.cache.distributed.dht.atomic;

import java.io.Externalizable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorResult;
import org.apache.ignite.IgniteCacheRestartingException;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.binary.BinaryInvalidTypeException;
import org.apache.ignite.cache.CacheWriteSynchronizationMode;
import org.apache.ignite.cache.ReadRepairStrategy;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.failure.FailureContext;
import org.apache.ignite.failure.FailureType;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.NodeStoppingException;
import org.apache.ignite.internal.UnregisteredBinaryTypeException;
import org.apache.ignite.internal.UnregisteredClassException;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.mem.IgniteOutOfMemoryException;
import org.apache.ignite.internal.processors.affinity.AffinityAssignment;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.CacheEntryPredicate;
import org.apache.ignite.internal.processors.cache.CacheInvokeEntry;
import org.apache.ignite.internal.processors.cache.CacheInvokeResult;
import org.apache.ignite.internal.processors.cache.CacheLazyEntry;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.CacheOperationContext;
import org.apache.ignite.internal.processors.cache.CacheStoppedException;
import org.apache.ignite.internal.processors.cache.CacheStorePartialUpdateException;
import org.apache.ignite.internal.processors.cache.EntryGetResult;
import org.apache.ignite.internal.processors.cache.GridCacheConcurrentMap;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheEntryEx;
import org.apache.ignite.internal.processors.cache.GridCacheEntryRemovedException;
import org.apache.ignite.internal.processors.cache.GridCacheMapEntry;
import org.apache.ignite.internal.processors.cache.GridCacheOperation;
import org.apache.ignite.internal.processors.cache.GridCacheReturn;
import org.apache.ignite.internal.processors.cache.GridCacheUpdateAtomicResult;
import org.apache.ignite.internal.processors.cache.IgniteCacheExpiryPolicy;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.LockedEntriesInfo;
import org.apache.ignite.internal.processors.cache.binary.CacheObjectBinaryProcessorImpl;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtCacheAdapter;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtCacheEntry;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtFuture;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridPartitionedGetFuture;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridPartitionedSingleGetFuture;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtForceKeysRequest;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtForceKeysResponse;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtInvalidPartitionException;
import org.apache.ignite.internal.processors.cache.distributed.dht.topology.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearAtomicCache;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearCacheAdapter;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearGetRequest;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearGetResponse;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearSingleGetRequest;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearSingleGetResponse;
import org.apache.ignite.internal.processors.cache.distributed.near.consistency.GridNearReadRepairCheckOnlyFuture;
import org.apache.ignite.internal.processors.cache.dr.GridCacheDrExpirationInfo;
import org.apache.ignite.internal.processors.cache.dr.GridCacheDrInfo;
import org.apache.ignite.internal.processors.cache.persistence.CacheDataRow;
import org.apache.ignite.internal.processors.cache.persistence.StorageException;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxLocalEx;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersionConflictContext;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersionEx;
import org.apache.ignite.internal.processors.cacheobject.IgniteCacheObjectProcessor;
import org.apache.ignite.internal.processors.performancestatistics.OperationType;
import org.apache.ignite.internal.processors.timeout.GridTimeoutObject;
import org.apache.ignite.internal.util.GridLongList;
import org.apache.ignite.internal.util.future.GridFinishedFuture;
import org.apache.ignite.internal.util.nio.GridNioBackPressureControl;
import org.apache.ignite.internal.util.nio.GridNioMessageTracker;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.typedef.C1;
import org.apache.ignite.internal.util.typedef.CI1;
import org.apache.ignite.internal.util.typedef.CI2;
import org.apache.ignite.internal.util.typedef.CO;
import org.apache.ignite.internal.util.typedef.CX1;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.lang.IgniteRunnable;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.plugin.security.SecurityPermission;
import org.apache.ignite.thread.IgniteThread;
import org.apache.ignite.transactions.TransactionIsolation;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_ATOMIC_DEFERRED_ACK_BUFFER_SIZE;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_ATOMIC_DEFERRED_ACK_TIMEOUT;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_ASYNC;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.PRIMARY_SYNC;
import static org.apache.ignite.events.EventType.EVT_CACHE_OBJECT_PUT;
import static org.apache.ignite.events.EventType.EVT_CACHE_OBJECT_READ;
import static org.apache.ignite.events.EventType.EVT_CACHE_OBJECT_REMOVED;
import static org.apache.ignite.internal.processors.cache.GridCacheOperation.DELETE;
import static org.apache.ignite.internal.processors.cache.GridCacheOperation.TRANSFORM;
import static org.apache.ignite.internal.processors.cache.GridCacheOperation.UPDATE;
import static org.apache.ignite.internal.processors.cache.GridCacheUtils.isNearEnabled;
import static org.apache.ignite.internal.processors.dr.GridDrType.DR_BACKUP;
import static org.apache.ignite.internal.processors.dr.GridDrType.DR_NONE;
import static org.apache.ignite.internal.processors.dr.GridDrType.DR_PRIMARY;

/**
 * Non-transactional partitioned cache.
 */
@SuppressWarnings({"unchecked", "TooBroadScope"})
@GridToStringExclude
public class GridDhtAtomicCache<K, V> extends GridDhtCacheAdapter<K, V> {
    /** */
    private static final long serialVersionUID = 0L;

    /** @see IgniteSystemProperties#IGNITE_ATOMIC_DEFERRED_ACK_BUFFER_SIZE */
    public static final int DFLT_ATOMIC_DEFERRED_ACK_BUFFER_SIZE = 256;

    /** @see IgniteSystemProperties#IGNITE_ATOMIC_DEFERRED_ACK_TIMEOUT */
    public static final int DFLT_ATOMIC_DEFERRED_ACK_TIMEOUT = 500;

    /** Deferred update response buffer size. */
    private static final int DEFERRED_UPDATE_RESPONSE_BUFFER_SIZE =
        Integer.getInteger(IGNITE_ATOMIC_DEFERRED_ACK_BUFFER_SIZE, DFLT_ATOMIC_DEFERRED_ACK_BUFFER_SIZE);

    /** Deferred update response timeout. */
    private static final int DEFERRED_UPDATE_RESPONSE_TIMEOUT =
        Integer.getInteger(IGNITE_ATOMIC_DEFERRED_ACK_TIMEOUT, DFLT_ATOMIC_DEFERRED_ACK_TIMEOUT);

    /** */
    private final ThreadLocal<Map<UUID, GridDhtAtomicDeferredUpdateResponse>> defRes =
        new ThreadLocal<Map<UUID, GridDhtAtomicDeferredUpdateResponse>>() {
            @Override protected Map<UUID, GridDhtAtomicDeferredUpdateResponse> initialValue() {
                return new HashMap<>();
            }
        };

    /** Locked entries info for each thread. */
    private final LockedEntriesInfo lockedEntriesInfo = new LockedEntriesInfo();

    /** Update reply closure. */
    @GridToStringExclude
    private UpdateReplyClosure updateReplyClos;

    /** */
    private GridNearAtomicCache<K, V> near;

    /** Logger. */
    private IgniteLogger msgLog;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridDhtAtomicCache() {
        // No-op.
    }

    /**
     * @param ctx Cache context.
     */
    public GridDhtAtomicCache(GridCacheContext<K, V> ctx) {
        super(ctx);

        msgLog = ctx.shared().atomicMessageLogger();
    }

    /**
     * @param ctx Cache context.
     * @param map Cache concurrent map.
     */
    public GridDhtAtomicCache(GridCacheContext<K, V> ctx, GridCacheConcurrentMap map) {
        super(ctx, map);

        msgLog = ctx.shared().atomicMessageLogger();
    }

    /** {@inheritDoc} */
    @Override protected void checkJta() throws IgniteCheckedException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public boolean isDhtAtomic() {
        return true;
    }

    /** {@inheritDoc} */
    @Override protected void init() {
        super.init();

        updateReplyClos = new UpdateReplyClosure() {
            @Override public void apply(GridNearAtomicAbstractUpdateRequest req, GridNearAtomicUpdateResponse res) {
                if (req.writeSynchronizationMode() != FULL_ASYNC)
                    sendNearUpdateReply(res.nodeId(), res);
                else {
                    if (res.remapTopologyVersion() != null)
                        // Remap keys on primary node in FULL_ASYNC mode.
                        remapToNewPrimary(req);
                    else if (res.error() != null) {
                        U.error(log, "Failed to process write update request in FULL_ASYNC mode for keys: " +
                            res.failedKeys(), res.error());
                    }
                }
            }
        };
    }

    /** {@inheritDoc} */
    @Override public void onKernalStart() throws IgniteCheckedException {
        super.onKernalStart();

        assert !ctx.isRecoveryMode() : "Registering message handlers in recovery mode [cacheName=" + name() + ']';

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridNearGetRequest.class,
            (CI2<UUID, GridNearGetRequest>)this::processNearGetRequest);

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridNearSingleGetRequest.class,
            (CI2<UUID, GridNearSingleGetRequest>)this::processNearSingleGetRequest);

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridNearAtomicAbstractUpdateRequest.class,
            new CI2<UUID, GridNearAtomicAbstractUpdateRequest>() {
                @Override public void apply(
                    UUID nodeId,
                    GridNearAtomicAbstractUpdateRequest req
                ) {
                    processNearAtomicUpdateRequest(
                        nodeId,
                        req);
                }

                @Override public String toString() {
                    return "GridNearAtomicAbstractUpdateRequest handler " +
                        "[msgIdx=" + GridNearAtomicAbstractUpdateRequest.CACHE_MSG_IDX + ']';
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            AtomicApplicationAttributesAwareRequest.class,
            new CI2<UUID, AtomicApplicationAttributesAwareRequest>() {
                @Override public void apply(
                    UUID nodeId,
                    AtomicApplicationAttributesAwareRequest req
                ) {
                    if (req.applicationAttributes() != null)
                        ctx.operationContextPerCall(new CacheOperationContext().setApplicationAttributes(req.applicationAttributes()));

                    try {
                        processNearAtomicUpdateRequest(nodeId, req.payload());
                    }
                    finally {
                        ctx.operationContextPerCall(null);
                    }
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridNearAtomicUpdateResponse.class,
            new CI2<UUID, GridNearAtomicUpdateResponse>() {
                @Override public void apply(
                    UUID nodeId,
                    GridNearAtomicUpdateResponse res
                ) {
                    processNearAtomicUpdateResponse(
                        nodeId,
                        res);
                }

                @Override public String toString() {
                    return "GridNearAtomicUpdateResponse handler " +
                        "[msgIdx=" + GridNearAtomicUpdateResponse.CACHE_MSG_IDX + ']';
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridDhtAtomicAbstractUpdateRequest.class,
            new CI2<UUID, GridDhtAtomicAbstractUpdateRequest>() {
                @Override public void apply(
                    UUID nodeId,
                    GridDhtAtomicAbstractUpdateRequest req
                ) {
                    processDhtAtomicUpdateRequest(
                        nodeId,
                        req);
                }

                @Override public String toString() {
                    return "GridDhtAtomicUpdateRequest handler " +
                        "[msgIdx=" + GridDhtAtomicUpdateRequest.CACHE_MSG_IDX + ']';
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridDhtAtomicUpdateResponse.class,
            new CI2<UUID, GridDhtAtomicUpdateResponse>() {
                @Override public void apply(
                    UUID nodeId,
                    GridDhtAtomicUpdateResponse res
                ) {
                    processDhtAtomicUpdateResponse(
                        nodeId,
                        res);
                }

                @Override public String toString() {
                    return "GridDhtAtomicUpdateResponse handler " +
                        "[msgIdx=" + GridDhtAtomicUpdateResponse.CACHE_MSG_IDX + ']';
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridDhtAtomicDeferredUpdateResponse.class,
            new CI2<UUID, GridDhtAtomicDeferredUpdateResponse>() {
                @Override public void apply(
                    UUID nodeId,
                    GridDhtAtomicDeferredUpdateResponse res
                ) {
                    processDhtAtomicDeferredUpdateResponse(
                        nodeId,
                        res);
                }

                @Override public String toString() {
                    return "GridDhtAtomicDeferredUpdateResponse handler " +
                        "[msgIdx=" + GridDhtAtomicDeferredUpdateResponse.CACHE_MSG_IDX + ']';
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridDhtAtomicNearResponse.class,
            new CI2<UUID, GridDhtAtomicNearResponse>() {
                @Override public void apply(UUID uuid, GridDhtAtomicNearResponse msg) {
                    processDhtAtomicNearResponse(uuid, msg);
                }

                @Override public String toString() {
                    return "GridDhtAtomicNearResponse handler " +
                        "[msgIdx=" + GridDhtAtomicNearResponse.CACHE_MSG_IDX + ']';
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridNearAtomicCheckUpdateRequest.class,
            new CI2<UUID, GridNearAtomicCheckUpdateRequest>() {
                @Override public void apply(UUID uuid, GridNearAtomicCheckUpdateRequest msg) {
                    processCheckUpdateRequest(uuid, msg);
                }

                @Override public String toString() {
                    return "GridNearAtomicCheckUpdateRequest handler " +
                        "[msgIdx=" + GridNearAtomicCheckUpdateRequest.CACHE_MSG_IDX + ']';
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridDhtForceKeysRequest.class,
            new MessageHandler<GridDhtForceKeysRequest>() {
                @Override public void onMessage(ClusterNode node, GridDhtForceKeysRequest msg) {
                    processForceKeysRequest(node, msg);
                }
            });

        ctx.io().addCacheHandler(
            ctx.cacheId(),
            ctx.startTopologyVersion(),
            GridDhtForceKeysResponse.class,
            new MessageHandler<GridDhtForceKeysResponse>() {
                @Override public void onMessage(ClusterNode node, GridDhtForceKeysResponse msg) {
                    processForceKeyResponse(node, msg);
                }
            });

        if (near == null) {
            ctx.io().addCacheHandler(
                ctx.cacheId(),
                ctx.startTopologyVersion(),
                GridNearGetResponse.class,
                (CI2<UUID, GridNearGetResponse>)this::processNearGetResponse);

            ctx.io().addCacheHandler(
                ctx.cacheId(),
                ctx.startTopologyVersion(),
                GridNearSingleGetResponse.class,
                (CI2<UUID, GridNearSingleGetResponse>)this::processNearSingleGetResponse);
        }
    }

    /** {@inheritDoc} */
    @Override public void start() throws IgniteCheckedException {
        assert metrics != null : "Cache metrics instance isn't initialized.";

        if (ctx.dht().near() != null)
            metrics.delegate(ctx.dht().near().metrics0());
    }

    /**
     * @param near Near cache.
     */
    public void near(GridNearAtomicCache<K, V> near) {
        this.near = near;
    }

    /** {@inheritDoc} */
    @Override public GridNearCacheAdapter<K, V> near() {
        return near;
    }

    /** {@inheritDoc} */
    @Override protected IgniteInternalFuture<V> getAsync(
        final K key,
        final boolean forcePrimary,
        final boolean skipTx,
        final String taskName,
        final boolean deserializeBinary,
        final boolean skipVals,
        final boolean needVer
    ) {
        ctx.checkSecurity(SecurityPermission.CACHE_READ);

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        final ExpiryPolicy expiryPlc = skipVals ? null : opCtx != null ? opCtx.expiry() : null;
        final boolean skipStore = opCtx != null && opCtx.skipStore();
        final boolean recovery = opCtx != null && opCtx.recovery();
        final ReadRepairStrategy readRepairStrategy = opCtx != null ? opCtx.readRepairStrategy() : null;

        return asyncOp(new CO<IgniteInternalFuture<V>>() {
            @Override public IgniteInternalFuture<V> apply() {
                return getAsync0(ctx.toCacheKeyObject(key),
                    forcePrimary,
                    taskName,
                    deserializeBinary,
                    recovery,
                    readRepairStrategy,
                    expiryPlc,
                    skipVals,
                    skipStore,
                    needVer);
            }
        });
    }

    /** {@inheritDoc} */
    @Override protected Map<K, V> getAll(
        Collection<? extends K> keys,
        boolean deserializeBinary,
        boolean needVer,
        boolean recovery,
        ReadRepairStrategy readRepairStrategy) throws IgniteCheckedException {
        return getAllAsyncInternal(keys,
            !ctx.config().isReadFromBackup(),
            ctx.kernalContext().job().currentTaskName(),
            deserializeBinary,
            recovery,
            readRepairStrategy,
            false,
            needVer,
            false).get();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Map<K, V>> getAllAsync(
        @Nullable final Collection<? extends K> keys,
        final boolean forcePrimary,
        boolean skipTx,
        final String taskName,
        final boolean deserializeBinary,
        final boolean recovery,
        final ReadRepairStrategy readRepairStrategy,
        final boolean skipVals,
        final boolean needVer
    ) {
        return getAllAsyncInternal(keys,
            forcePrimary,
            taskName,
            deserializeBinary,
            recovery,
            readRepairStrategy,
            skipVals,
            needVer,
            true);
    }

    /**
     * @param keys Keys.
     * @param forcePrimary Force primary flag.
     * @param taskName Task name.
     * @param deserializeBinary Deserialize binary flag.
     * @param readRepairStrategy Read Repair strategy.
     * @param skipVals Skip values flag.
     * @param needVer Need version flag.
     * @param asyncOp Async operation flag.
     * @return Future.
     */
    private IgniteInternalFuture<Map<K, V>> getAllAsyncInternal(
        @Nullable final Collection<? extends K> keys,
        final boolean forcePrimary,
        final String taskName,
        final boolean deserializeBinary,
        final boolean recovery,
        final ReadRepairStrategy readRepairStrategy,
        final boolean skipVals,
        final boolean needVer,
        boolean asyncOp
    ) {
        ctx.checkSecurity(SecurityPermission.CACHE_READ);

        if (F.isEmpty(keys))
            return new GridFinishedFuture<>(Collections.<K, V>emptyMap());

        warnIfUnordered(keys, BulkOperation.GET);

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        final ExpiryPolicy expiryPlc = skipVals ? null : opCtx != null ? opCtx.expiry() : null;

        final boolean skipStore = opCtx != null && opCtx.skipStore();

        if (asyncOp) {
            return asyncOp(new CO<IgniteInternalFuture<Map<K, V>>>() {
                @Override public IgniteInternalFuture<Map<K, V>> apply() {
                    return getAllAsync0(ctx.cacheKeysView(keys),
                        forcePrimary,
                        taskName,
                        deserializeBinary,
                        recovery,
                        readRepairStrategy,
                        expiryPlc,
                        skipVals,
                        skipStore,
                        needVer);
                }
            });
        }
        else {
            return getAllAsync0(ctx.cacheKeysView(keys),
                forcePrimary,
                taskName,
                deserializeBinary,
                recovery,
                readRepairStrategy,
                expiryPlc,
                skipVals,
                skipStore,
                needVer);
        }
    }

    /** {@inheritDoc} */
    @Override protected V getAndPut0(K key, V val, @Nullable CacheEntryPredicate filter) throws IgniteCheckedException {
        return (V)update0(
            key,
            val,
            null,
            null,
            true,
            filter,
            false).get();
    }

    /** {@inheritDoc} */
    @Override protected boolean put0(K key, V val, CacheEntryPredicate filter) throws IgniteCheckedException {
        Boolean res = (Boolean)update0(
            key,
            val,
            null,
            null,
            false,
            filter,
            false).get();

        assert res != null;

        return res;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public IgniteInternalFuture<V> getAndPutAsync0(K key, V val, @Nullable CacheEntryPredicate filter) {
        return update0(
            key,
            val,
            null,
            null,
            true,
            filter,
            true);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public IgniteInternalFuture<Boolean> putAsync0(K key, V val, @Nullable CacheEntryPredicate filter) {
        return update0(
            key,
            val,
            null,
            null,
            false,
            filter,
            true);
    }

    /** {@inheritDoc} */
    @Override protected void putAll0(Map<? extends K, ? extends V> m) throws IgniteCheckedException {
        updateAll0(
            m.keySet(),
            m.values(),
            null,
            null,
            null,
            null,
            false,
            UPDATE,
            false
        ).get();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<?> putAllAsync0(Map<? extends K, ? extends V> m) {
        return updateAll0(
            m.keySet(),
            m.values(),
            null,
            null,
            null,
            null,
            false,
            UPDATE,
            true
        ).chain(RET2NULL);
    }

    /** {@inheritDoc} */
    @Override public void putAllConflict(Map<KeyCacheObject, GridCacheDrInfo> conflictMap)
        throws IgniteCheckedException {
        putAllConflictAsync(conflictMap).get();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<?> putAllConflictAsync(Map<KeyCacheObject, GridCacheDrInfo> conflictMap) {
        boolean statsEnabled = ctx.statisticsEnabled();
        boolean perfStatsEnabled = ctx.kernalContext().performanceStatistics().enabled();

        long start = (statsEnabled || perfStatsEnabled) ? System.nanoTime() : 0L;

        ctx.dr().onReceiveCacheEntriesReceived(conflictMap.size());

        warnIfUnordered(conflictMap, BulkOperation.PUT);

        IgniteInternalFuture<?> fut = updateAll0(
            conflictMap.keySet(),
            null,
            null,
            null,
            conflictMap.values(),
            null,
            false,
            UPDATE,
            true);

        if (statsEnabled)
            fut.listen(new UpdatePutAllConflictTimeStatClosure<>(metrics0(), start));

        if (perfStatsEnabled)
            fut.listen(() -> writeStatistics(OperationType.CACHE_PUT_ALL_CONFLICT, start));

        return fut;
    }

    /** {@inheritDoc} */
    @Override public V getAndRemove0(K key) throws IgniteCheckedException {
        return (V)remove0(key, true, null, false).get();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public IgniteInternalFuture<V> getAndRemoveAsync0(K key) {
        return remove0(key, true, null, true);
    }

    /** {@inheritDoc} */
    @Override protected void removeAll0(Collection<? extends K> keys) throws IgniteCheckedException {
        removeAllAsync0(keys, null, false, false).get();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<Object> removeAllAsync0(Collection<? extends K> keys) {
        return removeAllAsync0(keys, null, false, true).chain(RET2NULL);
    }

    /** {@inheritDoc} */
    @Override protected boolean remove0(K key, CacheEntryPredicate filter) throws IgniteCheckedException {
        return (Boolean)remove0(key, false, filter, false).get();
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public IgniteInternalFuture<Boolean> removeAsync0(K key, @Nullable CacheEntryPredicate filter) {
        return remove0(key, false, filter, true);
    }

    /** {@inheritDoc} */
    @Override public void removeAllConflict(Map<KeyCacheObject, GridCacheVersion> conflictMap)
        throws IgniteCheckedException {
        removeAllConflictAsync(conflictMap).get();
    }

    /** {@inheritDoc} */
    @Override public IgniteInternalFuture<?> removeAllConflictAsync(Map<KeyCacheObject, GridCacheVersion> conflictMap) {
        final boolean statsEnabled = ctx.statisticsEnabled();
        boolean perfStatsEnabled = ctx.kernalContext().performanceStatistics().enabled();

        final long start = (statsEnabled || perfStatsEnabled) ? System.nanoTime() : 0L;

        ctx.dr().onReceiveCacheEntriesReceived(conflictMap.size());

        IgniteInternalFuture<?> fut = removeAllAsync0(null, conflictMap, false, true);

        if (statsEnabled)
            fut.listen(new UpdateRemoveAllConflictTimeStatClosure<>(metrics0(), start));

        if (perfStatsEnabled)
            fut.listen(() -> writeStatistics(OperationType.CACHE_REMOVE_ALL_CONFLICT, start));

        return fut;
    }

    /**
     * @return {@code True} if store write-through enabled.
     */
    private boolean writeThrough() {
        return ctx.writeThrough() && ctx.store().configured();
    }

    /**
     * @param op Operation closure.
     * @return Future.
     */
    private <T> IgniteInternalFuture<T> asyncOp(final CO<IgniteInternalFuture<T>> op) {
        IgniteInternalFuture<T> fail = asyncOpAcquire(/*retry*/false);

        if (fail != null)
            return fail;

        IgniteInternalFuture<T> f = op.apply();

        f.listen(new CI1<IgniteInternalFuture<?>>() {
            @Override public void apply(IgniteInternalFuture<?> f) {
                asyncOpRelease(/*retry*/false);
            }
        });

        return f;
    }

    /** {@inheritDoc} */
    @Override protected IgniteInternalFuture<Boolean> lockAllAsync(Collection<KeyCacheObject> keys,
        long timeout,
        @Nullable IgniteTxLocalEx tx,
        boolean isInvalidate,
        boolean isRead,
        boolean retval,
        @Nullable TransactionIsolation isolation,
        long createTtl,
        long accessTtl) {
        return new FinishedLockFuture(new UnsupportedOperationException("Locks are not supported for " +
            "CacheAtomicityMode.ATOMIC mode (use CacheAtomicityMode.TRANSACTIONAL instead)"));
    }

    /** {@inheritDoc} */
    @Override public <T> EntryProcessorResult<T> invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... args)
        throws IgniteCheckedException {
        IgniteInternalFuture<EntryProcessorResult<T>> invokeFut = invoke0(false, key, entryProcessor, args);

        return invokeFut.get();
    }

    /** {@inheritDoc} */
    @Override public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
        EntryProcessor<K, V, T> entryProcessor,
        Object... args) throws IgniteCheckedException {

        warnIfUnordered(keys, BulkOperation.INVOKE);

        return invokeAll0(false, keys, entryProcessor, args).get();
    }

    /** {@inheritDoc} */
    @Override public <T> IgniteInternalFuture<EntryProcessorResult<T>> invokeAsync(K key,
        EntryProcessor<K, V, T> entryProcessor,
        Object... args) {
        return invoke0(true, key, entryProcessor, args);
    }

    /**
     * @param async Async operation flag.
     * @param key Key.
     * @param entryProcessor Entry processor.
     * @param args Entry processor arguments.
     * @return Future.
     */
    private <T> IgniteInternalFuture<EntryProcessorResult<T>> invoke0(
        boolean async,
        K key,
        EntryProcessor<K, V, T> entryProcessor,
        Object... args) {
        A.notNull(key, "key", entryProcessor, "entryProcessor");

        final boolean statsEnabled = ctx.statisticsEnabled();

        final long start = statsEnabled ? System.nanoTime() : 0L;

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        final boolean keepBinary = opCtx != null && opCtx.isKeepBinary();

        IgniteInternalFuture<Map<K, EntryProcessorResult<T>>> fut = update0(
            key,
            null,
            entryProcessor,
            args,
            false,
            null,
            async);

        return fut.chain(new CX1<IgniteInternalFuture<Map<K, EntryProcessorResult<T>>>, EntryProcessorResult<T>>() {
            @Override public EntryProcessorResult<T> applyx(IgniteInternalFuture<Map<K, EntryProcessorResult<T>>> fut)
                throws IgniteCheckedException {
                Map<K, EntryProcessorResult<T>> resMap = fut.get();

                if (statsEnabled)
                    metrics0().addInvokeTimeNanos(System.nanoTime() - start);

                if (resMap != null) {
                    assert resMap.isEmpty() || resMap.size() == 1 : resMap.size();

                    EntryProcessorResult<T> res =
                        resMap.isEmpty() ? new CacheInvokeResult<>() : resMap.values().iterator().next();

                    if (res instanceof CacheInvokeResult) {
                        CacheInvokeResult invokeRes = (CacheInvokeResult)res;

                        if (invokeRes.result() != null)
                            res = CacheInvokeResult.fromResult((T)ctx.unwrapBinaryIfNeeded(invokeRes.result(),
                                keepBinary, false, null));
                    }

                    return res;
                }

                return new CacheInvokeResult<>();
            }
        });
    }

    /** {@inheritDoc} */
    @Override public <T> IgniteInternalFuture<Map<K, EntryProcessorResult<T>>> invokeAllAsync(Set<? extends K> keys,
        final EntryProcessor<K, V, T> entryProcessor,
        Object... args) {

        warnIfUnordered(keys, BulkOperation.INVOKE);

        return invokeAll0(true, keys, entryProcessor, args);
    }

    /**
     * @param async Async operation flag.
     * @param keys Keys.
     * @param entryProcessor Entry processor.
     * @param args Entry processor arguments.
     * @return Future.
     */
    private <T> IgniteInternalFuture<Map<K, EntryProcessorResult<T>>> invokeAll0(
        boolean async,
        Set<? extends K> keys,
        final EntryProcessor<K, V, T> entryProcessor,
        Object... args) {
        A.notNull(keys, "keys", entryProcessor, "entryProcessor");

        final boolean statsEnabled = ctx.statisticsEnabled();

        final long start = statsEnabled ? System.nanoTime() : 0L;

        Collection<EntryProcessor<K, V, T>> invokeVals = Collections.nCopies(keys.size(), entryProcessor);

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        final boolean keepBinary = opCtx != null && opCtx.isKeepBinary();

        IgniteInternalFuture<Map<K, EntryProcessorResult<T>>> resFut = updateAll0(
            keys,
            null,
            invokeVals,
            args,
            null,
            null,
            false,
            TRANSFORM,
            async);

        return resFut.chain(
            new CX1<IgniteInternalFuture<Map<K, EntryProcessorResult<T>>>, Map<K, EntryProcessorResult<T>>>() {
                @Override public Map<K, EntryProcessorResult<T>> applyx(
                    IgniteInternalFuture<Map<K, EntryProcessorResult<T>>> fut
                ) throws IgniteCheckedException {
                    Map<Object, EntryProcessorResult> resMap = (Map)fut.get();

                    if (statsEnabled)
                        metrics0().addInvokeTimeNanos(System.nanoTime() - start);

                    return ctx.unwrapInvokeResult(resMap, keepBinary);
                }
            });
    }

    /** {@inheritDoc} */
    @Override public <T> Map<K, EntryProcessorResult<T>> invokeAll(
        Map<? extends K, ? extends EntryProcessor<K, V, T>> map,
        Object... args) throws IgniteCheckedException {
        A.notNull(map, "map");

        warnIfUnordered(map, BulkOperation.INVOKE);

        final boolean statsEnabled = ctx.statisticsEnabled();

        final long start = statsEnabled ? System.nanoTime() : 0L;

        Map<K, EntryProcessorResult<T>> updateResults = (Map<K, EntryProcessorResult<T>>)updateAll0(
            map.keySet(),
            null,
            map.values(),
            args,
            null,
            null,
            false,
            TRANSFORM,
            false).get();

        if (statsEnabled)
            metrics0().addInvokeTimeNanos(System.nanoTime() - start);

        return updateResults;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <T> IgniteInternalFuture<Map<K, EntryProcessorResult<T>>> invokeAllAsync(
        Map<? extends K, ? extends EntryProcessor<K, V, T>> map,
        Object... args) {
        A.notNull(map, "map");

        warnIfUnordered(map, BulkOperation.INVOKE);

        final boolean statsEnabled = ctx.statisticsEnabled();

        final long start = statsEnabled ? System.nanoTime() : 0L;

        IgniteInternalFuture updateResults = updateAll0(
            map.keySet(),
            null,
            map.values(),
            args,
            null,
            null,
            false,
            TRANSFORM,
            true);

        if (statsEnabled)
            updateResults.listen(new InvokeAllTimeStatClosure(metrics0(), start));

        return updateResults;
    }

    /**
     * Entry point for all public API put/transform methods.
     *
     * @param keys Keys.
     * @param vals Put values. Either {@code vals}, {@code invokeVals} or {@code conflictPutVals} should be passed.
     * @param invokeVals Invoke values. Either {@code vals}, {@code invokeVals} or {@code conflictPutVals} should be passed.
     * @param invokeArgs Optional arguments for EntryProcessor.
     * @param conflictPutVals Conflict put values.
     * @param conflictRmvVals Conflict remove values.
     * @param retval Return value required flag.
     * @param async Async operation flag.
     * @return Completion future.
     */
    @SuppressWarnings("ConstantConditions")
    private IgniteInternalFuture updateAll0(
        @Nullable Collection<?> keys,
        @Nullable Collection<? extends V> vals,
        @Nullable Collection<? extends EntryProcessor> invokeVals,
        @Nullable Object[] invokeArgs,
        @Nullable Collection<GridCacheDrInfo> conflictPutVals,
        @Nullable Collection<GridCacheVersion> conflictRmvVals,
        final boolean retval,
        final GridCacheOperation op,
        boolean async
    ) {
        assert ctx.updatesAllowed();

        assert keys != null : keys;

        ctx.checkSecurity(SecurityPermission.CACHE_PUT);

        final CacheOperationContext opCtx = ctx.operationContextPerCall();

        if (opCtx != null && opCtx.hasDataCenterId()) {
            assert conflictPutVals == null : conflictPutVals;
            assert conflictRmvVals == null : conflictRmvVals;

            if (op == GridCacheOperation.TRANSFORM) {
                assert invokeVals != null : invokeVals;

                conflictPutVals = F.viewReadOnly(invokeVals, o -> new GridCacheDrInfo(o, nextVersion(opCtx.dataCenterId())));

                invokeVals = null;
            }
            else if (op == GridCacheOperation.DELETE) {
                assert vals != null : vals;

                conflictRmvVals = F.viewReadOnly(vals, o -> nextVersion(opCtx.dataCenterId()));

                vals = null;
            }
            else {
                assert vals != null : vals;

                conflictPutVals = F.viewReadOnly(vals,
                    o -> new GridCacheDrInfo(ctx.toCacheObject(o), nextVersion(opCtx.dataCenterId())));

                vals = null;
            }
        }

        int taskNameHash = ctx.kernalContext().job().currentTaskNameHash();

        final GridNearAtomicUpdateFuture updateFut = new GridNearAtomicUpdateFuture(
            ctx,
            this,
            ctx.config().getWriteSynchronizationMode(),
            op,
            keys,
            vals != null ? vals : invokeVals,
            invokeArgs,
            conflictPutVals,
            conflictRmvVals,
            retval,
            opCtx != null ? opCtx.expiry() : null,
            CU.filterArray(null),
            taskNameHash,
            opCtx != null && opCtx.skipStore(),
            opCtx != null && opCtx.isKeepBinary(),
            opCtx != null && opCtx.recovery(),
            opCtx != null && opCtx.noRetries() ? 1 : MAX_RETRIES,
            opCtx != null ? opCtx.applicationAttributes() : null);

        if (async) {
            return asyncOp(new CO<IgniteInternalFuture<Object>>() {
                @Override public IgniteInternalFuture<Object> apply() {
                    updateFut.map();

                    return updateFut;
                }
            });
        }
        else {
            updateFut.map();

            return updateFut;
        }
    }

    /**
     * Entry point for update/invoke with a single key.
     *
     * @param key Key.
     * @param val Value.
     * @param proc Entry processor.
     * @param invokeArgs Invoke arguments.
     * @param retval Return value flag.
     * @param filter Filter.
     * @param async Async operation flag.
     * @return Future.
     */
    private IgniteInternalFuture update0(
        K key,
        @Nullable V val,
        @Nullable EntryProcessor proc,
        @Nullable Object[] invokeArgs,
        final boolean retval,
        @Nullable final CacheEntryPredicate filter,
        boolean async
    ) {
        assert val == null || proc == null;

        assert ctx.updatesAllowed();

        ctx.checkSecurity(SecurityPermission.CACHE_PUT);

        final GridNearAtomicAbstractUpdateFuture updateFut =
            createSingleUpdateFuture(key, val, proc, invokeArgs, retval, filter);

        if (async) {
            return asyncOp(new CO<IgniteInternalFuture<Object>>() {
                @Override public IgniteInternalFuture<Object> apply() {
                    updateFut.map();

                    return updateFut;
                }
            });
        }
        else {
            updateFut.map();

            return updateFut;
        }
    }

    /**
     * Entry point for remove with single key.
     *
     * @param key Key.
     * @param retval Whether to return
     * @param filter Filter.
     * @param async Async operation flag.
     * @return Future.
     */
    private IgniteInternalFuture remove0(K key, final boolean retval,
        @Nullable CacheEntryPredicate filter,
        boolean async) {
        assert ctx.updatesAllowed();

        ctx.checkSecurity(SecurityPermission.CACHE_REMOVE);

        final GridNearAtomicAbstractUpdateFuture updateFut = createSingleUpdateFuture(key,
            null,
            null,
            null,
            retval,
            filter);

        if (async) {
            return asyncOp(new CO<IgniteInternalFuture<Object>>() {
                @Override public IgniteInternalFuture<Object> apply() {
                    updateFut.map();

                    return updateFut;
                }
            });
        }
        else {
            updateFut.map();

            return updateFut;
        }
    }

    /**
     * Craete future for single key-val pair update.
     *
     * @param key Key.
     * @param val Value.
     * @param proc Processor.
     * @param invokeArgs Invoke arguments.
     * @param retval Return value flag.
     * @param filter Filter.
     * @return Future.
     */
    private GridNearAtomicAbstractUpdateFuture createSingleUpdateFuture(
        K key,
        @Nullable V val,
        @Nullable EntryProcessor proc,
        @Nullable Object[] invokeArgs,
        boolean retval,
        @Nullable CacheEntryPredicate filter
    ) {
        CacheOperationContext opCtx = ctx.operationContextPerCall();

        GridCacheOperation op;
        Object val0;

        if (val != null) {
            op = UPDATE;
            val0 = val;
        }
        else if (proc != null) {
            op = TRANSFORM;
            val0 = proc;
        }
        else {
            op = DELETE;
            val0 = null;
        }

        GridCacheDrInfo conflictPutVal = null;
        GridCacheVersion conflictRmvVer = null;

        if (opCtx != null && opCtx.hasDataCenterId()) {
            Byte dcId = opCtx.dataCenterId();

            assert dcId != null;

            if (op == UPDATE) {
                conflictPutVal = new GridCacheDrInfo(ctx.toCacheObject(val), nextVersion(dcId));

                val0 = null;
            }
            else if (op == GridCacheOperation.TRANSFORM) {
                conflictPutVal = new GridCacheDrInfo(proc, nextVersion(dcId));

                val0 = null;
            }
            else
                conflictRmvVer = nextVersion(dcId);
        }

        CacheEntryPredicate[] filters = CU.filterArray(filter);

        ReadRepairStrategy readRepairStrategy = opCtx != null ? opCtx.readRepairStrategy() : null;

        // Providing the guarantee that all copies are updated when read repair operation is finished.
        CacheWriteSynchronizationMode syncMode =
            readRepairStrategy != null ? FULL_SYNC : ctx.config().getWriteSynchronizationMode();

        if (conflictPutVal == null && conflictRmvVer == null) {
            return new GridNearAtomicSingleUpdateFuture(
                ctx,
                this,
                syncMode,
                op,
                key,
                val0,
                invokeArgs,
                retval,
                opCtx != null ? opCtx.expiry() : null,
                filters,
                ctx.kernalContext().job().currentTaskNameHash(),
                opCtx != null && opCtx.skipStore(),
                opCtx != null && opCtx.isKeepBinary(),
                opCtx != null && opCtx.recovery(),
                opCtx != null && opCtx.noRetries() ? 1 : MAX_RETRIES,
                opCtx != null ? opCtx.applicationAttributes() : null
            );
        }
        else {
            return new GridNearAtomicUpdateFuture(
                ctx,
                this,
                syncMode,
                op,
                Collections.singletonList(key),
                val0 != null ? Collections.singletonList(val0) : null,
                invokeArgs,
                conflictPutVal != null ? Collections.singleton(conflictPutVal) : null,
                conflictRmvVer != null ? Collections.singleton(conflictRmvVer) : null,
                retval,
                opCtx != null ? opCtx.expiry() : null,
                filters,
                ctx.kernalContext().job().currentTaskNameHash(),
                opCtx != null && opCtx.skipStore(),
                opCtx != null && opCtx.isKeepBinary(),
                opCtx != null && opCtx.recovery(),
                opCtx != null && opCtx.noRetries() ? 1 : MAX_RETRIES,
                opCtx != null ? opCtx.applicationAttributes() : null);
        }
    }

    /**
     * Entry point for all public API remove methods.
     *
     * @param keys Keys to remove.
     * @param conflictMap Conflict map.
     * @param retval Return value required flag.
     * @return Completion future.
     */
    private IgniteInternalFuture removeAllAsync0(
        @Nullable Collection<? extends K> keys,
        @Nullable Map<KeyCacheObject, GridCacheVersion> conflictMap,
        final boolean retval,
        boolean async
    ) {
        assert ctx.updatesAllowed();

        assert keys != null || conflictMap != null;

        ctx.checkSecurity(SecurityPermission.CACHE_REMOVE);

        final CacheOperationContext opCtx = ctx.operationContextPerCall();

        int taskNameHash = ctx.kernalContext().job().currentTaskNameHash();

        Collection<GridCacheVersion> drVers = null;

        if (opCtx != null && keys != null && opCtx.hasDataCenterId()) {
            assert conflictMap == null : conflictMap;

            drVers = F.transform(keys, new C1<K, GridCacheVersion>() {
                @Override public GridCacheVersion apply(K k) {
                    return nextVersion(opCtx.dataCenterId());
                }
            });
        }

        final GridNearAtomicUpdateFuture updateFut = new GridNearAtomicUpdateFuture(
            ctx,
            this,
            ctx.config().getWriteSynchronizationMode(),
            DELETE,
            keys != null ? keys : conflictMap.keySet(),
            null,
            null,
            null,
            drVers != null ? drVers : (keys != null ? null : conflictMap.values()),
            retval,
            opCtx != null ? opCtx.expiry() : null,
            CU.filterArray(null),
            taskNameHash,
            opCtx != null && opCtx.skipStore(),
            opCtx != null && opCtx.isKeepBinary(),
            opCtx != null && opCtx.recovery(),
            opCtx != null && opCtx.noRetries() ? 1 : MAX_RETRIES,
            opCtx != null ? opCtx.applicationAttributes() : null);

        if (async) {
            return asyncOp(new CO<IgniteInternalFuture<Object>>() {
                @Override public IgniteInternalFuture<Object> apply() {
                    updateFut.map();

                    return updateFut;
                }
            });
        }
        else {
            updateFut.map();

            return updateFut;
        }
    }

    /**
     * Entry point to all public API single get methods.
     *
     * @param key Key.
     * @param forcePrimary Force primary flag.
     * @param taskName Task name.
     * @param deserializeBinary Deserialize binary flag.
     * @param readRepairStrategy Read Repair strategy.
     * @param expiryPlc Expiry policy.
     * @param skipVals Skip values flag.
     * @param skipStore Skip store flag.
     * @param needVer Need version.
     * @return Get future.
     */
    private IgniteInternalFuture<V> getAsync0(KeyCacheObject key,
        boolean forcePrimary,
        String taskName,
        boolean deserializeBinary,
        boolean recovery,
        ReadRepairStrategy readRepairStrategy,
        @Nullable ExpiryPolicy expiryPlc,
        boolean skipVals,
        boolean skipStore,
        boolean needVer
    ) {
        AffinityTopologyVersion topVer = ctx.affinity().affinityTopologyVersion();

        IgniteCacheExpiryPolicy expiry = skipVals ? null : expiryPolicy(expiryPlc);

        if (readRepairStrategy != null) {
            return new GridNearReadRepairCheckOnlyFuture(
                topVer,
                ctx,
                Collections.singleton(ctx.toCacheKeyObject(key)),
                readRepairStrategy,
                !skipStore,
                taskName,
                deserializeBinary,
                recovery,
                expiry,
                skipVals,
                needVer,
                false,
                null).single();
        }

        GridPartitionedSingleGetFuture fut = new GridPartitionedSingleGetFuture(ctx,
            key,
            topVer,
            !skipStore,
            forcePrimary,
            taskName,
            deserializeBinary,
            expiry,
            skipVals,
            needVer,
            false,
            recovery,
            null);

        fut.init();

        return (IgniteInternalFuture<V>)fut;
    }

    /**
     * Entry point to all public API get methods.
     *
     * @param keys Keys.
     * @param forcePrimary Force primary flag.
     * @param taskName Task name.
     * @param deserializeBinary Deserialize binary flag.
     * @param expiryPlc Expiry policy.
     * @param skipVals Skip values flag.
     * @param skipStore Skip store flag.
     * @param needVer Need version.
     * @return Get future.
     */
    private IgniteInternalFuture<Map<K, V>> getAllAsync0(@Nullable Collection<KeyCacheObject> keys,
        boolean forcePrimary,
        String taskName,
        boolean deserializeBinary,
        boolean recovery,
        ReadRepairStrategy readRepairStrategy,
        @Nullable ExpiryPolicy expiryPlc,
        boolean skipVals,
        boolean skipStore,
        boolean needVer
    ) {
        AffinityTopologyVersion topVer = ctx.affinity().affinityTopologyVersion();

        final IgniteCacheExpiryPolicy expiry = skipVals ? null : expiryPolicy(expiryPlc);

        final boolean evt = !skipVals;

        if (readRepairStrategy != null) {
            return new GridNearReadRepairCheckOnlyFuture(
                topVer,
                ctx,
                ctx.cacheKeysView(keys),
                readRepairStrategy,
                !skipStore,
                taskName,
                deserializeBinary,
                recovery,
                expiry,
                skipVals,
                needVer,
                false,
                null).multi();
        }

        // Optimisation: try to resolve value locally and escape 'get future' creation.
        if (!forcePrimary && ctx.config().isReadFromBackup() && ctx.affinityNode() &&
            ctx.group().topology().lostPartitions().isEmpty()) {
            ctx.shared().database().checkpointReadLock();

            try {
                Map<K, V> locVals = U.newHashMap(keys.size());

                boolean success = true;
                boolean readNoEntry = ctx.readNoEntry(expiry, false);

                // Optimistically expect that all keys are available locally (avoid creation of get future).
                for (KeyCacheObject key : keys) {
                    if (readNoEntry) {
                        CacheDataRow row = ctx.offheap().read(ctx, key);

                        if (row != null) {
                            long expireTime = row.expireTime();

                            if (expireTime == 0 || expireTime > U.currentTimeMillis()) {
                                ctx.addResult(locVals,
                                    key,
                                    row.value(),
                                    skipVals,
                                    false,
                                    deserializeBinary,
                                    true,
                                    null,
                                    row.version(),
                                    0,
                                    0,
                                    needVer,
                                    U.deploymentClassLoader(ctx.kernalContext(), U.contextDeploymentClassLoaderId(ctx.kernalContext())));

                                if (evt) {
                                    ctx.events().readEvent(key,
                                        null,
                                        null,
                                        row.value(),
                                        taskName,
                                        !deserializeBinary);
                                }
                            }
                            else
                                success = false;
                        }
                        else
                            success = false;
                    }
                    else {
                        GridCacheEntryEx entry = null;

                        while (true) {
                            try {
                                entry = entryEx(key);

                                // If our DHT cache do has value, then we peek it.
                                if (entry != null) {
                                    boolean isNew = entry.isNewLocked();

                                    EntryGetResult getRes = null;
                                    CacheObject v = null;
                                    GridCacheVersion ver = null;

                                    if (needVer) {
                                        getRes = entry.innerGetVersioned(
                                            null,
                                            null,
                                            /*update-metrics*/false,
                                            /*event*/evt,
                                            null,
                                            taskName,
                                            expiry,
                                            true,
                                            null);

                                        if (getRes != null) {
                                            v = getRes.value();
                                            ver = getRes.version();
                                        }
                                    }
                                    else {
                                        v = entry.innerGet(
                                            null,
                                            null,
                                            /*read-through*/false,
                                            /*update-metrics*/false,
                                            /*event*/evt,
                                            null,
                                            taskName,
                                            expiry,
                                            !deserializeBinary);
                                    }

                                    // Entry was not in memory or in swap, so we remove it from cache.
                                    if (v == null) {
                                        if (isNew && entry.markObsoleteIfEmpty(nextVersion()))
                                            removeEntry(entry);

                                        success = false;
                                    }
                                    else {
                                        ctx.addResult(locVals,
                                            key,
                                            v,
                                            skipVals,
                                            false,
                                            deserializeBinary,
                                            true,
                                            getRes,
                                            ver,
                                            0,
                                            0,
                                            needVer,
                                            U.deploymentClassLoader(
                                                ctx.kernalContext(),
                                                U.contextDeploymentClassLoaderId(ctx.kernalContext())
                                            )
                                        );
                                    }
                                }
                                else
                                    success = false;

                                break; // While.
                            }
                            catch (GridCacheEntryRemovedException ignored) {
                                // No-op, retry.
                            }
                            catch (GridDhtInvalidPartitionException ignored) {
                                success = false;

                                break; // While.
                            }
                            finally {
                                if (entry != null)
                                    entry.touch();
                            }
                        }
                    }

                    if (!success)
                        break;
                    else if (!skipVals && ctx.statisticsEnabled())
                        metrics0().onRead(true);
                }

                if (success) {
                    sendTtlUpdateRequest(expiry);

                    return new GridFinishedFuture<>(locVals);
                }
            }
            catch (IgniteCheckedException e) {
                return new GridFinishedFuture<>(e);
            }
            finally {
                ctx.shared().database().checkpointReadUnlock();
            }
        }

        if (expiry != null)
            expiry.reset();

        // Either reload or not all values are available locally.
        GridPartitionedGetFuture<K, V> fut = new GridPartitionedGetFuture<>(ctx,
            keys,
            !skipStore,
            forcePrimary,
            taskName,
            deserializeBinary,
            recovery,
            expiry,
            skipVals,
            needVer,
            false,
            null,
            null);

        fut.init(topVer);

        return fut;
    }

    /**
     * Executes local update.
     *
     * @param node Node.
     * @param req Update request.
     * @param completionCb Completion callback.
     */
    void updateAllAsyncInternal(
        final ClusterNode node,
        final GridNearAtomicAbstractUpdateRequest req,
        final UpdateReplyClosure completionCb
    ) {
        IgniteInternalFuture<Object> forceFut = ctx.group().preloader().request(ctx, req, req.topologyVersion());

        if (forceFut == null || forceFut.isDone()) {
            try {
                if (forceFut != null)
                    forceFut.get();
            }
            catch (NodeStoppingException ignored) {
                return;
            }
            catch (IgniteCheckedException e) {
                onForceKeysError(node.id(), req, completionCb, e);

                return;
            }

            updateAllAsyncInternal0(node, req, completionCb);
        }
        else {
            forceFut.listen(new CI1<IgniteInternalFuture<Object>>() {
                @Override public void apply(IgniteInternalFuture<Object> fut) {
                    try {
                        fut.get();
                    }
                    catch (NodeStoppingException ignored) {
                        return;
                    }
                    catch (IgniteCheckedException e) {
                        onForceKeysError(node.id(), req, completionCb, e);

                        return;
                    }

                    updateAllAsyncInternal0(node, req, completionCb);
                }
            });
        }
    }

    /**
     * @param nodeId Node ID.
     * @param req Update request.
     * @param completionCb Completion callback.
     * @param e Error.
     */
    private void onForceKeysError(final UUID nodeId,
        final GridNearAtomicAbstractUpdateRequest req,
        final UpdateReplyClosure completionCb,
        IgniteCheckedException e
    ) {
        GridNearAtomicUpdateResponse res = new GridNearAtomicUpdateResponse(ctx.cacheId(),
            nodeId,
            req.futureId(),
            req.partition(),
            false,
            ctx.deploymentEnabled());

        res.addFailedKeys(req.keys(), e);

        completionCb.apply(req, res);
    }

    /**
     * Executes local update after preloader fetched values.
     *
     * @param node Node.
     * @param req Update request.
     * @param completionCb Completion callback.
     */
    private void updateAllAsyncInternal0(
        final ClusterNode node,
        final GridNearAtomicAbstractUpdateRequest req,
        final UpdateReplyClosure completionCb
    ) {
        GridNearAtomicUpdateResponse res = new GridNearAtomicUpdateResponse(ctx.cacheId(),
            node.id(),
            req.futureId(),
            req.partition(),
            false,
            ctx.deploymentEnabled());

        assert !req.returnValue() || (req.operation() == TRANSFORM || req.size() == 1);

        GridDhtAtomicAbstractUpdateFuture dhtFut = null;

        IgniteCacheExpiryPolicy expiry = null;

        boolean needTaskName = ctx.events().isRecordable(EVT_CACHE_OBJECT_READ) ||
            ctx.events().isRecordable(EVT_CACHE_OBJECT_PUT) ||
            ctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED);

        String taskName = needTaskName ? ctx.kernalContext().task().resolveTaskName(req.taskNameHash()) : null;

        ctx.shared().database().checkpointReadLock();

        try {
            ctx.shared().database().ensureFreeSpace(ctx.dataRegion());

            // If batch store update is enabled, we need to lock all entries.
            // First, need to acquire locks on cache entries, then check filter.
            List<GridDhtCacheEntry> locked = lockEntries(req, req.topologyVersion());;

            Collection<IgniteBiTuple<GridDhtCacheEntry, GridCacheVersion>> deleted = null;

            DhtAtomicUpdateResult updDhtRes = new DhtAtomicUpdateResult();

            try {
                while (true) {
                    try {
                        GridDhtPartitionTopology top = topology();

                        top.readLock();

                        try {
                            if (top.stopping()) {
                                if (ctx.shared().cache().isCacheRestarting(name()))
                                    res.addFailedKeys(req.keys(), new IgniteCacheRestartingException(name()));
                                else
                                    res.addFailedKeys(req.keys(), new CacheStoppedException(name()));

                                completionCb.apply(req, res);

                                return;
                            }

                            boolean remap = false;

                            // Do not check topology version if topology was locked on near node by
                            // external transaction or explicit lock.
                            if (!req.topologyLocked()) {
                                AffinityTopologyVersion waitVer = top.topologyVersionFuture().initialVersion();

                                // No need to remap if next future version is compatible.
                                boolean compatible =
                                    waitVer.isBetween(req.lastAffinityChangedTopologyVersion(), req.topologyVersion());

                                // Can not wait for topology future since it will break
                                // GridNearAtomicCheckUpdateRequest processing.
                                remap = !compatible && !top.topologyVersionFuture().isDone() ||
                                    needRemap(req.topologyVersion());
                            }

                            if (!remap) {
                                update(node, locked, req, res, updDhtRes, taskName);

                                dhtFut = updDhtRes.dhtFuture();
                                deleted = updDhtRes.deleted();
                                expiry = updDhtRes.expiryPolicy();
                            }
                            else
                                // Should remap all keys.
                                res.remapTopologyVersion(top.lastTopologyChangeVersion());
                        }
                        finally {
                            top.readUnlock();
                        }

                        // This call will convert entry processor invocation results to cache object instances.
                        // Must be done outside topology read lock to avoid deadlocks.
                        if (res.returnValue() != null)
                            res.returnValue().marshalResult(ctx);

                        break;
                    }
                    catch (UnregisteredClassException ex) {
                        IgniteCacheObjectProcessor cacheObjProc = ctx.cacheObjects();

                        assert cacheObjProc instanceof CacheObjectBinaryProcessorImpl;

                        ((CacheObjectBinaryProcessorImpl)cacheObjProc)
                            .binaryContext().registerType(ex.cls(), true, false);
                    }
                    catch (UnregisteredBinaryTypeException ex) {
                        if (ex.future() != null) {
                            // Wait for the future that couldn't be processed because of
                            // IgniteThread#isForbiddenToRequestBinaryMetadata flag being true. Usually this means
                            // that awaiting for the future right there would lead to potential deadlock if
                            // continuous queries are used in parallel with entry processor.
                            ex.future().get();

                            // Retry and don't update current binary metadata, because it most likely already exists.
                            continue;
                        }

                        IgniteCacheObjectProcessor cacheObjProc = ctx.cacheObjects();

                        assert cacheObjProc instanceof CacheObjectBinaryProcessorImpl;

                        ((CacheObjectBinaryProcessorImpl)cacheObjProc)
                            .binaryContext().updateMetadata(ex.typeId(), ex.binaryMetadata(), false);
                    }
                }
            }
            catch (GridCacheEntryRemovedException e) {
                assert false : "Entry should not become obsolete while holding lock.";

                e.printStackTrace();
            }
            finally {
                if (locked != null)
                    unlockEntries(locked, req.topologyVersion());

                // Enqueue if necessary after locks release.
                if (deleted != null) {
                    assert !deleted.isEmpty();
                    assert ctx.deferredDelete() : this;

                    for (IgniteBiTuple<GridDhtCacheEntry, GridCacheVersion> e : deleted)
                        ctx.onDeferredDelete(e.get1(), e.get2());
                }

                // TODO handle failure: probably drop the node from topology
                // TODO fire events only after successful fsync
                if (ctx.shared().wal() != null)
                    ctx.shared().wal().flush(null, false);
            }
        }
        catch (GridDhtInvalidPartitionException ignore) {
            if (log.isDebugEnabled())
                log.debug("Caught invalid partition exception for cache entry (will remap update request): " + req);

            res.remapTopologyVersion(ctx.topology().lastTopologyChangeVersion());
        }
        catch (Throwable e) {
            // At least RuntimeException can be thrown by the code above when GridCacheContext is cleaned and there is
            // an attempt to use cleaned resources.
            U.error(log, "Unexpected exception during cache update", e);

            res.addFailedKeys(req.keys(), e);

            completionCb.apply(req, res);

            if (e instanceof Error)
                throw (Error)e;

            return;
        }
        finally {
            ctx.shared().database().checkpointReadUnlock();
        }

        if (res.remapTopologyVersion() != null) {
            assert dhtFut == null;

            completionCb.apply(req, res);
        }
        else {
            if (dhtFut != null)
                dhtFut.map(node, res.returnValue(), res, completionCb);
        }

        if (req.writeSynchronizationMode() != FULL_ASYNC)
            req.cleanup(!node.isLocal());

        sendTtlUpdateRequest(expiry);
    }

    /**
     * @param node Node.
     * @param locked Entries.
     * @param req Request.
     * @param res Response.
     * @param dhtUpdRes DHT update result
     * @param taskName Task name.
     * @return Operation result.
     * @throws GridCacheEntryRemovedException If got obsolete entry.
     */
    private DhtAtomicUpdateResult update(
        ClusterNode node,
        List<GridDhtCacheEntry> locked,
        GridNearAtomicAbstractUpdateRequest req,
        GridNearAtomicUpdateResponse res,
        DhtAtomicUpdateResult dhtUpdRes,
        String taskName
    ) throws GridCacheEntryRemovedException {
        GridDhtPartitionTopology top = topology();

        boolean hasNear = req.nearCache();

        // Assign next version for update inside entries lock.
        GridCacheVersion ver = dhtUpdRes.dhtFuture() != null /*retry*/ ? dhtUpdRes.dhtFuture().writeVer : nextVersion();

        if (hasNear)
            res.nearVersion(ver);

        if (msgLog.isDebugEnabled()) {
            msgLog.debug("Assigned update version [futId=" + req.futureId() +
                ", writeVer=" + ver + ']');
        }

        assert ver != null : "Got null version for update request: " + req;

        boolean sndPrevVal = !top.rebalanceFinished(req.topologyVersion());

        if (dhtUpdRes.dhtFuture() == null)
            dhtUpdRes.dhtFuture(createDhtFuture(ver, req));

        IgniteCacheExpiryPolicy expiry = expiryPolicy(req.expiry());

        GridCacheReturn retVal = null;

        if (req.size() > 1 &&                    // Several keys ...
            writeThrough() && !req.skipStore() && // and store is enabled ...
            !ctx.store().isLocal() &&             // and this is not local store ...
            // (conflict resolver should be used for local store)
            !ctx.dr().receiveEnabled()            // and no DR.
            ) {
            // This method can only be used when there are no replicated entries in the batch.
            updateWithBatch(node,
                hasNear,
                req,
                res,
                locked,
                ver,
                ctx.isDrEnabled(),
                taskName,
                expiry,
                sndPrevVal,
                dhtUpdRes);

            if (req.operation() == TRANSFORM)
                retVal = dhtUpdRes.returnValue();
        }
        else {
            updateSingle(node,
                hasNear,
                req,
                res,
                locked,
                ver,
                ctx.isDrEnabled(),
                taskName,
                expiry,
                sndPrevVal,
                dhtUpdRes);

            retVal = dhtUpdRes.returnValue();
        }

        GridDhtAtomicAbstractUpdateFuture dhtFut = dhtUpdRes.dhtFuture();

        if (retVal == null)
            retVal = new GridCacheReturn(ctx, node.isLocal(), true, null, null, true);

        res.returnValue(retVal);

        if (dhtFut != null) {
            if (req.writeSynchronizationMode() == PRIMARY_SYNC
                // To avoid deadlock disable back-pressure for sender data node.
                && !ctx.discovery().cacheGroupAffinityNode(node, ctx.groupId())
                && !dhtFut.isDone()) {
                final IgniteRunnable tracker = GridNioBackPressureControl.threadTracker();

                if (tracker instanceof GridNioMessageTracker) {
                    ((GridNioMessageTracker)tracker).onMessageReceived();

                    dhtFut.listen(new IgniteInClosure<IgniteInternalFuture<Void>>() {
                        @Override public void apply(IgniteInternalFuture<Void> fut) {
                            ((GridNioMessageTracker)tracker).onMessageProcessed();
                        }
                    });
                }
            }

            ctx.mvcc().addAtomicFuture(dhtFut.id(), dhtFut);
        }

        dhtUpdRes.expiryPolicy(expiry);

        return dhtUpdRes;
    }

    /**
     * Updates locked entries using batched write-through.
     *
     * @param node Sender node.
     * @param hasNear {@code True} if originating node has near cache.
     * @param req Update request.
     * @param res Update response.
     * @param locked Locked entries.
     * @param ver Assigned version.
     * @param replicate Whether replication is enabled.
     * @param taskName Task name.
     * @param expiry Expiry policy.
     * @param sndPrevVal If {@code true} sends previous value to backups.
     * @param dhtUpdRes DHT update result.
     * @throws GridCacheEntryRemovedException Should not be thrown.
     */
    @SuppressWarnings("unchecked")
    private void updateWithBatch(
        final ClusterNode node,
        final boolean hasNear,
        final GridNearAtomicAbstractUpdateRequest req,
        final GridNearAtomicUpdateResponse res,
        final List<GridDhtCacheEntry> locked,
        final GridCacheVersion ver,
        final boolean replicate,
        final String taskName,
        @Nullable final IgniteCacheExpiryPolicy expiry,
        final boolean sndPrevVal,
        final DhtAtomicUpdateResult dhtUpdRes
    ) throws GridCacheEntryRemovedException {
        assert !ctx.dr().receiveEnabled(); // Cannot update in batches during DR due to possible conflicts.
        assert !req.returnValue() || req.operation() == TRANSFORM; // Should not request return values for putAll.

        if (!F.isEmpty(req.filter()) && ctx.loadPreviousValue()) {
            try {
                reloadIfNeeded(locked);
            }
            catch (IgniteCheckedException e) {
                res.addFailedKeys(req.keys(), e);

                return;
            }
        }

        int size = req.size();

        Map<KeyCacheObject, CacheObject> putMap = null;

        Map<KeyCacheObject, EntryProcessor<Object, Object, Object>> entryProcMap = null;

        Collection<KeyCacheObject> rmvKeys = null;

        List<CacheObject> writeVals = null;

        List<GridDhtCacheEntry> filtered = new ArrayList<>(size);

        GridCacheOperation op = req.operation();

        GridCacheReturn invokeRes = null;

        int firstEntryIdx = 0;

        boolean intercept = ctx.config().getInterceptor() != null;

        for (int i = dhtUpdRes.processedEntriesCount(); i < locked.size(); i++) {
            GridDhtCacheEntry entry = locked.get(i);

            try {
                if (!checkFilter(entry, req, res)) {
                    if (expiry != null && entry.hasValue()) {
                        long ttl = expiry.forAccess();

                        if (ttl != CU.TTL_NOT_CHANGED) {
                            entry.updateTtl(null, ttl);

                            expiry.ttlUpdated(entry.key(),
                                entry.version(),
                                entry.readers());
                        }
                    }

                    if (log.isDebugEnabled())
                        log.debug("Entry did not pass the filter (will skip write) [entry=" + entry +
                            ", filter=" + Arrays.toString(req.filter()) + ", res=" + res + ']');

                    if (hasNear)
                        res.addSkippedIndex(i);

                    firstEntryIdx++;

                    continue;
                }

                if (op == TRANSFORM) {
                    EntryProcessor<Object, Object, Object> entryProc = req.entryProcessor(i);

                    CacheObject old = entry.innerGet(
                        ver,
                        null,
                        /*read through*/true,
                        /*metrics*/true,
                        /*event*/true,
                        entryProc,
                        taskName,
                        null,
                        req.keepBinary());

                    Object oldVal = null;
                    Object updatedVal = null;

                    CacheInvokeEntry<Object, Object> invokeEntry = new CacheInvokeEntry(entry.key(), old,
                        entry.version(), req.keepBinary(), entry);

                    CacheObject updated = null;

                    if (invokeRes == null)
                        invokeRes = new GridCacheReturn(node.isLocal());

                    CacheInvokeResult curInvokeRes = null;

                    boolean validation = false;

                    IgniteThread.onEntryProcessorEntered(true);

                    try {
                        Object computed = entryProc.process(invokeEntry, req.invokeArguments());

                        if (computed != null) {
                            computed = ctx.unwrapTemporary(computed);

                            curInvokeRes = CacheInvokeResult.fromResult(computed);
                        }

                        if (!invokeEntry.modified()) {
                            if (ctx.statisticsEnabled())
                                ctx.cache().metrics0().onReadOnlyInvoke(old != null);

                            continue;
                        }
                        else {
                            updatedVal = ctx.unwrapTemporary(invokeEntry.getValue());

                            updated = ctx.toCacheObject(updatedVal);

                            validation = true;

                            if (updated != null)
                                ctx.validateKeyAndValue(entry.key(), updated);
                        }
                    }
                    catch (UnregisteredClassException | UnregisteredBinaryTypeException e) {
                        throw e;
                    }
                    catch (Exception e) {
                        curInvokeRes = CacheInvokeResult.fromError(e);

                        updated = old;

                        if (validation) {
                            res.addSkippedIndex(i);

                            continue;
                        }
                    }
                    finally {
                        IgniteThread.onEntryProcessorLeft();

                        if (curInvokeRes != null) {
                            invokeRes.addEntryProcessResult(ctx, entry.key(), invokeEntry.key(), curInvokeRes.result(),
                                curInvokeRes.error(), req.keepBinary());
                        }
                    }

                    if (updated == null) {
                        if (intercept) {
                            CacheLazyEntry e = new CacheLazyEntry(ctx, entry.key(), invokeEntry.key(), old, oldVal, req.keepBinary());

                            IgniteBiTuple<Boolean, ?> interceptorRes = ctx.config().getInterceptor().onBeforeRemove(e);

                            if (ctx.cancelRemove(interceptorRes))
                                continue;
                        }

                        // Update previous batch.
                        if (putMap != null) {
                            updatePartialBatch(
                                hasNear,
                                firstEntryIdx,
                                filtered,
                                ver,
                                node,
                                writeVals,
                                putMap,
                                null,
                                entryProcMap,
                                req,
                                res,
                                replicate,
                                dhtUpdRes,
                                taskName,
                                expiry,
                                sndPrevVal);

                            firstEntryIdx = i;

                            putMap = null;
                            writeVals = null;
                            entryProcMap = null;

                            filtered = new ArrayList<>();
                        }

                        // Start collecting new batch.
                        if (rmvKeys == null)
                            rmvKeys = new ArrayList<>(size);

                        rmvKeys.add(entry.key());
                    }
                    else {
                        if (intercept) {
                            CacheLazyEntry e = new CacheLazyEntry(ctx, entry.key(), invokeEntry.key(), old, oldVal, req.keepBinary());

                            Object val = ctx.config().getInterceptor().onBeforePut(e, updatedVal);

                            if (val == null)
                                continue;

                            updated = ctx.toCacheObject(ctx.unwrapTemporary(val));
                        }

                        // Update previous batch.
                        if (rmvKeys != null) {
                            updatePartialBatch(
                                hasNear,
                                firstEntryIdx,
                                filtered,
                                ver,
                                node,
                                null,
                                null,
                                rmvKeys,
                                entryProcMap,
                                req,
                                res,
                                replicate,
                                dhtUpdRes,
                                taskName,
                                expiry,
                                sndPrevVal);

                            firstEntryIdx = i;

                            rmvKeys = null;
                            entryProcMap = null;

                            filtered = new ArrayList<>();
                        }

                        if (putMap == null) {
                            putMap = new LinkedHashMap<>(size, 1.0f);
                            writeVals = new ArrayList<>(size);
                        }

                        putMap.put(entry.key(), updated);
                        writeVals.add(updated);
                    }

                    if (entryProcMap == null)
                        entryProcMap = new HashMap<>();

                    entryProcMap.put(entry.key(), entryProc);
                }
                else if (op == UPDATE) {
                    CacheObject updated = req.value(i);

                    if (intercept) {
                        CacheObject old = entry.innerGet(
                            null,
                            null,
                            /*read through*/ctx.loadPreviousValue(),
                            /*metrics*/true,
                            /*event*/true,
                            null,
                            taskName,
                            null,
                            req.keepBinary());

                        Object val = ctx.config().getInterceptor().onBeforePut(
                            new CacheLazyEntry(
                                ctx,
                                entry.key(),
                                old,
                                req.keepBinary()),
                            ctx.unwrapBinaryIfNeeded(
                                updated,
                                req.keepBinary(),
                                false,
                                null));

                        if (val == null)
                            continue;

                        updated = ctx.toCacheObject(ctx.unwrapTemporary(val));
                    }

                    assert updated != null;

                    ctx.validateKeyAndValue(entry.key(), updated);

                    if (putMap == null) {
                        putMap = new LinkedHashMap<>(size, 1.0f);
                        writeVals = new ArrayList<>(size);
                    }

                    putMap.put(entry.key(), updated);
                    writeVals.add(updated);
                }
                else {
                    assert op == DELETE;

                    if (intercept) {
                        CacheObject old = entry.innerGet(
                            null,
                            null,
                            /*read through*/ctx.loadPreviousValue(),
                            /*metrics*/true,
                            /*event*/true,
                            null,
                            taskName,
                            null,
                            req.keepBinary());

                        IgniteBiTuple<Boolean, ?> interceptorRes = ctx.config().getInterceptor()
                            .onBeforeRemove(new CacheLazyEntry(ctx, entry.key(), old, req.keepBinary()));

                        if (ctx.cancelRemove(interceptorRes))
                            continue;
                    }

                    if (rmvKeys == null)
                        rmvKeys = new ArrayList<>(size);

                    rmvKeys.add(entry.key());
                }

                filtered.add(entry);
            }
            catch (IgniteCheckedException e) {
                res.addFailedKey(entry.key(), e);
            }
        }

        // Store final batch.
        if (putMap != null || rmvKeys != null) {
            updatePartialBatch(
                hasNear,
                firstEntryIdx,
                filtered,
                ver,
                node,
                writeVals,
                putMap,
                rmvKeys,
                entryProcMap,
                req,
                res,
                replicate,
                dhtUpdRes,
                taskName,
                expiry,
                sndPrevVal);
        }
        else
            assert filtered.isEmpty();

        dhtUpdRes.returnValue(invokeRes);
    }

    /**
     * @param entries Entries.
     * @throws IgniteCheckedException If failed.
     */
    private void reloadIfNeeded(final List<GridDhtCacheEntry> entries) throws IgniteCheckedException {
        Map<KeyCacheObject, Integer> needReload = null;

        for (int i = 0; i < entries.size(); i++) {
            GridDhtCacheEntry entry = entries.get(i);

            if (entry == null)
                continue;

            CacheObject val = entry.rawGet();

            if (val == null) {
                if (needReload == null)
                    needReload = new HashMap<>(entries.size(), 1.0f);

                needReload.put(entry.key(), i);
            }
        }

        if (needReload != null) {
            final Map<KeyCacheObject, Integer> idxMap = needReload;

            ctx.store().loadAll(null, needReload.keySet(), new CI2<KeyCacheObject, Object>() {
                @Override public void apply(KeyCacheObject k, Object v) {
                    Integer idx = idxMap.get(k);

                    if (idx != null) {
                        GridDhtCacheEntry entry = entries.get(idx);

                        try {
                            GridCacheVersion ver = entry.version();

                            entry.versionedValue(ctx.toCacheObject(v), null, ver, null, null);
                        }
                        catch (GridCacheEntryRemovedException e) {
                            assert false : "Entry should not get obsolete while holding lock [entry=" + entry +
                                ", e=" + e + ']';
                        }
                        catch (IgniteCheckedException e) {
                            throw new IgniteException(e);
                        }
                    }
                }
            });
        }
    }

    /**
     * Updates locked entries one-by-one.
     *
     * @param nearNode Originating node.
     * @param hasNear {@code True} if originating node has near cache.
     * @param req Update request.
     * @param res Update response.
     * @param locked Locked entries.
     * @param ver Assigned update version.
     * @param replicate Whether DR is enabled for that cache.
     * @param taskName Task name.
     * @param expiry Expiry policy.
     * @param sndPrevVal If {@code true} sends previous value to backups.
     * @param dhtUpdRes Dht update result
     * @throws GridCacheEntryRemovedException Should be never thrown.
     */
    private void updateSingle(
        ClusterNode nearNode,
        boolean hasNear,
        GridNearAtomicAbstractUpdateRequest req,
        GridNearAtomicUpdateResponse res,
        List<GridDhtCacheEntry> locked,
        GridCacheVersion ver,
        boolean replicate,
        String taskName,
        @Nullable IgniteCacheExpiryPolicy expiry,
        boolean sndPrevVal,
        DhtAtomicUpdateResult dhtUpdRes
    ) throws GridCacheEntryRemovedException {
        GridCacheReturn retVal = dhtUpdRes.returnValue();
        GridDhtAtomicAbstractUpdateFuture dhtFut = dhtUpdRes.dhtFuture();
        Collection<IgniteBiTuple<GridDhtCacheEntry, GridCacheVersion>> deleted = dhtUpdRes.deleted();

        AffinityTopologyVersion topVer = req.topologyVersion();

        boolean intercept = ctx.config().getInterceptor() != null;

        AffinityAssignment affAssignment = ctx.affinity().assignment(topVer);

        // Avoid iterator creation.
        for (int i = dhtUpdRes.processedEntriesCount(); i < req.size(); i++) {
            KeyCacheObject k = req.key(i);

            GridCacheOperation op = req.operation();

            // We are holding java-level locks on entries at this point.
            // No GridCacheEntryRemovedException can be thrown.
            try {
                GridDhtCacheEntry entry = locked.get(i);

                GridCacheVersion newConflictVer = req.conflictVersion(i);
                long newConflictTtl = req.conflictTtl(i);
                long newConflictExpireTime = req.conflictExpireTime(i);

                assert !(newConflictVer instanceof GridCacheVersionEx) : newConflictVer;

                Object writeVal = op == TRANSFORM ? req.entryProcessor(i) : req.writeValue(i);

                boolean readRepairRecovery = op == TRANSFORM && req.entryProcessor(i) instanceof AtomicReadRepairEntryProcessor;

                // Get readers before innerUpdate (reader cleared after remove).
                GridDhtCacheEntry.ReaderId[] readers = entry.readersLocked();

                GridCacheUpdateAtomicResult updRes = entry.innerUpdate(
                    ver,
                    nearNode.id(),
                    locNodeId,
                    op,
                    writeVal,
                    req.invokeArguments(),
                    writeThrough() && !req.skipStore(),
                    !req.skipStore(),
                    sndPrevVal || req.returnValue(),
                    req.keepBinary(),
                    expiry,
                    /*event*/true,
                    /*metrics*/true,
                    /*primary*/true,
                    /*verCheck*/false,
                    readRepairRecovery,
                    topVer,
                    req.filter(),
                    replicate ? DR_PRIMARY : DR_NONE,
                    newConflictTtl,
                    newConflictExpireTime,
                    newConflictVer,
                    /*conflictResolve*/true,
                    intercept,
                    taskName,
                    /*prevVal*/null,
                    /*updateCntr*/null,
                    dhtFut,
                    false);

                if (dhtFut != null) {
                    if (updRes.sendToDht()) { // Send to backups even in case of remove-remove scenarios.
                        GridCacheVersionConflictContext<?, ?> conflictCtx = updRes.conflictResolveResult();

                        if (conflictCtx == null)
                            newConflictVer = null;
                        else if (conflictCtx.isMerge())
                            newConflictVer = null; // Conflict version is discarded in case of merge.

                        EntryProcessor<Object, Object, Object> entryProc = null;

                        dhtFut.addWriteEntry(
                            affAssignment,
                            entry,
                            updRes.newValue(),
                            entryProc,
                            updRes.newTtl(),
                            updRes.conflictExpireTime(),
                            newConflictVer,
                            sndPrevVal,
                            updRes.oldValue(),
                            updRes.updateCounter(),
                            op,
                            readRepairRecovery);

                        if (readers != null)
                            dhtFut.addNearWriteEntries(
                                nearNode,
                                readers,
                                entry,
                                updRes.newValue(),
                                entryProc,
                                updRes.newTtl(),
                                updRes.conflictExpireTime(),
                                readRepairRecovery);
                    }
                    else {
                        if (log.isDebugEnabled())
                            log.debug("Entry did not pass the filter or conflict resolution (will skip write) " +
                                "[entry=" + entry + ", filter=" + Arrays.toString(req.filter()) + ']');
                    }
                }

                if (hasNear) {
                    if (updRes.sendToDht()) {
                        if (!ctx.affinity().partitionBelongs(nearNode, entry.partition(), topVer)) {
                            // If put the same value as in request then do not need to send it back.
                            if (op == TRANSFORM || writeVal != updRes.newValue()) {
                                res.addNearValue(i,
                                    updRes.newValue(),
                                    updRes.newTtl(),
                                    updRes.conflictExpireTime());
                            }
                            else
                                res.addNearTtl(i, updRes.newTtl(), updRes.conflictExpireTime());

                            if (updRes.newValue() != null) {
                                IgniteInternalFuture<Boolean> f =
                                    entry.addReader(nearNode.id(), req.messageId(), topVer);

                                assert f == null : f;
                            }
                        }
                        else if (GridDhtCacheEntry.ReaderId.contains(readers, nearNode.id())) {
                            // Reader became primary or backup.
                            entry.removeReader(nearNode.id(), req.messageId());
                        }
                        else
                            res.addSkippedIndex(i);
                    }
                    else
                        res.addSkippedIndex(i);
                }

                if (updRes.removeVersion() != null) {
                    if (deleted == null)
                        deleted = new ArrayList<>(req.size());

                    deleted.add(F.t(entry, updRes.removeVersion()));
                }

                if (op == TRANSFORM) {
                    assert !req.returnValue();

                    IgniteBiTuple<Object, Exception> compRes = updRes.computedResult();

                    if (compRes != null && (compRes.get1() != null || compRes.get2() != null)) {
                        if (retVal == null)
                            retVal = new GridCacheReturn(nearNode.isLocal());

                        retVal.addEntryProcessResult(ctx,
                            k,
                            null,
                            compRes.get1(),
                            compRes.get2(),
                            req.keepBinary());
                    }
                }
                else {
                    // Create only once.
                    if (retVal == null) {
                        CacheObject ret = updRes.oldValue();

                        retVal = new GridCacheReturn(ctx,
                            nearNode.isLocal(),
                            req.keepBinary(),
                            U.deploymentClassLoader(ctx.kernalContext(), U.contextDeploymentClassLoaderId(ctx.kernalContext())),
                            req.returnValue() ? ret : null,
                            updRes.success());
                    }
                }
            }
            catch (IgniteCheckedException e) {
                res.addFailedKey(k, e);
            }

            dhtUpdRes.processedEntriesCount(i + 1);
        }

        dhtUpdRes.returnValue(retVal);
        dhtUpdRes.deleted(deleted);
        dhtUpdRes.dhtFuture(dhtFut);
    }

    /**
     * @param hasNear {@code True} if originating node has near cache.
     * @param firstEntryIdx Index of the first entry in the request keys collection.
     * @param entries Entries to update.
     * @param ver Version to set.
     * @param nearNode Originating node.
     * @param writeVals Write values.
     * @param putMap Values to put.
     * @param rmvKeys Keys to remove.
     * @param entryProcessorMap Entry processors.
     * @param req Request.
     * @param res Response.
     * @param replicate Whether replication is enabled.
     * @param dhtUpdRes Batch update result.
     * @param taskName Task name.
     * @param expiry Expiry policy.
     * @param sndPrevVal If {@code true} sends previous value to backups.
     */
    @Nullable private void updatePartialBatch(
        final boolean hasNear,
        final int firstEntryIdx,
        final List<GridDhtCacheEntry> entries,
        final GridCacheVersion ver,
        final ClusterNode nearNode,
        @Nullable final List<CacheObject> writeVals,
        @Nullable final Map<KeyCacheObject, CacheObject> putMap,
        @Nullable final Collection<KeyCacheObject> rmvKeys,
        @Nullable final Map<KeyCacheObject, EntryProcessor<Object, Object, Object>> entryProcessorMap,
        final GridNearAtomicAbstractUpdateRequest req,
        final GridNearAtomicUpdateResponse res,
        final boolean replicate,
        final DhtAtomicUpdateResult dhtUpdRes,
        final String taskName,
        @Nullable final IgniteCacheExpiryPolicy expiry,
        final boolean sndPrevVal
    ) {
        assert putMap == null ^ rmvKeys == null;

        assert req.conflictVersions() == null : "Cannot be called when there are conflict entries in the batch.";

        AffinityTopologyVersion topVer = req.topologyVersion();

        CacheStorePartialUpdateException storeErr = null;

        try {
            GridCacheOperation op;

            if (putMap != null) {
                try {
                    Map<? extends KeyCacheObject, IgniteBiTuple<? extends CacheObject, GridCacheVersion>> view = F.viewReadOnly(putMap,
                        new C1<CacheObject, IgniteBiTuple<? extends CacheObject, GridCacheVersion>>() {
                            @Override public IgniteBiTuple<? extends CacheObject, GridCacheVersion> apply(CacheObject val) {
                                return F.t(val, ver);
                            }
                        });

                    ctx.store().putAll(null, view);
                }
                catch (CacheStorePartialUpdateException e) {
                    storeErr = e;
                }

                op = UPDATE;
            }
            else {
                try {
                    ctx.store().removeAll(null, rmvKeys);
                }
                catch (CacheStorePartialUpdateException e) {
                    storeErr = e;
                }

                op = DELETE;
            }

            boolean intercept = ctx.config().getInterceptor() != null;

            AffinityAssignment affAssignment = ctx.affinity().assignment(topVer);

            final GridDhtAtomicAbstractUpdateFuture dhtFut = dhtUpdRes.dhtFuture();

            Collection<Object> failedToUnwrapKeys = null;

            // Avoid iterator creation.
            for (int i = 0; i < entries.size(); i++) {
                GridDhtCacheEntry entry = entries.get(i);

                assert entry.lockedByCurrentThread();

                if (entry.obsolete()) {
                    assert req.operation() == DELETE : "Entry can become obsolete only after remove: " + entry;

                    continue;
                }

                if (storeErr != null) {
                    Object key = entry.key();

                    try {
                        key = entry.key().value(ctx.cacheObjectContext(), false);
                    }
                    catch (BinaryInvalidTypeException e) {
                        if (log.isDebugEnabled()) {
                            if (failedToUnwrapKeys == null)
                                failedToUnwrapKeys = new ArrayList<>();

                            // To limit keys count in log message.
                            if (failedToUnwrapKeys.size() < 5)
                                failedToUnwrapKeys.add(key);
                        }
                    }

                    if (storeErr.failedKeys().contains(key))
                        continue;
                }

                try {
                    // We are holding java-level locks on entries at this point.
                    CacheObject writeVal = op == UPDATE ? writeVals.get(i) : null;

                    assert writeVal != null || op == DELETE : "null write value found.";

                    // Get readers before innerUpdate (reader cleared after remove).
                    GridDhtCacheEntry.ReaderId[] readers = entry.readersLocked();

                    EntryProcessor<Object, Object, Object> entryProc =
                        entryProcessorMap == null ? null : entryProcessorMap.get(entry.key());

                    boolean readRepairRecovery = op == TRANSFORM && req.entryProcessor(i) instanceof AtomicReadRepairEntryProcessor;

                    GridCacheUpdateAtomicResult updRes = entry.innerUpdate(
                        ver,
                        nearNode.id(),
                        locNodeId,
                        op,
                        writeVal,
                        null,
                        /*write-through*/false,
                        /*read-through*/false,
                        /*retval*/sndPrevVal,
                        req.keepBinary(),
                        expiry,
                        /*event*/true,
                        /*metrics*/true,
                        /*primary*/true,
                        /*verCheck*/false,
                        readRepairRecovery,
                        topVer,
                        null,
                        replicate ? DR_PRIMARY : DR_NONE,
                        CU.TTL_NOT_CHANGED,
                        CU.EXPIRE_TIME_CALCULATE,
                        null,
                        /*conflict resolve*/false,
                        /*intercept*/false,
                        taskName,
                        null,
                        null,
                        dhtFut,
                        entryProc != null);

                    assert !updRes.success() || updRes.newTtl() == CU.TTL_NOT_CHANGED || expiry != null :
                        "success=" + updRes.success() + ", newTtl=" + updRes.newTtl() + ", expiry=" + expiry;

                    if (intercept) {
                        if (op == UPDATE) {
                            ctx.config().getInterceptor().onAfterPut(new CacheLazyEntry(
                                ctx,
                                entry.key(),
                                updRes.newValue(),
                                req.keepBinary()));
                        }
                        else {
                            assert op == DELETE : op;

                            // Old value should be already loaded for 'CacheInterceptor.onBeforeRemove'.
                            ctx.config().getInterceptor().onAfterRemove(new CacheLazyEntry(ctx, entry.key(),
                                updRes.oldValue(), req.keepBinary()));
                        }
                    }

                    dhtUpdRes.addDeleted(entry, updRes, entries);

                    if (dhtFut != null) {
                        dhtFut.addWriteEntry(
                            affAssignment,
                            entry,
                            writeVal,
                            entryProc,
                            updRes.newTtl(),
                            CU.EXPIRE_TIME_CALCULATE,
                            null,
                            sndPrevVal,
                            updRes.oldValue(),
                            updRes.updateCounter(),
                            op,
                            readRepairRecovery);

                        if (readers != null)
                            dhtFut.addNearWriteEntries(
                                nearNode,
                                readers,
                                entry,
                                writeVal,
                                entryProc,
                                updRes.newTtl(),
                                CU.EXPIRE_TIME_CALCULATE,
                                readRepairRecovery);
                    }

                    if (hasNear) {
                        if (!ctx.affinity().partitionBelongs(nearNode, entry.partition(), topVer)) {
                            int idx = firstEntryIdx + i;

                            if (req.operation() == TRANSFORM) {
                                res.addNearValue(idx,
                                    writeVal,
                                    updRes.newTtl(),
                                    CU.EXPIRE_TIME_CALCULATE);
                            }
                            else
                                res.addNearTtl(idx, updRes.newTtl(), CU.EXPIRE_TIME_CALCULATE);

                            if (writeVal != null || entry.hasValue()) {
                                IgniteInternalFuture<Boolean> f = entry.addReader(nearNode.id(), req.messageId(), topVer);

                                assert f == null : f;
                            }
                        }
                        else if (GridDhtCacheEntry.ReaderId.contains(readers, nearNode.id())) {
                            // Reader became primary or backup.
                            entry.removeReader(nearNode.id(), req.messageId());
                        }
                        else
                            res.addSkippedIndex(firstEntryIdx + i);
                    }
                }
                catch (GridCacheEntryRemovedException e) {
                    assert false : "Entry cannot become obsolete while holding lock.";

                    e.printStackTrace();
                }

                dhtUpdRes.processedEntriesCount(firstEntryIdx + i + 1);
            }

            if (failedToUnwrapKeys != null) {
                log.warning("Failed to get values of keys: " + failedToUnwrapKeys +
                    " (the binary objects will be used instead).");
            }
        }
        catch (IgniteCheckedException e) {
            res.addFailedKeys(putMap != null ? putMap.keySet() : rmvKeys, e);
        }

        if (storeErr != null) {
            ArrayList<KeyCacheObject> failed = new ArrayList<>(storeErr.failedKeys().size());

            for (Object failedKey : storeErr.failedKeys())
                failed.add(ctx.toCacheKeyObject(failedKey));

            res.addFailedKeys(failed, storeErr.getCause());
        }
    }

    /**
     * Acquires java-level locks on cache entries. Returns collection of locked entries.
     *
     * @param req Request with keys to lock.
     * @param topVer Topology version to lock on.
     * @return Collection of locked entries.
     * @throws GridDhtInvalidPartitionException If entry does not belong to local node. If exception is thrown, locks
     * are released.
     */
    private List<GridDhtCacheEntry> lockEntries(GridNearAtomicAbstractUpdateRequest req, AffinityTopologyVersion topVer)
        throws GridDhtInvalidPartitionException {
        if (req.size() == 1) {
            KeyCacheObject key = req.key(0);

            while (true) {
                GridDhtCacheEntry entry = entryExx(key, topVer);

                entry.lockEntry();

                if (entry.obsolete())
                    entry.unlockEntry();
                else
                    return Collections.singletonList(entry);
            }
        }
        else {
            GridDhtCacheEntry[] locked = new GridDhtCacheEntry[req.size()];

            while (true) {
                for (int i = 0; i < req.size(); i++) {
                    GridDhtCacheEntry entry = entryExx(req.key(i), topVer);

                    locked[i] = entry;
                }

                if (lockedEntriesInfo.tryLockEntries(locked))
                    return Arrays.asList(locked);
            }
        }
    }

    /**
     * Releases java-level locks on cache entries.
     *
     * @param locked Locked entries.
     * @param topVer Topology version.
     */
    private void unlockEntries(List<GridDhtCacheEntry> locked, AffinityTopologyVersion topVer) {
        // Process deleted entries before locks release.
        assert ctx.deferredDelete() : this;

        // Entries to skip eviction manager notification for.
        // Enqueue entries while holding locks.
        Collection<KeyCacheObject> skip = null;

        int size = locked.size();

        try {
            for (int i = 0; i < size; i++) {
                GridCacheMapEntry entry = locked.get(i);
                if (entry != null && entry.deleted()) {
                    if (skip == null)
                        skip = U.newHashSet(locked.size());

                    skip.add(entry.key());
                }
            }
        }
        finally {
            // At least RuntimeException can be thrown by the code above when GridCacheContext is cleaned and there is
            // an attempt to use cleaned resources.
            // That's why releasing locks in the finally block..
            for (int i = 0; i < size; i++) {
                GridCacheMapEntry entry = locked.get(i);
                if (entry != null)
                    entry.unlockEntry();
            }
        }

        // Try evict partitions.
        for (int i = 0; i < size; i++) {
            GridDhtCacheEntry entry = locked.get(i);
            if (entry != null)
                entry.onUnlock();
        }

        if (skip != null && skip.size() == size)
            // Optimization.
            return;

        // Must touch all entries since update may have deleted entries.
        // Eviction manager will remove empty entries.
        for (int i = 0; i < size; i++) {
            GridCacheMapEntry entry = locked.get(i);
            if (entry != null && (skip == null || !skip.contains(entry.key())))
                entry.touch();
        }
    }

    /**
     * @param entry Entry to check.
     * @param req Update request.
     * @param res Update response. If filter evaluation failed, key will be added to failed keys and method will return
     * false.
     * @return {@code True} if filter evaluation succeeded.
     */
    private boolean checkFilter(GridCacheEntryEx entry, GridNearAtomicAbstractUpdateRequest req,
        GridNearAtomicUpdateResponse res) {
        try {
            return ctx.isAllLocked(entry, req.filter());
        }
        catch (IgniteCheckedException e) {
            res.addFailedKey(entry.key(), e);

            return false;
        }
    }

    /**
     * @param req Request to remap.
     */
    void remapToNewPrimary(GridNearAtomicAbstractUpdateRequest req) {
        assert req.writeSynchronizationMode() == FULL_ASYNC : req;

        if (log.isDebugEnabled())
            log.debug("Remapping near update request locally: " + req);

        Collection<?> vals;
        Collection<GridCacheDrInfo> drPutVals;
        Collection<GridCacheVersion> drRmvVals;

        if (req.conflictVersions() == null) {
            vals = req.values();

            drPutVals = null;
            drRmvVals = null;
        }
        else if (req.operation() == UPDATE) {
            int size = req.keys().size();

            drPutVals = new ArrayList<>(size);

            for (int i = 0; i < size; i++) {
                long ttl = req.conflictTtl(i);

                if (ttl == CU.TTL_NOT_CHANGED)
                    drPutVals.add(new GridCacheDrInfo(req.value(i), req.conflictVersion(i)));
                else
                    drPutVals.add(new GridCacheDrExpirationInfo(req.value(i), req.conflictVersion(i), ttl,
                        req.conflictExpireTime(i)));
            }

            vals = null;
            drRmvVals = null;
        }
        else {
            assert req.operation() == DELETE : req;

            drRmvVals = req.conflictVersions();

            vals = null;
            drPutVals = null;
        }

        CacheOperationContext opCtx = ctx.operationContextPerCall();

        GridNearAtomicUpdateFuture updateFut = new GridNearAtomicUpdateFuture(
            ctx,
            this,
            ctx.config().getWriteSynchronizationMode(),
            req.operation(),
            req.keys(),
            vals,
            req.invokeArguments(),
            drPutVals,
            drRmvVals,
            req.returnValue(),
            req.expiry(),
            req.filter(),
            req.taskNameHash(),
            req.skipStore(),
            req.keepBinary(),
            req.recovery(),
            MAX_RETRIES,
            opCtx == null ? null : opCtx.applicationAttributes());

        updateFut.map();
    }

    /**
     * Creates backup update future if necessary.
     *
     * @param writeVer Write version.
     * @param updateReq Update request.
     * @return Backup update future.
     */
    private GridDhtAtomicAbstractUpdateFuture createDhtFuture(
        GridCacheVersion writeVer,
        GridNearAtomicAbstractUpdateRequest updateReq
    ) {
        if (updateReq.size() == 1)
            return new GridDhtAtomicSingleUpdateFuture(ctx, writeVer, updateReq);
        else
            return new GridDhtAtomicUpdateFuture(ctx, writeVer, updateReq);
    }

    /**
     * @param nodeId Sender node ID.
     * @param req Near atomic update request.
     */
    private void processNearAtomicUpdateRequest(UUID nodeId, GridNearAtomicAbstractUpdateRequest req) {
        if (msgLog.isDebugEnabled()) {
            msgLog.debug("Received near atomic update request [futId=" + req.futureId() +
                ", node=" + nodeId + ']');
        }

        ClusterNode node = ctx.discovery().node(nodeId);

        if (node == null) {
            U.warn(msgLog, "Skip near update request, node originated update request left [" +
                "futId=" + req.futureId() + ", node=" + nodeId + ']');

            return;
        }

        updateAllAsyncInternal(node, req, updateReplyClos);
    }

    /**
     * @param nodeId Sender node ID.
     * @param res Near atomic update response.
     */
    private void processNearAtomicUpdateResponse(UUID nodeId, GridNearAtomicUpdateResponse res) {
        if (msgLog.isDebugEnabled())
            msgLog.debug("Received near atomic update response [futId" + res.futureId() +
                ", node=" + nodeId + ']');

        res.nodeId(ctx.localNodeId());

        GridNearAtomicAbstractUpdateFuture fut =
            (GridNearAtomicAbstractUpdateFuture)ctx.mvcc().atomicFuture(res.futureId());

        if (fut != null)
            fut.onPrimaryResponse(nodeId, res, false);
        else
            U.warn(msgLog, "Failed to find near update future for update response (will ignore) " +
                "[futId=" + res.futureId() + ", node=" + nodeId + ", res=" + res + ']');
    }

    /**
     * @param nodeId Node ID.
     * @param checkReq Request.
     */
    private void processCheckUpdateRequest(UUID nodeId, GridNearAtomicCheckUpdateRequest checkReq) {
        /*
         * Message is processed in the same stripe, so primary already processed update request. It is possible
         * response was not sent if operation result was empty. Near node will get original response or this one.
         */
        GridNearAtomicUpdateResponse res = new GridNearAtomicUpdateResponse(ctx.cacheId(),
            nodeId,
            checkReq.futureId(),
            checkReq.partition(),
            false,
            false);

        GridCacheReturn ret = new GridCacheReturn(false, true);

        res.returnValue(ret);

        sendNearUpdateReply(nodeId, res);
    }

    /**
     * @param nodeId Sender node ID.
     * @param req Dht atomic update request.
     */
    private void processDhtAtomicUpdateRequest(UUID nodeId, GridDhtAtomicAbstractUpdateRequest req) {
        assert Thread.currentThread().getName().startsWith("sys-stripe-") : Thread.currentThread().getName();

        if (msgLog.isDebugEnabled()) {
            msgLog.debug("Received DHT atomic update request [futId=" + req.futureId() +
                ", writeVer=" + req.writeVersion() + ", node=" + nodeId + ']');
        }

        assert req.partition() >= 0 : req;

        GridCacheVersion ver = req.writeVersion();

        ctx.versions().onReceived(nodeId, ver);

        GridDhtAtomicNearResponse nearRes = null;

        if (req.nearNodeId() != null) {
            nearRes = new GridDhtAtomicNearResponse(ctx.cacheId(),
                req.partition(),
                req.nearFutureId(),
                nodeId,
                req.flags());
        }

        boolean replicate = ctx.isDrEnabled();

        boolean intercept = req.forceTransformBackups() && ctx.config().getInterceptor() != null;

        boolean needTaskName = ctx.events().isRecordable(EVT_CACHE_OBJECT_READ) ||
            ctx.events().isRecordable(EVT_CACHE_OBJECT_PUT) ||
            ctx.events().isRecordable(EVT_CACHE_OBJECT_REMOVED);

        String taskName = needTaskName ? ctx.kernalContext().task().resolveTaskName(req.taskNameHash()) : null;

        ctx.shared().database().checkpointReadLock();

        try {
            for (int i = 0; i < req.size(); i++) {
                KeyCacheObject key = req.key(i);

                try {
                    while (true) {
                        GridDhtCacheEntry entry = null;

                        try {
                            entry = entryExx(key);

                            CacheObject val = req.value(i);
                            CacheObject prevVal = req.previousValue(i);

                            EntryProcessor<Object, Object, Object> entryProc = req.entryProcessor(i);
                            Long updateIdx = req.updateCounter(i);

                            GridCacheOperation op = entryProc != null ? TRANSFORM :
                                (val != null) ? UPDATE : DELETE;

                            long ttl = req.ttl(i);
                            long expireTime = req.conflictExpireTime(i);

                            GridCacheUpdateAtomicResult updRes = entry.innerUpdate(
                                ver,
                                nodeId,
                                nodeId,
                                op,
                                op == TRANSFORM ? entryProc : val,
                                op == TRANSFORM ? req.invokeArguments() : null,
                                /*write-through*/(ctx.store().isLocal() && !ctx.shared().localStorePrimaryOnly())
                                    && writeThrough() && !req.skipStore(),
                                /*read-through*/false,
                                /*retval*/false,
                                req.keepBinary(),
                                /*expiry policy*/null,
                                /*event*/true,
                                /*metrics*/true,
                                /*primary*/false,
                                /*check version*/!req.forceTransformBackups(),
                                req.readRepairRecovery(),
                                req.topologyVersion(),
                                CU.empty0(),
                                replicate ? DR_BACKUP : DR_NONE,
                                ttl,
                                expireTime,
                                req.conflictVersion(i),
                                false,
                                intercept,
                                taskName,
                                prevVal,
                                updateIdx,
                                null,
                                req.transformOperation());

                            if (updRes.removeVersion() != null)
                                ctx.onDeferredDelete(entry, updRes.removeVersion());

                            entry.onUnlock();

                            break; // While.
                        }
                        catch (GridCacheEntryRemovedException ignored) {
                            if (log.isDebugEnabled())
                                log.debug("Got removed entry while updating backup value (will retry): " + key);

                            entry = null;
                        }
                        finally {
                            if (entry != null)
                                entry.touch();
                        }
                    }
                }
                catch (NodeStoppingException e) {
                    U.warn(log, "Failed to update key on backup (local node is stopping): " + key);

                    return;
                }
                catch (GridDhtInvalidPartitionException ignored) {
                    // Ignore.
                }
                catch (IgniteCheckedException | RuntimeException e) {
                    if (e instanceof RuntimeException && !X.hasCause(e, IgniteOutOfMemoryException.class))
                        throw (RuntimeException)e;

                    U.error(log, "Failed to update key on backup node: " + key, e);

                    IgniteCheckedException err =
                        new IgniteCheckedException("Failed to update key on backup node: " + key, e);

                    // Trigger failure handler to avoid data inconsistency.
                    ctx.kernalContext().failure().process(new FailureContext(FailureType.CRITICAL_ERROR, err));

                    if (nearRes != null)
                        nearRes.addFailedKey(key, err);
                }
            }
        }
        finally {
            ctx.shared().database().checkpointReadUnlock();
        }

        GridDhtAtomicUpdateResponse dhtRes = null;

        if (req.nearSize() > 0 || req.obsoleteNearKeysSize() > 0) {
            List<KeyCacheObject> nearEvicted = null;

            if (isNearEnabled(ctx))
                nearEvicted = ((GridNearAtomicCache<K, V>)near()).processDhtAtomicUpdateRequest(nodeId, req, nearRes);
            else if (req.nearSize() > 0) {
                nearEvicted = new ArrayList<>(req.nearSize());

                for (int i = 0; i < req.nearSize(); i++)
                    nearEvicted.add(req.nearKey(i));
            }

            if (nearEvicted != null) {
                dhtRes = new GridDhtAtomicUpdateResponse(ctx.cacheId(),
                    req.partition(),
                    req.futureId(),
                    ctx.deploymentEnabled());

                dhtRes.nearEvicted(nearEvicted);
            }
        }

        try {
            // TODO handle failure: probably drop the node from topology
            // TODO fire events only after successful fsync
            if (ctx.shared().wal() != null)
                ctx.shared().wal().flush(null, false);
        }
        catch (StorageException e) {
            if (dhtRes != null)
                dhtRes.onError(new IgniteCheckedException(e));

            if (nearRes != null)
                nearRes.onClassError(e);
        }
        catch (IgniteCheckedException e) {
            if (dhtRes != null)
                dhtRes.onError(e);

            if (nearRes != null)
                nearRes.onClassError(e);
        }

        if (nearRes != null)
            sendDhtNearResponse(req, nearRes);

        if (dhtRes == null && req.replyWithoutDelay()) {
            dhtRes = new GridDhtAtomicUpdateResponse(ctx.cacheId(),
                req.partition(),
                req.futureId(),
                ctx.deploymentEnabled());
        }

        if (dhtRes != null)
            sendDhtPrimaryResponse(nodeId, req, dhtRes);
        else
            sendDeferredUpdateResponse(req.partition(), nodeId, req.futureId());
    }

    /**
     * @param nodeId Primary node ID.
     * @param req Request.
     * @param dhtRes Response to send.
     */
    private void sendDhtPrimaryResponse(UUID nodeId,
        GridDhtAtomicAbstractUpdateRequest req,
        GridDhtAtomicUpdateResponse dhtRes) {
        try {
            ctx.io().send(nodeId, dhtRes, ctx.ioPolicy());

            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Sent DHT response [futId=" + req.futureId() +
                    ", nearFutId=" + req.nearFutureId() +
                    ", writeVer=" + req.writeVersion() +
                    ", node=" + nodeId + ']');
            }
        }
        catch (ClusterTopologyCheckedException ignored) {
            U.warn(msgLog, "Failed to send DHT response, node left [futId=" + req.futureId() +
                ", nearFutId=" + req.nearFutureId() +
                ", node=" + nodeId + ']');
        }
        catch (IgniteCheckedException e) {
            U.error(msgLog, "Failed to send DHT near response [futId=" + req.futureId() +
                ", nearFutId=" + req.nearFutureId() +
                ", node=" + nodeId +
                ", res=" + dhtRes + ']', e);
        }
    }

    /**
     * @param part Partition.
     * @param primaryId Primary ID.
     * @param futId Future ID.
     */
    private void sendDeferredUpdateResponse(int part, UUID primaryId, long futId) {
        Map<UUID, GridDhtAtomicDeferredUpdateResponse> resMap = defRes.get();

        GridDhtAtomicDeferredUpdateResponse msg = resMap.get(primaryId);

        if (msg == null) {
            msg = new GridDhtAtomicDeferredUpdateResponse(ctx.cacheId(),
                new GridLongList(DEFERRED_UPDATE_RESPONSE_BUFFER_SIZE));

            if (DEFERRED_UPDATE_RESPONSE_TIMEOUT > 0) {
                GridTimeoutObject timeoutSnd = new DeferredUpdateTimeout(part, primaryId);

                msg.timeoutSender(timeoutSnd);

                ctx.time().addTimeoutObject(timeoutSnd);
            }

            resMap.put(primaryId, msg);
        }

        GridLongList futIds = msg.futureIds();

        assert futIds.size() < DEFERRED_UPDATE_RESPONSE_BUFFER_SIZE : futIds.size();

        futIds.add(futId);

        if (futIds.size() >= DEFERRED_UPDATE_RESPONSE_BUFFER_SIZE) {
            resMap.remove(primaryId);

            sendDeferredUpdateResponse(primaryId, msg);
        }
    }

    /**
     * @param primaryId Primary ID.
     * @param msg Message.
     */
    private void sendDeferredUpdateResponse(UUID primaryId, GridDhtAtomicDeferredUpdateResponse msg) {
        try {
            GridTimeoutObject timeoutSnd = msg.timeoutSender();

            if (timeoutSnd != null)
                ctx.time().removeTimeoutObject(timeoutSnd);

            ctx.io().send(primaryId, msg, ctx.ioPolicy());

            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Sent deferred DHT update response [futIds=" + msg.futureIds() +
                    ", node=" + primaryId + ']');
            }
        }
        catch (ClusterTopologyCheckedException ignored) {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Failed to send deferred DHT update response, node left [" +
                    "futIds=" + msg.futureIds() + ", node=" + primaryId + ']');
            }
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send deferredDHT  update response to remote node [" +
                "futIds=" + msg.futureIds() + ", node=" + primaryId + ']', e);
        }
    }

    /**
     * @param req Request.
     * @param nearRes Response to send.
     */
    private void sendDhtNearResponse(final GridDhtAtomicAbstractUpdateRequest req, GridDhtAtomicNearResponse nearRes) {
        try {
            ClusterNode node = ctx.discovery().node(req.nearNodeId());

            if (node == null)
                throw new ClusterTopologyCheckedException("Node failed: " + req.nearNodeId());

            if (node.isLocal())
                processDhtAtomicNearResponse(node.id(), nearRes);
            else
                ctx.io().send(node, nearRes, ctx.ioPolicy());

            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Sent DHT near response [futId=" + req.futureId() +
                    ", nearFutId=" + req.nearFutureId() +
                    ", writeVer=" + req.writeVersion() +
                    ", node=" + req.nearNodeId() + ']');
            }
        }
        catch (ClusterTopologyCheckedException ignored) {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Failed to send DHT near response, node left [futId=" + req.futureId() +
                    ", nearFutId=" + req.nearFutureId() +
                    ", node=" + req.nearNodeId() + ']');
            }
        }
        catch (IgniteCheckedException e) {
            U.error(msgLog, "Failed to send DHT near response [futId=" + req.futureId() +
                ", nearFutId=" + req.nearFutureId() +
                ", node=" + req.nearNodeId() +
                ", res=" + nearRes + ']', e);
        }
    }

    /**
     * @param nodeId Node ID.
     * @param res Response.
     */
    private void processDhtAtomicNearResponse(UUID nodeId, GridDhtAtomicNearResponse res) {
        GridNearAtomicAbstractUpdateFuture updateFut =
            (GridNearAtomicAbstractUpdateFuture)ctx.mvcc().atomicFuture(res.futureId());

        if (updateFut != null) {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Received DHT atomic near response [futId=" + res.futureId() +
                    ", node=" + nodeId + ']');
            }

            updateFut.onDhtResponse(nodeId, res);
        }
        else {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Failed to find future for DHT atomic near response [futId=" + res.futureId() +
                    ", node=" + nodeId +
                    ", res=" + res + ']');
            }
        }
    }

    /**
     * @param nodeId Sender node ID.
     * @param res Dht atomic update response.
     */
    private void processDhtAtomicUpdateResponse(UUID nodeId, GridDhtAtomicUpdateResponse res) {
        GridDhtAtomicAbstractUpdateFuture updateFut =
            (GridDhtAtomicAbstractUpdateFuture)ctx.mvcc().atomicFuture(res.futureId());

        if (updateFut != null) {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Received DHT atomic update response [futId=" + res.futureId() +
                        ", writeVer=" + updateFut.writeVersion() + ", node=" + nodeId + ']');
            }

            updateFut.onDhtResponse(nodeId, res);
        }
        else {
            U.warn(msgLog, "Failed to find DHT update future for update response [futId=" + res.futureId() +
                ", node=" + nodeId + ", res=" + res + ']');
        }
    }

    /**
     * @param nodeId Sender node ID.
     * @param res Deferred atomic update response.
     */
    private void processDhtAtomicDeferredUpdateResponse(UUID nodeId, GridDhtAtomicDeferredUpdateResponse res) {
        GridLongList futIds = res.futureIds();

        assert futIds != null && !futIds.isEmpty() : futIds;

        for (int i = 0; i < futIds.size(); i++) {
            long id = futIds.get(i);

            GridDhtAtomicAbstractUpdateFuture updateFut = (GridDhtAtomicAbstractUpdateFuture)ctx.mvcc().atomicFuture(id);

            if (updateFut != null) {
                if (msgLog.isDebugEnabled()) {
                    msgLog.debug("Received DHT atomic deferred update response [futId=" + id +
                        ", writeVer=" + res + ", node=" + nodeId + ']');
                }

                updateFut.onDeferredResponse(nodeId);
            }
            else {
                U.warn(msgLog, "Failed to find DHT update future for deferred update response [futId=" + id +
                    ", nodeId=" + nodeId + ", res=" + res + ']');
            }
        }
    }

    /**
     * @param nodeId Originating node ID.
     * @param res Near update response.
     */
    private void sendNearUpdateReply(UUID nodeId, GridNearAtomicUpdateResponse res) {
        try {
            ctx.io().send(nodeId, res, ctx.ioPolicy());

            if (msgLog.isDebugEnabled())
                msgLog.debug("Sent near update response [futId=" + res.futureId() + ", node=" + nodeId + ']');
        }
        catch (ClusterTopologyCheckedException ignored) {
            if (msgLog.isDebugEnabled()) {
                msgLog.debug("Failed to send near update response [futId=" + res.futureId() +
                    ", node=" + nodeId + ']');
            }
        }
        catch (IgniteCheckedException e) {
            U.error(msgLog, "Failed to send near update response [futId=" + res.futureId() +
                ", node=" + nodeId + ", res=" + res + ']', e);
        }
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridDhtAtomicCache.class, this, super.toString());
    }

    /**
     *
     */
    private static class FinishedLockFuture extends GridFinishedFuture<Boolean> implements GridDhtFuture<Boolean> {
        /**
         * @param err Error.
         */
        private FinishedLockFuture(Throwable err) {
            super(err);
        }

        /** {@inheritDoc} */
        @Override public Collection<Integer> invalidPartitions() {
            return Collections.emptyList();
        }
    }

    /**
     *
     */
    interface UpdateReplyClosure extends CI2<GridNearAtomicAbstractUpdateRequest, GridNearAtomicUpdateResponse> {
        // No-op.
    }

    /**
     *
     */
    private class DeferredUpdateTimeout implements GridTimeoutObject, Runnable {
        /** */
        private final int part;

        /** */
        private final UUID primaryId;

        /** */
        private final IgniteUuid id;

        /** */
        private final long endTime;

        /**
         * @param part Partition.
         * @param primaryId Primary ID.
         */
        DeferredUpdateTimeout(int part, UUID primaryId) {
            this.part = part;
            this.primaryId = primaryId;

            endTime = U.currentTimeMillis() + DEFERRED_UPDATE_RESPONSE_TIMEOUT;

            id = IgniteUuid.fromUuid(primaryId);
        }

        /** {@inheritDoc} */
        @Override public IgniteUuid timeoutId() {
            return id;
        }

        /** {@inheritDoc} */
        @Override public long endTime() {
            return endTime;
        }

        /** {@inheritDoc} */
        @Override public void run() {
            Map<UUID, GridDhtAtomicDeferredUpdateResponse> resMap = defRes.get();

            GridDhtAtomicDeferredUpdateResponse msg = resMap.get(primaryId);

            if (msg != null && msg.timeoutSender() == this) {
                msg.timeoutSender(null);

                resMap.remove(primaryId);

                sendDeferredUpdateResponse(primaryId, msg);
            }
        }

        /** {@inheritDoc} */
        @Override public void onTimeout() {
            ctx.kernalContext().pools().getStripedExecutorService().execute(part, this);
        }
    }
}
