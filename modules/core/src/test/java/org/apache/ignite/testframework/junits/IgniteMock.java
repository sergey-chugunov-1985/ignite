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

package org.apache.ignite.testframework.junits;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.cache.CacheException;
import javax.management.MBeanServer;
import org.apache.ignite.DataRegionMetrics;
import org.apache.ignite.DataRegionMetricsAdapter;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteAtomicLong;
import org.apache.ignite.IgniteAtomicReference;
import org.apache.ignite.IgniteAtomicSequence;
import org.apache.ignite.IgniteAtomicStamped;
import org.apache.ignite.IgniteBinary;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteCompute;
import org.apache.ignite.IgniteCountDownLatch;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.IgniteEncryption;
import org.apache.ignite.IgniteEvents;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLock;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.IgniteMessaging;
import org.apache.ignite.IgniteQueue;
import org.apache.ignite.IgniteScheduler;
import org.apache.ignite.IgniteSemaphore;
import org.apache.ignite.IgniteServices;
import org.apache.ignite.IgniteSet;
import org.apache.ignite.IgniteSnapshot;
import org.apache.ignite.IgniteTransactions;
import org.apache.ignite.MemoryMetrics;
import org.apache.ignite.binary.BinaryObjectBuilder;
import org.apache.ignite.binary.BinaryObjectException;
import org.apache.ignite.cache.affinity.Affinity;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.AtomicConfiguration;
import org.apache.ignite.configuration.BinaryConfiguration;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.CollectionConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.configuration.NearCacheConfiguration;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.binary.BinaryContext;
import org.apache.ignite.internal.binary.BinaryMarshaller;
import org.apache.ignite.internal.binary.BinaryUtils;
import org.apache.ignite.internal.binary.builder.BinaryObjectBuilders;
import org.apache.ignite.internal.cluster.IgniteClusterEx;
import org.apache.ignite.internal.management.IgniteCommandRegistry;
import org.apache.ignite.internal.processors.cache.GridCacheUtilityKey;
import org.apache.ignite.internal.processors.cache.IgniteInternalCache;
import org.apache.ignite.internal.processors.cache.persistence.wal.reader.StandaloneGridKernalContext;
import org.apache.ignite.internal.processors.cacheobject.NoOpBinary;
import org.apache.ignite.internal.processors.subscription.GridInternalSubscriptionProcessor;
import org.apache.ignite.internal.processors.tracing.configuration.NoopTracingConfigurationManager;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.lang.IgniteProductVersion;
import org.apache.ignite.logger.NullLogger;
import org.apache.ignite.marshaller.Marshaller;
import org.apache.ignite.metric.IgniteMetrics;
import org.apache.ignite.plugin.IgnitePlugin;
import org.apache.ignite.plugin.PluginNotFoundException;
import org.apache.ignite.spi.tracing.TracingConfigurationManager;
import org.apache.ignite.testframework.junits.logger.GridTestLog4jLogger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Ignite mock.
 */
public class IgniteMock implements IgniteEx {
    /** Ignite name */
    private final String name;

    /** Local host. */
    private final String locHost;

    /** */
    private final UUID nodeId;

    /** */
    private Marshaller marshaller;

    /** */
    private final MBeanServer jmx;

    /** */
    private final String home;

    /** */
    private IgniteConfiguration staticCfg;

    /** */
    private IgniteBinary binaryMock;

    /** */
    private BinaryContext ctx;

    /** */
    private final GridKernalContext kernalCtx;

