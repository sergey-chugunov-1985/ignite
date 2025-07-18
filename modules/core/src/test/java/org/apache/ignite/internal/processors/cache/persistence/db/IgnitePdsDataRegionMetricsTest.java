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

package org.apache.ignite.internal.processors.cache.persistence.db;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.ignite.DataRegionMetrics;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.cache.GridCacheSharedContext;
import org.apache.ignite.internal.processors.cache.persistence.DataRegionMetricsImpl;
import org.apache.ignite.internal.processors.cache.persistence.GridCacheDatabaseSharedManager;
import org.apache.ignite.internal.processors.cache.persistence.file.FilePageStore;
import org.apache.ignite.internal.processors.cache.persistence.file.FilePageStoreManager;
import org.apache.ignite.internal.processors.cache.persistence.filename.NodeFileTree;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.spi.metric.LongMetric;
import org.apache.ignite.spi.metric.ReadOnlyMetricRegistry;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Assert;
import org.junit.Test;

import static java.nio.file.Files.newDirectoryStream;
import static org.apache.ignite.configuration.DataStorageConfiguration.DFLT_DATA_REG_DEFAULT_NAME;
import static org.apache.ignite.events.EventType.EVT_PAGE_REPLACEMENT_STARTED;
import static org.apache.ignite.internal.processors.cache.GridCacheUtils.UTILITY_CACHE_NAME;
import static org.apache.ignite.internal.processors.cache.persistence.DataRegionMetricsImpl.DATAREGION_METRICS_PREFIX;
import static org.apache.ignite.internal.processors.cache.persistence.filename.NodeFileTree.partId;
import static org.apache.ignite.internal.processors.cache.persistence.metastorage.MetaStorage.METASTORAGE_CACHE_ID;
import static org.apache.ignite.internal.processors.cache.persistence.metastorage.MetaStorage.METASTORAGE_CACHE_NAME;
import static org.apache.ignite.internal.processors.metric.impl.MetricUtils.metricName;

/**
 *
 */
public class IgnitePdsDataRegionMetricsTest extends GridCommonAbstractTest {
    /** */
    private static final String SMALL_REGION = "SmallRegion";

    /** */
    private static final long INIT_REGION_SIZE = 20 << 20;

    /** */
    private static final long MAX_REGION_SIZE = INIT_REGION_SIZE * 10;

    /** */
    private static final int ITERATIONS = 3;

    /** */
    private static final int BATCHES = 5;

    /** */
    private static final int BATCH_SIZE_LOW = 100;

    /** */
    private static final int BATCH_SIZE_HIGH = 1000;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setIncludeEventTypes(EVT_PAGE_REPLACEMENT_STARTED);

        DataStorageConfiguration memCfg = new DataStorageConfiguration()
            .setDefaultDataRegionConfiguration(
                new DataRegionConfiguration()
                    .setInitialSize(INIT_REGION_SIZE)
                    .setMaxSize(MAX_REGION_SIZE)
                    .setPersistenceEnabled(true)
                    .setMetricsEnabled(true))
            .setDataRegionConfigurations(
                new DataRegionConfiguration()
                    .setName("EmptyRegion")
                    .setInitialSize(INIT_REGION_SIZE)
                    .setMaxSize(MAX_REGION_SIZE)
                    .setPersistenceEnabled(true)
                    .setMetricsEnabled(true))
            .setDataRegionConfigurations(
                new DataRegionConfiguration()
                    .setName(SMALL_REGION)
                    .setMaxSize(INIT_REGION_SIZE)
                    .setPersistenceEnabled(true)
                    .setMetricsEnabled(true))
            .setCheckpointFrequency(1000);

        cfg.setDataStorageConfiguration(memCfg);

        CacheConfiguration<Object, Object> ccfg = cacheConfiguration();