    /**
     * Mock values
     *
     * @param name Name.
     * @param locHost Local host.
     * @param nodeId Node ID.
     * @param marshaller Marshaller.
     * @param jmx Jmx Bean Server.
     * @param home Ignite home.
     */
    public IgniteMock(
        String name, String locHost, UUID nodeId, Marshaller marshaller, MBeanServer jmx, String home, IgniteConfiguration staticCfg) {
        this.locHost = locHost;
        this.nodeId = nodeId;
        this.marshaller = marshaller;
        this.jmx = jmx;
        this.home = home;
        this.name = name;
        this.staticCfg = staticCfg;

        try {
            kernalCtx = new StandaloneGridKernalContext(new GridTestLog4jLogger(), null) {
                @Override public GridInternalSubscriptionProcessor internalSubscriptionProcessor() {
                    return new GridInternalSubscriptionProcessor(this);
                }
            };
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public String name() {
        return name;
    }

    /** {@inheritDoc} */
    @Override public IgniteLogger log() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteConfiguration configuration() {
        if (staticCfg != null)
            return staticCfg;

        IgniteConfiguration cfg = new IgniteConfiguration();

        cfg.setNodeId(nodeId);
        cfg.setMBeanServer(jmx);
        cfg.setIgniteHome(home);
        cfg.setLocalHost(locHost);

        try {
            cfg.setWorkDirectory(U.defaultWorkDirectory());
        }
        catch (IgniteCheckedException e) {
            throw new IgniteException("Failed to get default work directory.", e);
        }

        return cfg;
    }

    /** {@inheritDoc} */
    @Override public <K extends GridCacheUtilityKey, V> IgniteInternalCache<K, V> utilityCache() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public @Nullable <K, V> IgniteInternalCache<K, V> cachex(String name) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public Collection<IgniteInternalCache<?, ?>> cachesx(@Nullable IgnitePredicate<? super IgniteInternalCache<?, ?>>... p) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteBiTuple<IgniteCache<K, V>, Boolean> getOrCreateCache0(
        CacheConfiguration<K, V> cacheCfg,
        boolean sql
    ) throws CacheException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public boolean destroyCache0(String cacheName, boolean sql) throws CacheException {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean eventUserRecordable(int type) {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean allEventsUserRecordable(int[] types) {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isJmxRemoteEnabled() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public boolean isRestartEnabled() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public IgniteClusterEx cluster() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public @Nullable String latestVersion() {
        return "";
    }

    /** {@inheritDoc} */
    @Override public ClusterNode localNode() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public GridKernalContext context() {
        return kernalCtx;
    }

    /** {@inheritDoc} */
    @Override public boolean isRebalanceEnabled() {
        return false;
    }

    /** {@inheritDoc} */
    @Override public void rebalanceEnabled(boolean rebalanceEnabled) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public <T> IgniteSet<T> set(
        String name,
        int cacheId,
        boolean collocated,
        boolean separated
    ) throws IgniteException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteCommandRegistry commandsRegistry() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteCompute compute() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteMetrics metrics() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteCompute compute(ClusterGroup grp) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteMessaging message() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteMessaging message(ClusterGroup prj) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteEvents events() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteEvents events(ClusterGroup grp) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteServices services() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteServices services(ClusterGroup grp) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public ExecutorService executorService() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public ExecutorService executorService(ClusterGroup grp) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteProductVersion version() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteScheduler scheduler() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> cache(@Nullable String name) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public Collection<String> cacheNames() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> createCache(CacheConfiguration<K, V> cacheCfg) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public Collection<IgniteCache> createCaches(Collection<CacheConfiguration> cacheCfgs) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> getOrCreateCache(CacheConfiguration<K, V> cacheCfg) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> createCache(CacheConfiguration<K, V> cacheCfg,
        @Nullable NearCacheConfiguration<K, V> nearCfg) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> createNearCache(String cacheName, NearCacheConfiguration<K, V> nearCfg) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> getOrCreateCache(CacheConfiguration<K, V> cacheCfg, NearCacheConfiguration<K, V> nearCfg) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> getOrCreateNearCache(@Nullable String cacheName, NearCacheConfiguration<K, V> nearCfg) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> getOrCreateCache(String cacheName) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public Collection<IgniteCache> getOrCreateCaches(Collection<CacheConfiguration> cacheCfgs) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteCache<K, V> createCache(String cacheName) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> void addCacheConfiguration(CacheConfiguration<K, V> cacheCfg) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void destroyCache(String cacheName) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void destroyCaches(Collection<String> cacheNames) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public IgniteTransactions transactions() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K, V> IgniteDataStreamer<K, V> dataStreamer(@Nullable String cacheName) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <T extends IgnitePlugin> T plugin(String name) throws PluginNotFoundException {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteBinary binary() {
        if (binaryMock != null)
            return binaryMock;

        if (ctx == null) {
            IgniteConfiguration cfg = configuration();

            BinaryConfiguration bcfg = cfg.getBinaryConfiguration() == null ? new BinaryConfiguration() : cfg.getBinaryConfiguration();

            /** {@inheritDoc} */
            ctx = new BinaryContext(
                BinaryUtils.cachingMetadataHandler(),
                (BinaryMarshaller)marshaller,
                cfg.getIgniteInstanceName(),
                cfg.getClassLoader(),
                bcfg.getSerializer(),
                bcfg.getIdMapper(),
                bcfg.getNameMapper(),
                bcfg.getTypeConfigurations(),
                CU.affinityFields(configuration()),
                bcfg.isCompactFooter(),
                NullLogger.INSTANCE
            ) {
                @Override public int typeId(String typeName) {
                    return typeName.hashCode();
                }
            };
        }

        binaryMock = new NoOpBinary() {
            /** {@inheritDoc} */
            @Override public int typeId(String typeName) {
                return typeName.hashCode();
            }

            /** {@inheritDoc} */
            @Override public BinaryObjectBuilder builder(String typeName) throws BinaryObjectException {
                return BinaryObjectBuilders.builder(ctx, typeName);
            }
        };

        return binaryMock;
    }

    /** {@inheritDoc} */
    @Override public void close() {}

    /** {@inheritDoc} */
    @Nullable @Override public IgniteAtomicSequence atomicSequence(String name, long initVal, boolean create) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteAtomicSequence atomicSequence(String name, AtomicConfiguration cfg, long initVal,
        boolean create) throws IgniteException {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public IgniteAtomicLong atomicLong(String name, long initVal, boolean create) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteAtomicLong atomicLong(String name, AtomicConfiguration cfg, long initVal,
        boolean create) throws IgniteException {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public <T> IgniteAtomicReference<T> atomicReference(String name,
        @Nullable T initVal,
        boolean create
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <T> IgniteAtomicReference<T> atomicReference(String name, AtomicConfiguration cfg,
        @Nullable T initVal, boolean create) throws IgniteException {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public <T, S> IgniteAtomicStamped<T, S> atomicStamped(String name,
        @Nullable T initVal,
        @Nullable S initStamp,
        boolean create
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <T, S> IgniteAtomicStamped<T, S> atomicStamped(String name, AtomicConfiguration cfg,
        @Nullable T initVal, @Nullable S initStamp, boolean create) throws IgniteException {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public IgniteCountDownLatch countDownLatch(String name,
        int cnt,
        boolean autoDel,
        boolean create
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public IgniteSemaphore semaphore(String name,
        int cnt,
        boolean failoverSafe,
        boolean create
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public IgniteLock reentrantLock(String name,
        boolean failoverSafe,
        boolean fair,
        boolean create
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public <T> IgniteQueue<T> queue(String name,
        int cap,
        CollectionConfiguration cfg
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public <T> IgniteSet<T> set(String name,
        CollectionConfiguration cfg
    ) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public <K> Affinity<K> affinity(String cacheName) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public boolean active() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public void active(boolean active) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public void resetLostPartitions(Collection<String> cacheNames) {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public Collection<DataRegionMetrics> dataRegionMetrics() {
        return null;
    }

    /** {@inheritDoc} */
    @Nullable @Override public DataRegionMetrics dataRegionMetrics(String memPlcName) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteEncryption encryption() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public IgniteSnapshot snapshot() {
        return null;
    }

    /** {@inheritDoc} */
    @Override public @NotNull TracingConfigurationManager tracingConfiguration() {
        return NoopTracingConfigurationManager.INSTANCE;
    }

    /** {@inheritDoc} */
    @Override public Ignite withApplicationAttributes(Map<String, String> attrs) {
        return null;
    }

    /** {@inheritDoc} */
    @Override public Collection<MemoryMetrics> memoryMetrics() {
        return DataRegionMetricsAdapter.collectionOf(dataRegionMetrics());
    }

    /** {@inheritDoc} */
    @Nullable @Override public MemoryMetrics memoryMetrics(String memPlcName) {
        return DataRegionMetricsAdapter.valueOf(dataRegionMetrics(memPlcName));
    }

    /**
     * @param staticCfg Configuration.
     */
    public void setStaticCfg(IgniteConfiguration staticCfg) {
        this.staticCfg = staticCfg;
    }
}