        cfg.setCacheConfiguration(ccfg);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        super.beforeTestsStarted();
    }

    /** {@inheritDoc} */
    @Override protected void afterTestsStopped() throws Exception {
        super.afterTestsStopped();
    }

    /**
     * @return Ignite cache configuration.
     */
    protected CacheConfiguration<Object, Object> cacheConfiguration() {
        return (CacheConfiguration<Object, Object>)new CacheConfiguration<>(DEFAULT_CACHE_NAME)
                .setCacheMode(CacheMode.PARTITIONED)
                .setBackups(1);
    }

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        cleanPersistenceDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        cleanPersistenceDir();

        super.afterTest();
    }

    /** */
    @Test
    public void testMemoryUsageSingleNode() throws Exception {
        DataRegionMetrics initMetrics = null;

        for (int iter = 0; iter < ITERATIONS; iter++) {
            final IgniteEx node = startGrid(0);

            node.cluster().state(ClusterState.ACTIVE);

            DataRegionMetrics currMetrics = getDfltRegionMetrics(node);

            if (initMetrics == null)
                initMetrics = currMetrics;

            assertTrue(currMetrics.getTotalAllocatedPages() >= currMetrics.getPhysicalMemoryPages());

            final IgniteCache<String, String> cache = node.cache(DEFAULT_CACHE_NAME);

            Map<String, String> map = new HashMap<>();

            for (int batch = 0; batch < BATCHES; batch++) {
                int nPuts = BATCH_SIZE_LOW + ThreadLocalRandom.current().nextInt(BATCH_SIZE_HIGH - BATCH_SIZE_LOW);

                for (int i = 0; i < nPuts; i++)
                    map.put(UUID.randomUUID().toString(), UUID.randomUUID().toString());

                cache.putAll(map);

                forceCheckpoint(node);

                checkMetricsConsistency(node);
            }

            currMetrics = getDfltRegionMetrics(node);

            // Make sure metrics are rising
            assertTrue(currMetrics.getPhysicalMemoryPages() > initMetrics.getPhysicalMemoryPages());
            assertTrue(currMetrics.getTotalAllocatedPages() > initMetrics.getTotalAllocatedPages());

            stopGrid(0, true);
        }
    }

    /** */
    @Test
    public void testMemoryUsageMultipleNodes() throws Exception {
        IgniteEx node0 = startGrid(0);
        IgniteEx node1 = startGrid(1);

        node0.cluster().baselineAutoAdjustEnabled(false);
        node0.cluster().state(ClusterState.ACTIVE);

        final IgniteCache<Integer, String> cache = node0.cache(DEFAULT_CACHE_NAME);

        Map<Integer, String> map = new HashMap<>();

        for (int i = 0; i < 10_000; i++)
            map.put(i, UUID.randomUUID().toString());

        cache.putAll(map);

        awaitPartitionMapExchange(true, true, null);

        forceCheckpoint();

        checkMetricsConsistency(node0);
        checkMetricsConsistency(node1);

        IgniteEx node2 = startGrid(2);

        resetBaselineTopology();

        awaitPartitionMapExchange(true, true, null);

        forceCheckpoint();

        checkMetricsConsistency(node0);
        checkMetricsConsistency(node1);
        checkMetricsConsistency(node2);

        stopGrid(1, true);

        resetBaselineTopology();

        awaitPartitionMapExchange(true, true, null);

        forceCheckpoint();

        checkMetricsConsistency(node0);
        checkMetricsConsistency(node2);
    }

    /**
     * Test for check checkpoint size metric.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCheckpointBufferSize() throws Exception {
        IgniteEx ig = startGrid(0);

        ig.cluster().state(ClusterState.ACTIVE);

        DataRegionMetricsImpl regionMetrics = ig.cachex(DEFAULT_CACHE_NAME)
            .context().group().dataRegion().metrics();

        Assert.assertTrue(regionMetrics.getCheckpointBufferSize() != 0);
        Assert.assertTrue(regionMetrics.getCheckpointBufferSize() <= MAX_REGION_SIZE);
    }

    /**
     * Test for check used checkpoint size metric.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testUsedCheckpointBuffer() throws Exception {
        IgniteEx ig = startGrid(0);

        ig.cluster().state(ClusterState.ACTIVE);

        final DataRegionMetricsImpl regionMetrics = ig.cachex(DEFAULT_CACHE_NAME)
            .context().group().dataRegion().metrics();

        Assert.assertEquals(0, regionMetrics.getUsedCheckpointBufferPages());
        Assert.assertEquals(0, regionMetrics.getUsedCheckpointBufferSize());

        load(ig);

        GridCacheDatabaseSharedManager psMgr = (GridCacheDatabaseSharedManager)ig.context().cache().context().database();

        GridFutureAdapter<T2<Long, Long>> metricsResult = new GridFutureAdapter<>();

        IgniteInternalFuture chpBeginFut = psMgr.wakeupForCheckpoint(null);

        chpBeginFut.listen(() -> {
            load(ig);

            metricsResult.onDone(new T2<>(
                regionMetrics.getUsedCheckpointBufferPages(),
                regionMetrics.getUsedCheckpointBufferSize()
            ));
        });

        metricsResult.get();

        Assert.assertTrue(metricsResult.get().get1() > 0);
        Assert.assertTrue(metricsResult.get().get2() > 0);
    }

    /**
     *
     */
    @Test
    public void testReadMetrics() throws Exception {
        int cnt = 100;

        IgniteEx ignite = startGrid();

        ignite.cluster().state(ClusterState.ACTIVE);

        IgniteCache<Object, Object> cache = ignite.getOrCreateCache(new CacheConfiguration<>("smallRegionCache")
            .setDataRegionName(SMALL_REGION));

        for (int i = 0; i < cnt; i++)
            cache.put(i, i);

        ignite.cluster().state(ClusterState.INACTIVE);

        ignite.cluster().state(ClusterState.ACTIVE);

        ReadOnlyMetricRegistry mreg = ignite.context().metric().registry(metricName(DATAREGION_METRICS_PREFIX,
            SMALL_REGION));

        LongMetric readPages = mreg.findMetric("PagesRead");
        LongMetric readPagesTime = mreg.findMetric("PagesReadTime");
        LongMetric replPages = mreg.findMetric("PagesReplaced");
        LongMetric replPagesTime = mreg.findMetric("PagesReplaceTime");

        assertTrue(readPages.value() > 0);
        assertTrue(readPagesTime.value() > 0);
        assertEquals(0, replPages.value());
        assertEquals(0, replPagesTime.value());

        AtomicBoolean replacementStarted = new AtomicBoolean();

        ignite.events().remoteListen((uuid, evt) -> {
            if (evt.type() == EVT_PAGE_REPLACEMENT_STARTED) {
                replacementStarted.set(true);

                return false;
            }

            return true;
        }, null, EVT_PAGE_REPLACEMENT_STARTED);

        while (!replacementStarted.get())
            cache.put(cnt++, new byte[1000]);

        assertTrue(replPages.value() > 0);
        assertTrue(replPagesTime.value() > 0);
    }

    /**
     * @param ig Ignite.
     */
    private void load(Ignite ig) {
        IgniteCache<Integer, byte[]> cache = ig.cache(DEFAULT_CACHE_NAME);

        Random rnd = new Random();

        for (int i = 0; i < 1000; i++) {
            byte[] payload = new byte[128];

            rnd.nextBytes(payload);

            cache.put(i, payload);
        }
    }

    /** */
    private static DataRegionMetrics getDfltRegionMetrics(Ignite node) {
        for (DataRegionMetrics m : node.dataRegionMetrics())
            if (DFLT_DATA_REG_DEFAULT_NAME.equals(m.getName()))
                return m;

        throw new RuntimeException("No metrics found for default data region");
    }

    /** */
    private void checkMetricsConsistency(final IgniteEx node) throws Exception {
        checkMetricsConsistency(node, DEFAULT_CACHE_NAME);
        checkMetricsConsistency(node, UTILITY_CACHE_NAME);
        checkMetricsConsistency(node, METASTORAGE_CACHE_NAME);
    }

    /** */
    private void checkMetricsConsistency(final IgniteEx node, String cacheName) throws Exception {
        FilePageStoreManager pageStoreMgr = (FilePageStoreManager)node.context().cache().context().pageStore();
        NodeFileTree ft = node.context().pdsFolderResolver().fileTree();

        assert pageStoreMgr != null : "Persistence is not enabled";

        boolean metaStore = METASTORAGE_CACHE_NAME.equals(cacheName);

        File cacheWorkDir = metaStore
            ? ft.metaStorage()
            : ft.defaultCacheStorage(node.cachex(cacheName).configuration());

        long totalPersistenceSize = 0;

        try (DirectoryStream<Path> files = newDirectoryStream(
            cacheWorkDir.toPath(), entry -> entry.toFile().getName().endsWith(".bin"))
        ) {
            for (Path path : files) {
                File file = path.toFile();

                FilePageStore store = (FilePageStore)pageStoreMgr.getStore(metaStore ?
                    METASTORAGE_CACHE_ID : CU.cacheId(cacheName), partId(file));

                int pageSize = store.getPageSize();
                long storeSize = path.toFile().length() - store.headerSize();

                if (storeSize % pageSize != 0)
                    storeSize = (storeSize / pageSize + 1) * pageSize; // Adjust for possible page compression.

                totalPersistenceSize += storeSize;
            }
        }

        GridCacheSharedContext cctx = node.context().cache().context();

        String regionName = metaStore ? GridCacheDatabaseSharedManager.METASTORE_DATA_REGION_NAME :
            cctx.cacheContext(CU.cacheId(cacheName)).group().dataRegion().config().getName();

        long totalAllocatedPagesFromMetrics = cctx.database().memoryMetrics(regionName).getTotalAllocatedPages();

        assertEquals("Number of allocated pages is different than in metrics for [node=" + node.name() + ", cache=" + cacheName + "]",
            totalPersistenceSize / pageStoreMgr.pageSize(), totalAllocatedPagesFromMetrics);
    }
}
