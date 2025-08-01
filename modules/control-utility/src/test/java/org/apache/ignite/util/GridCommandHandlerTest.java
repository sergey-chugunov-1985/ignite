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

package org.apache.ignite.util;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.MutableEntry;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteAtomicSequence;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.IgniteException;
import org.apache.ignite.ShutdownPolicy;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.cluster.BaselineNode;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.cluster.ClusterState;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.GridJobExecuteResponse;
import org.apache.ignite.internal.IgniteEx;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.TestRecordingCommunicationSpi;
import org.apache.ignite.internal.dto.IgniteDataTransferObject;
import org.apache.ignite.internal.management.api.Argument;
import org.apache.ignite.internal.management.api.OfflineCommand;
import org.apache.ignite.internal.management.cache.FindAndDeleteGarbageInPersistenceTaskResult;
import org.apache.ignite.internal.management.cache.IdleVerifyDumpTask;
import org.apache.ignite.internal.management.cache.VerifyBackupPartitionsTask;
import org.apache.ignite.internal.management.tx.TxInfo;
import org.apache.ignite.internal.management.tx.TxTaskResult;
import org.apache.ignite.internal.managers.communication.GridIoMessage;
import org.apache.ignite.internal.processors.cache.ClusterStateTestUtils;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheEntryEx;
import org.apache.ignite.internal.processors.cache.GridCacheMvccCandidate;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTxFinishRequest;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearLockResponse;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearTxFinishRequest;
import org.apache.ignite.internal.processors.cache.distributed.near.GridNearTxLocal;
import org.apache.ignite.internal.processors.cache.persistence.GridCacheDatabaseSharedManager;
import org.apache.ignite.internal.processors.cache.persistence.checkpoint.CheckpointListener;
import org.apache.ignite.internal.processors.cache.persistence.db.IgniteCacheGroupsWithRestartsTest;
import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.dumpprocessors.ToFileDumpProcessor;
import org.apache.ignite.internal.processors.cache.persistence.file.FileIO;
import org.apache.ignite.internal.processors.cache.persistence.file.FileIOFactory;
import org.apache.ignite.internal.processors.cache.persistence.snapshot.DataStreamerUpdatesHandler;
import org.apache.ignite.internal.processors.cache.persistence.snapshot.IgniteSnapshotManager;
import org.apache.ignite.internal.processors.cache.persistence.snapshot.SnapshotPartitionsQuickVerifyHandler;
import org.apache.ignite.internal.processors.cache.persistence.snapshot.SnapshotPartitionsVerifyTaskResult;
import org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxEntry;
import org.apache.ignite.internal.processors.cache.transactions.TransactionProxyImpl;
import org.apache.ignite.internal.processors.cache.warmup.BlockedWarmUpConfiguration;
import org.apache.ignite.internal.processors.cache.warmup.BlockedWarmUpStrategy;
import org.apache.ignite.internal.processors.cache.warmup.WarmUpTestPluginProvider;
import org.apache.ignite.internal.processors.cluster.ChangeGlobalStateFinishMessage;
import org.apache.ignite.internal.processors.cluster.GridClusterStateProcessor;
import org.apache.ignite.internal.processors.datastreamer.DataStreamerRequest;
import org.apache.ignite.internal.processors.metric.MetricRegistryImpl;
import org.apache.ignite.internal.util.BasicRateLimiter;
import org.apache.ignite.internal.util.GridConcurrentHashSet;
import org.apache.ignite.internal.util.distributed.DistributedProcess;
import org.apache.ignite.internal.util.distributed.SingleNodeMessage;
import org.apache.ignite.internal.util.future.IgniteFinishedFutureImpl;
import org.apache.ignite.internal.util.lang.GridAbsPredicate;
import org.apache.ignite.internal.util.lang.GridFunc;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.G;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.lang.IgnitePredicate;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.metric.MetricRegistry;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.metric.LongMetric;
import org.apache.ignite.spi.metric.Metric;
import org.apache.ignite.spi.systemview.view.ComputeJobView;
import org.apache.ignite.spi.systemview.view.ComputeTaskView;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.ListeningTestLogger;
import org.apache.ignite.testframework.LogListener;
import org.apache.ignite.testframework.junits.WithSystemProperty;
import org.apache.ignite.transactions.Transaction;
import org.apache.ignite.transactions.TransactionRollbackException;
import org.apache.ignite.transactions.TransactionTimeoutException;
import org.jetbrains.annotations.NotNull;
import org.junit.Assume;
import org.junit.Test;

import static java.io.File.separatorChar;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_CLUSTER_NAME;
import static org.apache.ignite.cache.CacheAtomicityMode.TRANSACTIONAL;
import static org.apache.ignite.cache.CacheMode.PARTITIONED;
import static org.apache.ignite.cache.CacheWriteSynchronizationMode.FULL_SYNC;
import static org.apache.ignite.cache.PartitionLossPolicy.READ_ONLY_SAFE;
import static org.apache.ignite.cluster.ClusterState.ACTIVE;
import static org.apache.ignite.cluster.ClusterState.ACTIVE_READ_ONLY;
import static org.apache.ignite.cluster.ClusterState.INACTIVE;
import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.internal.commandline.CommandHandler.CONFIRM_MSG;
import static org.apache.ignite.internal.commandline.CommandHandler.EXIT_CODE_COMPLETED_WITH_WARNINGS;
import static org.apache.ignite.internal.commandline.CommandHandler.EXIT_CODE_INVALID_ARGUMENTS;
import static org.apache.ignite.internal.commandline.CommandHandler.EXIT_CODE_OK;
import static org.apache.ignite.internal.commandline.CommandHandler.EXIT_CODE_UNEXPECTED_ERROR;
import static org.apache.ignite.internal.encryption.AbstractEncryptionTest.MASTER_KEY_NAME_2;
import static org.apache.ignite.internal.management.cache.CacheIdleVerifyCancelTask.TASKS_TO_CANCEL;
import static org.apache.ignite.internal.management.cache.VerifyBackupPartitionsTask.CACL_PART_HASH_ERR_MSG;
import static org.apache.ignite.internal.management.cache.VerifyBackupPartitionsTask.CP_REASON;
import static org.apache.ignite.internal.processors.cache.persistence.GridCacheDatabaseSharedManager.IGNITE_PDS_SKIP_CHECKPOINT_ON_NODE_STOP;
import static org.apache.ignite.internal.processors.cache.persistence.snapshot.AbstractSnapshotSelfTest.doSnapshotCancellationTest;
import static org.apache.ignite.internal.processors.cache.persistence.snapshot.AbstractSnapshotSelfTest.snp;
import static org.apache.ignite.internal.processors.cache.persistence.snapshot.IgniteSnapshotManager.SNAPSHOT_LIMITED_TRANSFER_BLOCK_SIZE_BYTES;
import static org.apache.ignite.internal.processors.cache.persistence.snapshot.IgniteSnapshotManager.SNAPSHOT_METRICS;
import static org.apache.ignite.internal.processors.cache.persistence.snapshot.IgniteSnapshotManager.SNAPSHOT_TRANSFER_RATE_DMS_KEY;
import static org.apache.ignite.internal.processors.cache.persistence.snapshot.SnapshotRestoreProcess.SNAPSHOT_RESTORE_METRICS;
import static org.apache.ignite.internal.processors.cache.verify.IdleVerifyUtility.CRC_CHECK_ERR_MSG;
import static org.apache.ignite.internal.processors.cache.verify.IdleVerifyUtility.GRID_NOT_IDLE_MSG;
import static org.apache.ignite.internal.processors.diagnostic.DiagnosticProcessor.DEFAULT_TARGET_FOLDER;
import static org.apache.ignite.internal.processors.job.GridJobProcessor.JOBS_VIEW;
import static org.apache.ignite.internal.processors.task.GridTaskProcessor.TASKS_VIEW;
import static org.apache.ignite.testframework.GridTestUtils.assertContains;
import static org.apache.ignite.testframework.GridTestUtils.assertNotContains;
import static org.apache.ignite.testframework.GridTestUtils.runAsync;
import static org.apache.ignite.testframework.GridTestUtils.waitForCondition;
import static org.apache.ignite.transactions.TransactionConcurrency.OPTIMISTIC;
import static org.apache.ignite.transactions.TransactionConcurrency.PESSIMISTIC;
import static org.apache.ignite.transactions.TransactionIsolation.READ_COMMITTED;
import static org.apache.ignite.util.TestCommandsProvider.registerCommands;
import static org.apache.ignite.util.TestCommandsProvider.unregisterAll;
import static org.apache.ignite.util.TestStorageUtils.corruptDataEntry;

/**
 * Command line handler test.
 * You can use this class if you need create nodes for each test.
 * If you not necessary create nodes for each test you can try use {@link GridCommandHandlerClusterByClassTest}
 */
public class GridCommandHandlerTest extends GridCommandHandlerClusterPerMethodAbstractTest {
    /** Partitioned cache name. */
    protected static final String PARTITIONED_CACHE_NAME = "part_cache";

    /** Replicated cache name. */
    protected static final String REPLICATED_CACHE_NAME = "repl_cache";

    /** */
    public static final String CACHE_GROUP_KEY_IDS = "cache_key_ids";

    /** */
    public static final String CHANGE_CACHE_GROUP_KEY = "change_cache_key";

    /** */
    public static final String REENCRYPTION_RATE = "reencryption_rate_limit";

    /** */
    public static final String REENCRYPTION_RESUME = "resume_reencryption";

    /** */
    public static final String REENCRYPTION_STATUS = "reencryption_status";

    /** */
    public static final String REENCRYPTION_SUSPEND = "suspend_reencryption";

    /** */
    protected static File defaultDiagnosticDir;

    /** */
    protected static File customDiagnosticDir;

    /** */
    protected ListeningTestLogger listeningLog;

    /** {@inheritDoc} */
    @Override protected void beforeTest() throws Exception {
        super.beforeTest();

        initDiagnosticDir();

        cleanDiagnosticDir();
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        super.afterTest();

        listeningLog = null;
    }

    /** {@inheritDoc} */
    @Override protected void cleanPersistenceDir() throws Exception {
        super.cleanPersistenceDir();

        cleanDiagnosticDir();
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        if (listeningLog != null)
            cfg.setGridLogger(listeningLog);

        return cfg;
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    protected void initDiagnosticDir() throws IgniteCheckedException {
        defaultDiagnosticDir = new File(U.defaultWorkDirectory()
            + separatorChar + DEFAULT_TARGET_FOLDER + separatorChar);

        customDiagnosticDir = new File(U.defaultWorkDirectory()
            + separatorChar + "diagnostic_test_dir" + separatorChar);
    }

    /**
     * Clean diagnostic directories.
     */
    protected void cleanDiagnosticDir() {
        U.delete(defaultDiagnosticDir);
        U.delete(customDiagnosticDir);
    }

    /**
     * Test activation works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testActivate() throws Exception {
        Ignite ignite = startGrids(1);

        injectTestSystemOut();

        assertEquals(INACTIVE, ignite.cluster().state());

        assertEquals(EXIT_CODE_OK, execute("--activate"));

        assertEquals(ACTIVE, ignite.cluster().state());

        if (cliCommandHandler())
            assertContains(log, testOut.toString(), "Command deprecated. Use --set-state instead.");
    }

    /**
     * Test enabling/disabling read-only mode works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testReadOnlyEnableDisable() throws Exception {
        Ignite ignite = startGrids(1);

        ignite.cluster().state(ACTIVE);

        assertEquals(ACTIVE, ignite.cluster().state());

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--set-state", "ACTIVE_READ_ONLY"));

        assertEquals(ACTIVE_READ_ONLY, ignite.cluster().state());

        assertContains(log, testOut.toString(), "Cluster state changed to ACTIVE_READ_ONLY");

        assertEquals(EXIT_CODE_OK, execute("--set-state", "ACTIVE"));

        assertEquals(ACTIVE, ignite.cluster().state());

        assertContains(log, testOut.toString(), "Cluster state changed to ACTIVE");
    }

    /**
     * Verifies that update-tag action obeys its specification: doesn't allow updating tag on inactive cluster,
     *
     * @throws Exception If failed.
     */
    @Test
    public void testClusterChangeTag() throws Exception {
        final String newTag = "new_tag";

        IgniteEx cl = startGrid(0);

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--change-tag", newTag));

        String out = testOut.toString();

        //because cluster is inactive
        assertTrue(out.contains("Error has occurred during tag update:"));

        cl.cluster().state(ClusterState.ACTIVE);

        //because new tag should be non-empty string
        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--change-tag", ""));

        assertEquals(EXIT_CODE_OK, execute("--change-tag", newTag));

        boolean tagUpdated = GridTestUtils.waitForCondition(() -> newTag.equals(cl.cluster().tag()), 10_000);
        assertTrue("Tag has not been updated in 10 seconds", tagUpdated);
    }

    /**
     * Tests idle_verify working on an inactive cluster with persistence. Works via control.sh.
     *
     * @throws IgniteException if succeeded.
     */
    @Test
    public void testIdleVerifyOnInactiveClusterWithPersistence() throws Exception {
        IgniteEx srv = startGrids(2);

        assertTrue(CU.isPersistenceEnabled(getConfiguration()));
        assertFalse(srv.cluster().state().active());

        injectTestSystemOut();

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute("--cache", "idle_verify"));

        assertContains(log, testOut.toString(), VerifyBackupPartitionsTask.IDLE_VERIFY_ON_INACTIVE_CLUSTER_ERROR_MESSAGE);
        assertContains(log, testOut.toString(), "Failed to perform operation");

        srv.cluster().state(ACTIVE);
        srv.createCache(DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify"));
        assertContains(log, testOut.toString(), "The check procedure has finished, no conflicts have been found.");
    }

    /** */
    @Test
    public void testIdleVerifyCancelOnCheckpoint() throws Exception {
        doTestCancelIdleVerify((beforeCancelLatch, afterCancelLatch) -> {
            G.allGrids().forEach(grid -> {
                if (grid.configuration().isClientMode())
                    return;

                GridCacheDatabaseSharedManager dbMgr =
                    (GridCacheDatabaseSharedManager)((IgniteEx)grid).context().cache().context().database();

                dbMgr.addCheckpointListener(new CheckpointListener() {
                    @Override public void beforeCheckpointBegin(Context ctx) {
                        if (Objects.equals(ctx.progress().reason(), CP_REASON))
                            beforeCancelLatch.countDown();
                    }

                    @Override public void afterCheckpointEnd(Context ctx) throws IgniteCheckedException {
                        if (Objects.equals(ctx.progress().reason(), CP_REASON)) {
                            try {
                                assertTrue(afterCancelLatch.await(getTestTimeout(), TimeUnit.MILLISECONDS));
                            }
                            catch (InterruptedException e) {
                                throw new IgniteInterruptedCheckedException(e);
                            }
                        }
                    }

                    @Override public void onMarkCheckpointBegin(Context ctx) {
                        // No-op.
                    }

                    @Override public void onCheckpointBegin(Context ctx) {
                        // No-op.
                    }
                });
            });
        }, false);
    }

    /** */
    @Test
    public void testIdleVerifyCancelBeforeCalcPartitionHashStarted() throws Exception {
        doTestCancelIdleVerify((beforeCancelLatch, afterCancelLatch) -> {
            ForkJoinPool pool = new ForkJoinPool() {
                @Override public <T> ForkJoinTask<T> submit(Callable<T> task) {
                    beforeCancelLatch.countDown();

                    ForkJoinTask<T> submitted = super.submit(task);

                    try {
                        assertTrue(afterCancelLatch.await(getTestTimeout(), TimeUnit.MILLISECONDS));
                    }
                    catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }

                    return submitted;
                }
            };

            VerifyBackupPartitionsTask.poolSupplier = () -> pool;
        }, false);
    }

    /** */
    @Test
    public void testIdleVerifyCancelWhileCalcPartitionHashRunning() throws Exception {
        for (boolean checkCrc : new boolean[] {false, true}) {
            // Can't place assert inside pool, because exceptions from task ignored.
            AtomicBoolean interruptedOnCancel = new AtomicBoolean(true);
            AtomicBoolean eCatched = new AtomicBoolean(false);

            doTestCancelIdleVerify((beforeCancelLatch, afterCancelLatch) -> {
                ForkJoinPool pool = new ForkJoinPool() {
                    @Override public <T> ForkJoinTask<T> submit(Callable<T> task) {
                        return super.submit(new Callable<T>() {
                            @Override public T call() throws Exception {
                                beforeCancelLatch.countDown();

                                try {
                                    assertTrue(afterCancelLatch.await(getTestTimeout(), TimeUnit.MILLISECONDS));
                                }
                                catch (InterruptedException ignored) {
                                    interruptedOnCancel.set(false);
                                }

                                try {
                                    // Call must fail.
                                    T res = task.call();

                                    interruptedOnCancel.set(false);

                                    return res;
                                }
                                catch (IgniteException e) {
                                    if (!e.getMessage().startsWith(checkCrc ? CRC_CHECK_ERR_MSG : CACL_PART_HASH_ERR_MSG))
                                        interruptedOnCancel.set(false);

                                    eCatched.set(true);

                                    throw e;
                                }
                                catch (Throwable e) {
                                    interruptedOnCancel.set(false);

                                    throw e;
                                }
                            }
                        });
                    }
                };

                VerifyBackupPartitionsTask.poolSupplier = () -> pool;
            }, checkCrc);

            assertTrue("All tasks must be cancelled", interruptedOnCancel.get());
            assertTrue("Task must fail with expected exception", eCatched.get());

            eCatched.set(false);
        }
    }

    /**
     * Wrapper for tests for idle verify cancel command.
     *
     * @param prepare Prepares the test using beforeCancelLatch and afterCancelLatch.
     * @param checkCrc If {@code true} then run idle verify with --check-crc argument.
    */
    private void doTestCancelIdleVerify(BiConsumer<CountDownLatch, CountDownLatch> prepare, boolean checkCrc) throws Exception {
        final int gridsCnt = 4;

        if (G.allGrids().isEmpty()) {
            listeningLog = new ListeningTestLogger(log);

            IgniteEx srv = startGrids(gridsCnt);

            srv.cluster().state(ACTIVE);

            IgniteCache<Integer, Integer> cache = srv.getOrCreateCache(new CacheConfiguration<Integer, Integer>(DEFAULT_CACHE_NAME)
                .setBackups(3)
                .setAffinity(new RendezvousAffinityFunction().setPartitions(3)));

            for (int part = 0; part < 3; part++) {
                for (Integer key : partitionKeys(cache, part, 3, 0)) {
                    cache.put(key, key);
                }
            }
        }

        CountDownLatch beforeCancelLatch = new CountDownLatch(1);
        CountDownLatch afterCancelLatch = new CountDownLatch(1);

        prepare.accept(beforeCancelLatch, afterCancelLatch);

        LogListener lsnr = LogListener.matches("Idle verify was cancelled.").build();

        listeningLog.registerListener(lsnr);

        IgniteInternalFuture<Integer> idleVerifyFut = GridTestUtils.runAsync(() -> {
            if (checkCrc)
                execute("--cache", "idle_verify", "--check-crc");
            else
                execute("--cache", "idle_verify");
        });

        assertTrue(beforeCancelLatch.await(getTestTimeout(), TimeUnit.MILLISECONDS));

        assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify", "--cancel"));

        afterCancelLatch.countDown();

        assertTrue(waitForCondition(() -> {
            for (int i = 0; i < gridsCnt; i++) {
                for (ComputeTaskView taskView : grid(i).context().systemView().<ComputeTaskView>view(TASKS_VIEW)) {
                    if (TASKS_TO_CANCEL.contains(taskView.taskName()))
                        return false;
                }

                for (ComputeJobView jobView : grid(i).context().systemView().<ComputeJobView>view(JOBS_VIEW)) {
                    if (TASKS_TO_CANCEL.contains(jobView.taskName()))
                        return false;
                }
            }

            return true;
        }, getTestTimeout()));

        idleVerifyFut.get(getTestTimeout(), TimeUnit.MILLISECONDS);

        assertTrue(lsnr.check());
    }

    /**
     * Test deactivation works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDeactivate() throws Exception {
        Ignite ignite = startGrids(1);

        assertFalse(ignite.cluster().state().active());
        assertEquals(INACTIVE, ignite.cluster().state());

        ignite.cluster().state(ACTIVE);

        assertTrue(ignite.cluster().state().active());
        assertEquals(ACTIVE, ignite.cluster().state());

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--deactivate"));

        assertFalse(ignite.cluster().state().active());
        assertEquals(INACTIVE, ignite.cluster().state());

        if (cliCommandHandler())
            assertContains(log, testOut.toString(), "Command deprecated. Use --set-state instead.");
    }

    /**
     * Test "deactivate" via control.sh when a non-persistent cache involved.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDeactivateNonPersistent() throws Exception {
        checkDeactivateNonPersistent("--deactivate");
    }

    /**
     * Test "set-state inactive" via control.sh when a non-persistent cache involved.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSetInactiveNonPersistent() throws Exception {
        checkDeactivateNonPersistent("--set-state", "inactive");
    }

    /**
     * Launches cluster deactivation. Works via control.sh when a non-persistent cache involved.
     *
     *  @param cmd Certain command to deactivate cluster.
     */
    private void checkDeactivateNonPersistent(String... cmd) throws Exception {
        dataRegionConfiguration = new DataRegionConfiguration()
            .setName("non-persistent-dataRegion")
            .setPersistenceEnabled(false);

        Ignite ignite = startGrids(1);

        ignite.cluster().state(ACTIVE);

        assertTrue(ignite.cluster().state().active());
        assertEquals(ACTIVE, ignite.cluster().state());

        ignite.createCache(new CacheConfiguration<>("non-persistent-cache")
            .setDataRegionName("non-persistent-dataRegion"));

        injectTestSystemOut();

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute(cmd));

        assertTrue(ignite.cluster().state().active());
        assertEquals(ACTIVE, ignite.cluster().state());
        assertContains(log, testOut.toString(), GridClusterStateProcessor.DATA_LOST_ON_DEACTIVATION_WARNING);

        List<String> forceCmd = new ArrayList<>(Arrays.asList(cmd));
        forceCmd.add("--force");

        assertEquals(EXIT_CODE_OK, execute(forceCmd));

        assertFalse(ignite.cluster().state().active());
        assertEquals(INACTIVE, ignite.cluster().state());
    }

    /**
     * Test the deactivation command on the active and no cluster with checking
     * the cluster name(which is set through the system property) in
     * confirmation.
     *
     * @throws Exception If failed.
     * */
    @Test
    @WithSystemProperty(key = IGNITE_CLUSTER_NAME, value = "TEST_CLUSTER_NAME")
    public void testDeactivateWithCheckClusterNameInConfirmationBySystemProperty() throws Exception {
        IgniteEx igniteEx = startGrid(0);
        assertFalse(igniteEx.cluster().state().active());

        deactivateActiveOrNotClusterWithCheckClusterNameInConfirmation(igniteEx, "TEST_CLUSTER_NAME");
    }

    /**
     * Test the deactivation command on the active and no cluster with checking
     * the cluster name(default) in confirmation.
     *
     * @throws Exception If failed.
     * */
    @Test
    public void testDeactivateWithCheckClusterNameInConfirmationByDefault() throws Exception {
        IgniteEx igniteEx = startGrid(0);
        assertFalse(igniteEx.cluster().state().active());

        deactivateActiveOrNotClusterWithCheckClusterNameInConfirmation(
            igniteEx,
            igniteEx.cluster().id().toString()
        );
    }

    /**
     * Deactivating the cluster(active and not) with checking the cluster name
     * in the confirmation.
     *
     * @param igniteEx Node.
     * @param clusterName Cluster name to check in the confirmation message.
     * */
    private void deactivateActiveOrNotClusterWithCheckClusterNameInConfirmation(
        IgniteEx igniteEx,
        String clusterName
    ) {
        deactivateWithCheckClusterNameInConfirmation(igniteEx, clusterName);

        igniteEx.cluster().state(ACTIVE);
        assertTrue(igniteEx.cluster().state().active());

        deactivateWithCheckClusterNameInConfirmation(igniteEx, clusterName);
    }

    /**
     * Deactivating the cluster with checking the cluster name in the
     * confirmation.
     *
     * @param igniteEx Node.
     * @param clusterName Cluster name to check in the confirmation message.
     * */
    private void deactivateWithCheckClusterNameInConfirmation(IgniteEx igniteEx, String clusterName) {
        autoConfirmation = false;
        injectTestSystemOut();
        injectTestSystemIn(CONFIRM_MSG);

        assertEquals(EXIT_CODE_OK, execute("--deactivate"));
        assertFalse(igniteEx.cluster().state().active());

        if (cliCommandHandler()) {
            assertContains(
                log,
                testOut.toString(),
                "Warning: the command will deactivate a cluster \"" + clusterName + "\"."
            );
        }
    }

    /**
     * Test cluster active state works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testState() throws Exception {
        final String newTag = "new_tag";

        Ignite ignite = startGrids(2);
        
        startClientGrid("client");

        assertFalse(ignite.cluster().state().active());

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--state"));

        assertClusterState(INACTIVE, testOut.toString());

        String out = testOut.toString();

        UUID clId = ignite.cluster().id();
        String clTag = ignite.cluster().tag();

        assertTrue(out.contains("Cluster  ID: " + clId));
        assertTrue(out.contains("Cluster tag: " + clTag));

        ignite.cluster().state(ClusterState.ACTIVE);

        assertTrue(ignite.cluster().state().active());

        assertEquals(EXIT_CODE_OK, execute("--state"));

        assertClusterState(ACTIVE, testOut.toString());

        ignite.cluster().state(ACTIVE_READ_ONLY);

        awaitPartitionMapExchange();

        assertEquals(ACTIVE_READ_ONLY, ignite.cluster().state());

        assertEquals(EXIT_CODE_OK, execute("--state"));

        assertClusterState(ACTIVE_READ_ONLY, testOut.toString());

        boolean tagUpdated = GridTestUtils.waitForCondition(() -> {
            try {
                ignite.cluster().tag(newTag);
            }
            catch (IgniteCheckedException e) {
                return false;
            }

            return true;
        }, 10_000);

        assertTrue("Tag has not been updated in 10 seconds.", tagUpdated);

        assertEquals(EXIT_CODE_OK, execute("--state"));

        out = testOut.toString();

        assertTrue(out.contains("Cluster tag: " + newTag));

        ignite.cluster().state(INACTIVE);

        awaitPartitionMapExchange();

        assertEquals(EXIT_CODE_OK, execute("--state"));

        assertClusterState(INACTIVE, testOut.toString());
    }

    /**
     * Test --set-state command works correct.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSetState() throws Exception {
        Ignite ignite = startGrids(2);

        ignite.cluster().state(ACTIVE);

        ignite.createCache(ClusterStateTestUtils.partitionedCache(PARTITIONED_CACHE_NAME));
        ignite.createCache(ClusterStateTestUtils.replicatedCache(REPLICATED_CACHE_NAME));

        ignite.cluster().state(INACTIVE);

        injectTestSystemOut();

        assertEquals(INACTIVE, ignite.cluster().state());

        // INACTIVE -> INACTIVE.
        setState(ignite, INACTIVE, "INACTIVE", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // INACTIVE -> ACTIVE_READ_ONLY.
        setState(ignite, ACTIVE_READ_ONLY, "ACTIVE_READ_ONLY", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // ACTIVE_READ_ONLY -> ACTIVE_READ_ONLY.
        setState(ignite, ACTIVE_READ_ONLY, "ACTIVE_READ_ONLY", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // ACTIVE_READ_ONLY -> ACTIVE.
        setState(ignite, ACTIVE, "ACTIVE", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // ACTIVE -> ACTIVE.
        setState(ignite, ACTIVE, "ACTIVE", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // ACTIVE -> INACTIVE.
        setState(ignite, INACTIVE, "INACTIVE", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // INACTIVE -> ACTIVE.
        setState(ignite, ACTIVE, "ACTIVE", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // ACTIVE -> ACTIVE_READ_ONLY.
        setState(ignite, ACTIVE_READ_ONLY, "ACTIVE_READ_ONLY", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);

        // ACTIVE_READ_ONLY -> INACTIVE.
        setState(ignite, INACTIVE, "INACTIVE", PARTITIONED_CACHE_NAME, REPLICATED_CACHE_NAME);
    }

    /** */
    private void setState(Ignite ignite, ClusterState state, String strState, String... cacheNames) throws Exception {
        ClusterState curState = ignite.cluster().state();

        log.info(curState + " -> " + state);

        CountDownLatch latch = getNewStateLatch(ignite.cluster().state(), state);

        if (state == INACTIVE)
            assertEquals(EXIT_CODE_OK, execute("--set-state", strState, "--force"));
        else
            assertEquals(EXIT_CODE_OK, execute("--set-state", strState));

        latch.await(getTestTimeout(), TimeUnit.MILLISECONDS);

        assertEquals(state, ignite.cluster().state());

        if (state == curState)
            assertContains(log, testOut.toString(), "Cluster state is already " + strState);
        else
            assertContains(log, testOut.toString(), "Cluster state changed to " + strState);

        List<IgniteEx> nodes = IntStream.range(0, 2)
            .mapToObj(this::grid)
            .collect(Collectors.toList());

        ClusterStateTestUtils.putSomeDataAndCheck(log, nodes, cacheNames);

        if (state == ACTIVE) {
            for (String cacheName : cacheNames)
                grid(0).cache(cacheName).clear();
        }
    }

    /** */
    private CountDownLatch getNewStateLatch(ClusterState oldState, ClusterState newState) {
        if (oldState != newState) {
            CountDownLatch latch = new CountDownLatch(G.allGrids().size());

            for (Ignite grid : G.allGrids()) {
                ((IgniteEx)grid).context().discovery().setCustomEventListener(ChangeGlobalStateFinishMessage.class,
                    ((topVer, snd, msg) -> latch.countDown()));
            }

            return latch;
        }
        else
            return new CountDownLatch(0);
    }

    /**
     * Test baseline collect works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineCollect() throws Exception {
        Ignite ignite = startGrid(
            optimize(getConfiguration(getTestIgniteInstanceName(0))).setLocalHost("0.0.0.0"));

        Field addrs = ignite.cluster().node().getClass().getDeclaredField("addrs");
        addrs.setAccessible(true);
        addrs.set(ignite.cluster().node(), Arrays.asList("127.0.0.1", "0:0:0:0:0:0:0:1", "10.19.112.175", "188.166.164.247"));
        Field hostNames = ignite.cluster().node().getClass().getDeclaredField("hostNames");
        hostNames.setAccessible(true);
        hostNames.set(ignite.cluster().node(), Arrays.asList("10.19.112.175.hostname"));

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ACTIVE);

        injectTestSystemOut();

        { // non verbose mode
            assertEquals(EXIT_CODE_OK, execute("--baseline"));

            List<String> nodesInfo = findBaselineNodesInfo();
            assertEquals(1, nodesInfo.size());
            assertContains(log, nodesInfo.get(0), "Address=188.166.164.247.hostname/188.166.164.247, ");
        }

        { // verbose mode
            assertEquals(EXIT_CODE_OK, execute("--baseline", "--verbose"));

            List<String> nodesInfo = findBaselineNodesInfo();
            assertEquals(1, nodesInfo.size());
            assertContains(
                log,
                nodesInfo.get(0),
                "Addresses=188.166.164.247.hostname/188.166.164.247,10.19.112.175.hostname/10.19.112.175"
            );
        }

        { // empty resolved addresses
            addrs.set(ignite.cluster().node(), Collections.emptyList());
            hostNames.set(ignite.cluster().node(), Collections.emptyList());

            assertEquals(EXIT_CODE_OK, execute("--verbose", "--baseline"));

            List<String> nodesInfo = findBaselineNodesInfo();
            assertEquals(1, nodesInfo.size());
            assertContains(log, nodesInfo.get(0), "ConsistentId=" +
                grid(0).cluster().localNode().consistentId() + ", State=");
        }

        assertEquals(1, ignite.cluster().currentBaselineTopology().size());
    }

    /**
     * Test baseline collect works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineCollectCrd() throws Exception {
        Ignite ignite = startGrids(2);

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ClusterState.ACTIVE);

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--port", connectorPort(grid(0)), "--baseline"));

        String crdStr = findCrdInfo();

        assertEquals("(Coordinator: ConsistentId=" +
            grid(0).cluster().localNode().consistentId() + ", Address=127.0.0.1.hostname/127.0.0.1" + ", Order=1)", crdStr);

        stopGrid(0);

        assertEquals(EXIT_CODE_OK, execute("--port", connectorPort(grid(1)), "--baseline"));

        crdStr = findCrdInfo();

        assertEquals("(Coordinator: ConsistentId=" +
            grid(1).cluster().localNode().consistentId() + ", Address=127.0.0.1.hostname/127.0.0.1" + ", Order=2)", crdStr);

        startGrid(0);

        assertEquals(EXIT_CODE_OK, execute("--port", connectorPort(grid(1)), "--baseline"));

        crdStr = findCrdInfo();

        assertEquals("(Coordinator: ConsistentId=" +
            grid(1).cluster().localNode().consistentId() + ", Address=127.0.0.1.hostname/127.0.0.1" + ", Order=2)", crdStr);

        stopGrid(1);

        assertEquals(EXIT_CODE_OK, execute("--port", connectorPort(grid(0)), "--baseline"));

        crdStr = findCrdInfo();

        assertEquals("(Coordinator: ConsistentId=" +
            grid(0).cluster().localNode().consistentId() + ", Address=127.0.0.1.hostname/127.0.0.1" + ", Order=4)", crdStr);
    }

    /**
     * @return utility information about coordinator
     */
    private String findCrdInfo() {
        String outStr = testOut.toString();

        int i = outStr.indexOf("(Coordinator: ConsistentId=");

        assertTrue(i != -1);

        String crdStr = outStr.substring(i).trim();

        return crdStr.substring(0, crdStr.indexOf('\n')).trim();
    }

    /**
     * @return utility information about baseline nodes
     */
    private List<String> findBaselineNodesInfo() {
        String outStr = testOut.toString();

        int i = outStr.indexOf("Baseline nodes:");

        assertTrue("Baseline nodes information is not found", i != -1);

        int j = outStr.indexOf("\n", i) + 1;

        int beginOfNodeDesc = -1;

        List<String> nodesInfo = new ArrayList<>();

        while ((beginOfNodeDesc = outStr.indexOf("ConsistentId=", j) ) != -1) {
            j = outStr.indexOf("\n", beginOfNodeDesc);
            nodesInfo.add(outStr.substring(beginOfNodeDesc, j).trim());
        }

        return nodesInfo;
    }

    /**
     * @param ignites Ignites.
     * @return Local node consistent ID.
     */
    private String consistentIds(Ignite... ignites) {
        StringBuilder res = new StringBuilder();

        for (Ignite ignite : ignites) {
            String consistentId = ignite.cluster().localNode().consistentId().toString();

            if (res.length() != 0)
                res.append(",");

            res.append(consistentId);
        }

        return res.toString();
    }

    /**
     * Test baseline add items works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineAdd() throws Exception {
        Ignite ignite = startGrids(1);

        ignite.cluster().baselineAutoAdjustEnabled(false);

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ClusterState.ACTIVE);

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--baseline", "add"));

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--baseline", "add", "non-existent-id"));

        Ignite other = startGrid(2);

        assertEquals(EXIT_CODE_OK, execute("--baseline", "add", consistentIds(other)));

        assertEquals(2, ignite.cluster().currentBaselineTopology().size());
    }

    /**
     * Test connectivity command works via control.sh.
     */
    @Test
    public void testConnectivityCommandWithoutFailedNodes() throws Exception {
        IgniteEx ignite = startGrids(5);

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ACTIVE);

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--diagnostic", "connectivity"));

        assertContains(log, testOut.toString(), "There are no connectivity problems.");
    }

    /**
     * Test that if node exits topology during connectivity check, the command will not fail.
     *
     * Description:
     * 1. Start three nodes.
     * 2. Execute connectivity check.
     * 3. When 3-rd node receives connectivity check compute task, it must stop itself.
     * 4. The command should exit with code OK.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testConnectivityCommandWithNodeExit() throws Exception {
        IgniteEx[] node3 = new IgniteEx[1];

        /** */
        class KillNode3CommunicationSpi extends TcpCommunicationSpi {
            /** Fail check connection request and stop third node */
            boolean fail;

            /** */
            public KillNode3CommunicationSpi(boolean fail) {
                this.fail = fail;
            }

            /** {@inheritDoc} */
            @Override public IgniteFuture<BitSet> checkConnection(List<ClusterNode> nodes) {
                if (fail) {
                    runAsync(node3[0]::close);
                    return null;
                }

                return super.checkConnection(nodes);
            }
        }

        IgniteEx node1 = startGrid(1, (UnaryOperator<IgniteConfiguration>)configuration -> {
            configuration.setCommunicationSpi(new KillNode3CommunicationSpi(false));
            return configuration;
        });

        IgniteEx node2 = startGrid(2, (UnaryOperator<IgniteConfiguration>)configuration -> {
            configuration.setCommunicationSpi(new KillNode3CommunicationSpi(false));
            return configuration;
        });

        node3[0] = startGrid(3, (UnaryOperator<IgniteConfiguration>)configuration -> {
            configuration.setCommunicationSpi(new KillNode3CommunicationSpi(true));
            return configuration;
        });

        assertFalse(node1.cluster().state().active());

        node1.cluster().state(ACTIVE);

        assertEquals(3, node1.cluster().nodes().size());

        injectTestSystemOut();

        final IgniteInternalFuture<?> connectivity = runAsync(() -> {
            final int result = execute("--diagnostic", "connectivity");
            assertEquals(EXIT_CODE_OK, result);
        });

        connectivity.get();
    }

    /**
     * Test connectivity command works via control.sh with one node failing.
     */
    @Test
    public void testConnectivityCommandWithFailedNodes() throws Exception {
        UUID okId = UUID.randomUUID();
        UUID failingId = UUID.randomUUID();

        UnaryOperator<IgniteConfiguration> operator = configuration -> {
            configuration.setCommunicationSpi(new TcpCommunicationSpi() {
                /** {inheritDoc} */
                @Override public IgniteFuture<BitSet> checkConnection(List<ClusterNode> nodes) {
                    BitSet bitSet = new BitSet();

                    int idx = 0;

                    for (ClusterNode remoteNode : nodes) {
                        if (!remoteNode.id().equals(failingId))
                            bitSet.set(idx);

                        idx++;
                    }

                    return new IgniteFinishedFutureImpl<>(bitSet);
                }
            });
            return configuration;
        };

        IgniteEx ignite = startGrid("normal", configuration -> {
            operator.apply(configuration);
            configuration.setConsistentId(okId);
            configuration.setNodeId(okId);
            return configuration;
        });

        IgniteEx failure = startGrid("failure", configuration -> {
            operator.apply(configuration);
            configuration.setConsistentId(failingId);
            configuration.setNodeId(failingId);
            return configuration;
        });

        ignite.cluster().state(ACTIVE);

        failure.cluster().state(ACTIVE);

        injectTestSystemOut();

        int connectivity = execute("--diagnostic", "connectivity");
        assertEquals(EXIT_CODE_OK, connectivity);

        String out = testOut.toString();
        String what = "There is no connectivity between the following nodes";

        assertContains(log, out.replaceAll("[\\W_]+", "").trim(),
                            what.replaceAll("[\\W_]+", "").trim());
    }

    /**
     * Test baseline remove works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineRemove() throws Exception {
        Ignite ignite = startGrids(1);

        ignite.cluster().baselineAutoAdjustEnabled(false);

        Ignite other = startGrid("nodeToStop");

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ClusterState.ACTIVE);

        String offlineNodeConsId = consistentIds(other);

        stopGrid("nodeToStop");

        assertEquals(EXIT_CODE_OK, execute("--baseline"));
        assertEquals(EXIT_CODE_OK, execute("--baseline", "remove", offlineNodeConsId));

        assertEquals(1, ignite.cluster().currentBaselineTopology().size());
    }

    /**
     * Test is checking how to --shutdown-policy command works through control.sh.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testShutdownPolicy() throws Exception {
        Ignite ignite = startGrids(1);

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ClusterState.ACTIVE);

        ShutdownPolicy plc = ignite.cluster().shutdownPolicy();

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--shutdown-policy"));

        String out = testOut.toString();

        assertContains(log, out, "Cluster shutdown policy is " + plc);
    }

    /**
     * Change shutdown policy through command.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testShutdownPolicyChange() throws Exception {
        Ignite ignite = startGrids(1);

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ClusterState.ACTIVE);

        ShutdownPolicy plcToChange = null;

        for (ShutdownPolicy plc : ShutdownPolicy.values()) {
            if (plc != ignite.cluster().shutdownPolicy())
                plcToChange = plc;
        }

        assertNotNull(plcToChange);

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--shutdown-policy", plcToChange.name()));

        assertSame(plcToChange, ignite.cluster().shutdownPolicy());

        String out = testOut.toString();

        assertContains(log, out, "Cluster shutdown policy is " + plcToChange);
    }

    /**
     * Test baseline remove node on not active cluster via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineRemoveOnNotActiveCluster() throws Exception {
        Ignite ignite = startGrids(1);
        Ignite other = startGrid("nodeToStop");

        assertFalse(ignite.cluster().state().active());

        String offlineNodeConsId = consistentIds(other);

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute("--baseline", "remove", offlineNodeConsId));

        ignite.cluster().state(ClusterState.ACTIVE);

        stopGrid("nodeToStop");

        assertEquals(2, ignite.cluster().currentBaselineTopology().size());

        ignite.cluster().state(ClusterState.INACTIVE);

        assertFalse(ignite.cluster().state().active());

        injectTestSystemOut();

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute("--baseline", "remove", offlineNodeConsId));

        assertContains(log, testOut.toString(), "Changing BaselineTopology on inactive cluster is not allowed.");
    }

    /**
     * Test baseline set works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineSet() throws Exception {
        Ignite ignite = startGrids(1);

        ignite.cluster().baselineAutoAdjustEnabled(false);

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ClusterState.ACTIVE);

        Ignite other = startGrid(2);

        assertEquals(EXIT_CODE_OK, execute("--baseline", "set", consistentIds(ignite, other)));

        assertEquals(2, ignite.cluster().currentBaselineTopology().size());

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--baseline", "set", "invalidConsistentId"));
    }

    /**
     * Test baseline set nodes with baseline offline node works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineSetWithOfflineNode() throws Exception {
        Ignite ignite0 = startGrid(0);
        //It is important to set consistent id to null for force autogeneration.
        Ignite ignite1 = startGrid(optimize(getConfiguration(getTestIgniteInstanceName(1)).setConsistentId(null)));

        assertFalse(ignite0.cluster().state().active());

        ignite0.cluster().state(ClusterState.ACTIVE);

        Ignite other = startGrid(2);

        String consistentIds = consistentIds(ignite0, ignite1, other);

        ignite1.close();

        assertEquals(EXIT_CODE_OK, execute("--baseline", "set", consistentIds));

        assertEquals(3, ignite0.cluster().currentBaselineTopology().size());
    }

    /**
     * Test baseline set by topology version works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineVersion() throws Exception {
        Ignite ignite = startGrids(1);

        ignite.cluster().baselineAutoAdjustEnabled(false);

        assertFalse(ignite.cluster().state().active());

        ignite.cluster().state(ClusterState.ACTIVE);

        startGrid(2);

        assertEquals(EXIT_CODE_OK, execute("--baseline"));

        assertEquals(EXIT_CODE_OK, execute("--baseline", "version", String.valueOf(ignite.cluster().topologyVersion())));

        assertEquals(2, ignite.cluster().currentBaselineTopology().size());
    }

    /**
     * Test that updating of baseline auto_adjustment settings via control.sh actually influence cluster's baseline.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineAutoAdjustmentAutoRemoveNode() throws Exception {
        Ignite ignite = startGrids(3);

        ignite.cluster().state(ClusterState.ACTIVE);

        int timeout = 2000;

        assertEquals(EXIT_CODE_OK,
            execute("--baseline", "auto_adjust", "enable", "timeout", String.valueOf(timeout)));

        assertEquals(3, ignite.cluster().currentBaselineTopology().size());

        CountDownLatch nodeLeftLatch = new CountDownLatch(1);

        AtomicLong nodeLeftTime = new AtomicLong();

        ignite.events().localListen(event -> {
            nodeLeftTime.set(event.timestamp());

            nodeLeftLatch.countDown();

            return false;
        }, EVT_NODE_LEFT, EVT_NODE_FAILED);

        runAsync(() -> stopGrid(2));

        nodeLeftLatch.await();

        while (true) {
            int bltSize = ignite.cluster().currentBaselineTopology().size();

            if (System.currentTimeMillis() >= nodeLeftTime.get() + timeout)
                break;

            assertEquals(3, bltSize);

            U.sleep(100);
        }

        assertTrue(waitForCondition(() -> ignite.cluster().currentBaselineTopology().size() == 2, 10000));

        Collection<BaselineNode> baselineNodesAfter = ignite.cluster().currentBaselineTopology();

        assertEquals(EXIT_CODE_OK, execute("--baseline", "auto_adjust", "disable"));

        stopGrid(1);

        Thread.sleep(3000L);

        Collection<BaselineNode> baselineNodesFinal = ignite.cluster().currentBaselineTopology();

        assertEquals(
            baselineNodesAfter.stream().map(BaselineNode::consistentId).collect(Collectors.toList()),
            baselineNodesFinal.stream().map(BaselineNode::consistentId).collect(Collectors.toList())
        );
    }

    /**
     * Test that updating of baseline auto_adjustment settings via control.sh actually influence cluster's baseline.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineAutoAdjustmentAutoAddNode() throws Exception {
        Ignite ignite = startGrids(1);

        ignite.cluster().state(ACTIVE);

        assertEquals(EXIT_CODE_OK, execute("--baseline", "auto_adjust", "enable", "timeout", "2000"));

        assertEquals(1, ignite.cluster().currentBaselineTopology().size());

        startGrid(1);

        assertEquals(1, ignite.cluster().currentBaselineTopology().size());

        assertEquals(EXIT_CODE_OK, execute("--baseline"));

        assertTrue(waitForCondition(() -> ignite.cluster().currentBaselineTopology().size() == 2, 10000));

        Collection<BaselineNode> baselineNodesAfter = ignite.cluster().currentBaselineTopology();

        assertEquals(EXIT_CODE_OK, execute("--baseline", "auto_adjust", "disable"));

        startGrid(2);

        Thread.sleep(3000L);

        Collection<BaselineNode> baselineNodesFinal = ignite.cluster().currentBaselineTopology();

        assertEquals(
            baselineNodesAfter.stream().map(BaselineNode::consistentId).collect(Collectors.toList()),
            baselineNodesFinal.stream().map(BaselineNode::consistentId).collect(Collectors.toList())
        );
    }

    /**
     * Test active transactions.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testActiveTransactions() throws Exception {
        Ignite ignite = startGridsMultiThreaded(2);

        ignite.cluster().state(ACTIVE);

        Ignite client = startGrid("client");

        client.getOrCreateCache(new CacheConfiguration<>(DEFAULT_CACHE_NAME)
            .setAtomicityMode(TRANSACTIONAL).setWriteSynchronizationMode(FULL_SYNC));

        for (Ignite ig : G.allGrids())
            assertNotNull(ig.cache(DEFAULT_CACHE_NAME));

        CountDownLatch lockLatch = new CountDownLatch(1);
        CountDownLatch unlockLatch = new CountDownLatch(1);

        IgniteInternalFuture<?> fut = startTransactions("testActiveTransactions", lockLatch, unlockLatch, true);

        U.awaitQuiet(lockLatch);

        doSleep(5000);

        TestCommandHandler h = newCommandHandler();

        final TxInfo[] toKill = {null};

        // Basic test.
        validate(h, map -> {
            TxTaskResult res = map.get(grid(0).cluster().localNode());

            for (TxInfo info : res.getInfos()) {
                if (info.getSize() == 100) {
                    toKill[0] = info; // Store for further use.

                    break;
                }
            }

            assertEquals(3, map.size());
        }, "--tx");

        assertNotNull(toKill[0]);

        // Test filter by label.
        validate(h, map -> {
            ClusterNode node = grid(0).cluster().localNode();

            for (Map.Entry<ClusterNode, TxTaskResult> entry : map.entrySet())
                assertEquals(entry.getKey().equals(node) ? 1 : 0, entry.getValue().getInfos().size());
        }, "--tx", "--label", "label1");

        // Test filter by label regex.
        validate(h, map -> {
            ClusterNode node1 = grid(0).cluster().localNode();
            ClusterNode node2 = grid("client").cluster().localNode();

            for (Map.Entry<ClusterNode, TxTaskResult> entry : map.entrySet()) {
                if (entry.getKey().equals(node1)) {
                    assertEquals(1, entry.getValue().getInfos().size());

                    assertEquals("label1", entry.getValue().getInfos().get(0).getLabel());
                }
                else if (entry.getKey().equals(node2)) {
                    assertEquals(1, entry.getValue().getInfos().size());

                    assertEquals("label2", entry.getValue().getInfos().get(0).getLabel());
                }
                else
                    assertTrue(entry.getValue().getInfos().isEmpty());

            }
        }, "--tx", "--label", "^label[0-9]");

        // Test filter by empty label.
        validate(h, map -> {
            TxTaskResult res = map.get(grid(0).localNode());

            for (TxInfo info : res.getInfos())
                assertNull(info.getLabel());

        }, "--tx", "--label", "null");

        // test check minSize
        int minSize = 10;

        validate(h, map -> {
            TxTaskResult res = map.get(grid(0).localNode());

            assertNotNull(res);

            for (TxInfo txInfo : res.getInfos())
                assertTrue(txInfo.getSize() >= minSize);
        }, "--tx", "--min-size", Integer.toString(minSize));

        // test order by size.
        validate(h, map -> {
            TxTaskResult res = map.get(grid(0).localNode());

            assertTrue(res.getInfos().get(0).getSize() >= res.getInfos().get(1).getSize());
        }, "--tx", "--order", "SIZE");

        // test order by duration.
        validate(h, map -> {
            TxTaskResult res = map.get(grid(0).localNode());

            assertTrue(res.getInfos().get(0).getDuration() >= res.getInfos().get(1).getDuration());
        }, "--tx", "--order", "DURATION");

        // test order by start_time.
        validate(h, map -> {
            TxTaskResult res = map.get(grid(0).localNode());

            for (int i = res.getInfos().size() - 1; i > 1; i--)
                assertTrue(res.getInfos().get(i - 1).getStartTime() >= res.getInfos().get(i).getStartTime());
        }, "--tx", "--order", "START_TIME");

        // Trigger topology change and test connection.
        IgniteInternalFuture<?> startFut = multithreadedAsync(() -> {
            try {
                startGrid(2);
            }
            catch (Exception e) {
                fail();
            }
        }, 1, "start-node-thread");

        doSleep(5000); // Give enough time to reach exchange future.

        assertEquals(EXIT_CODE_OK, execute(h, "--tx"));

        // Test kill by xid.
        validate(h, map -> {
            assertEquals(1, map.size());

            Map.Entry<ClusterNode, TxTaskResult> killedEntry = map.entrySet().iterator().next();

            TxInfo info = killedEntry.getValue().getInfos().get(0);

            assertEquals(toKill[0].getXid(), info.getXid());
        }, "--tx", "--kill",
            "--xid", toKill[0].getXid().toString(), // Use saved on first run value.
            "--nodes", grid(0).localNode().consistentId().toString());

        unlockLatch.countDown();

        startFut.get();

        fut.get();

        awaitPartitionMapExchange();

        checkUserFutures();
    }

    /**
     * Simulate uncommitted backup transactions and test rolling back using utility.
     */
    @Test
    public void testKillHangingRemoteTransactions() throws Exception {
        final int cnt = 3;

        startGridsMultiThreaded(cnt);

        Ignite[] clients = new Ignite[] {
            startGrid("client1"),
            startGrid("client2"),
            startGrid("client3"),
            startGrid("client4")
        };

        clients[0].getOrCreateCache(new CacheConfiguration<>(DEFAULT_CACHE_NAME).
            setBackups(2).
            setAtomicityMode(TRANSACTIONAL).
            setWriteSynchronizationMode(FULL_SYNC).
            setAffinity(new RendezvousAffinityFunction(false, 64)));

        awaitPartitionMapExchange();

        for (Ignite client : clients) {
            assertTrue(client.configuration().isClientMode());

            assertNotNull(client.cache(DEFAULT_CACHE_NAME));
        }

        LongAdder progress = new LongAdder();

        AtomicInteger idx = new AtomicInteger();

        int tc = clients.length;

        CountDownLatch lockLatch = new CountDownLatch(1);
        CountDownLatch commitLatch = new CountDownLatch(1);

        Ignite prim = primaryNode(0L, DEFAULT_CACHE_NAME);

        TestRecordingCommunicationSpi primSpi = TestRecordingCommunicationSpi.spi(prim);

        primSpi.blockMessages(new IgniteBiPredicate<ClusterNode, Message>() {
            @Override public boolean apply(ClusterNode node, Message message) {
                return message instanceof GridDhtTxFinishRequest;
            }
        });

        Set<IgniteUuid> xidSet = new GridConcurrentHashSet<>();

        IgniteInternalFuture<?> fut = multithreadedAsync(new Runnable() {
            @Override public void run() {
                int id = idx.getAndIncrement();

                Ignite client = clients[id];

                try (Transaction tx = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED, 0, 1)) {
                    xidSet.add(tx.xid());

                    IgniteCache<Long, Long> cache = client.cache(DEFAULT_CACHE_NAME);

                    if (id != 0)
                        U.awaitQuiet(lockLatch);

                    cache.invoke(0L, new IncrementClosure(), null);

                    if (id == 0) {
                        lockLatch.countDown();

                        U.awaitQuiet(commitLatch);

                        doSleep(500); // Wait until candidates will enqueue.
                    }

                    tx.commit();
                }
                catch (Exception e) {
                    assertTrue(X.hasCause(e, TransactionTimeoutException.class));
                }

                progress.increment();

            }
        }, tc, "invoke-thread");

        U.awaitQuiet(lockLatch);

        commitLatch.countDown();

        primSpi.waitForBlocked(clients.length);

        // Unblock only finish messages from clients from 2 to 4.
        primSpi.stopBlock(true, blockedMsg -> {
                GridIoMessage iom = blockedMsg.ioMessage();

                Message m = iom.message();

                if (m instanceof GridDhtTxFinishRequest) {
                    GridDhtTxFinishRequest r = (GridDhtTxFinishRequest)m;

                    return !r.nearNodeId().equals(clients[0].cluster().localNode().id());
                }

                return true;
            }
        );

        // Wait until queue is stable
        for (Ignite ignite : G.allGrids()) {
            if (ignite.configuration().isClientMode())
                continue;

            Collection<IgniteInternalTx> txs = ((IgniteEx)ignite).context().cache().context().tm().activeTransactions();

            waitForCondition(new GridAbsPredicate() {
                @Override public boolean apply() {
                    for (IgniteInternalTx tx : txs)
                        if (!tx.local()) {
                            IgniteTxEntry entry = tx.writeEntries().iterator().next();

                            GridCacheEntryEx cached = entry.cached();

                            Collection<GridCacheMvccCandidate> candidates = cached.remoteMvccSnapshot();

                            if (candidates.size() != clients.length)
                                return false;
                        }

                    return true;
                }
            }, 10_000);
        }

        TestCommandHandler h = newCommandHandler();

        // Check listing.
        validate(h, map -> {
            for (int i = 0; i < cnt; i++) {
                IgniteEx grid = grid(i);

                // Skip primary.
                if (grid.localNode().id().equals(prim.cluster().localNode().id()))
                    continue;

                TxTaskResult res = map.get(grid.localNode());

                List<TxInfo> infos = res.getInfos()
                    .stream()
                    .filter(info -> xidSet.contains(info.getNearXid()))
                    .collect(Collectors.toList());

                // Validate queue length on backups.
                assertEquals(clients.length, infos.size());
            }
        }, "--tx");

        // Check kill.
        validate(h, map -> {
            // No-op.
        }, "--tx", "--kill");

        // Wait for all remote txs to finish.
        for (Ignite ignite : G.allGrids()) {
            if (ignite.configuration().isClientMode())
                continue;

            Collection<IgniteInternalTx> txs = ((IgniteEx)ignite).context().cache().context().tm().activeTransactions();

            for (IgniteInternalTx tx : txs)
                if (!tx.local())
                    tx.finishFuture().get();
        }

        // Unblock finish message from client1.
        primSpi.stopBlock(true);

        fut.get();

        Long cur = (Long)clients[0].cache(DEFAULT_CACHE_NAME).get(0L);

        assertEquals(tc - 1, cur.longValue());

        checkUserFutures();
    }

    /**
     * Test baseline add items works via control.sh
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBaselineAddOnNotActiveCluster() throws Exception {
        Ignite ignite = startGrid(1);

        assertFalse(ignite.cluster().state().active());

        String consistentIDs = getTestIgniteInstanceName(1);

        injectTestSystemOut();

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute("--baseline", "add", consistentIDs));

        assertContains(log, testOut.toString(), "Changing BaselineTopology on inactive cluster is not allowed.");

        consistentIDs =
            getTestIgniteInstanceName(1) + ", " +
                getTestIgniteInstanceName(2) + "," +
                getTestIgniteInstanceName(3);

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--baseline", "add", consistentIDs));

        String testOutStr = testOut.toString();

        // Ignite instase 1 can be logged only in arguments list.
        boolean isInstance1Found = Arrays.stream(testOutStr.split("\n"))
                                        .filter(s -> s.contains("Arguments:"))
                                        .noneMatch(s -> s.contains(getTestIgniteInstanceName() + "1"));

        assertTrue(testOutStr, testOutStr.contains("Node not found for consistent ID:"));

        if (cliCommandHandler())
            assertFalse(testOutStr, isInstance1Found);
    }

    /** */
    @Test
    public void testIdleVerifyCheckCrcFailsOnNotIdleCluster() throws Exception {
        checkpointFreq = 1000L;

        IgniteEx node = startGrids(2);

        node.cluster().state(ClusterState.ACTIVE);

        IgniteCache<Integer, Integer> cache = node.getOrCreateCache(new CacheConfiguration<Integer, Integer>()
            .setAffinity(new RendezvousAffinityFunction(false, 32))
            .setBackups(1)
            .setName(DEFAULT_CACHE_NAME));

        AtomicBoolean stopFlag = new AtomicBoolean();

        IgniteInternalFuture<?> loadFut = GridTestUtils.runMultiThreadedAsync(() -> {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();

            while (!stopFlag.get() && !Thread.currentThread().isInterrupted())
                cache.put(rnd.nextInt(1000), rnd.nextInt(1000));
        }, 5, "load-thread-");

        try {
            doSleep(checkpointFreq);

            injectTestSystemOut();

            assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify", "--check-crc"));
        }
        finally {
            doSleep(checkpointFreq);

            stopFlag.set(true);

            loadFut.get();
        }

        String out = testOut.toString();

        assertContains(log, out, "The check procedure failed");
        assertContains(log, out, "See log for additional information.");

        String logFileName = (out.split("See log for additional information. ")[1]).split(".txt")[0];

        String logFile = new String(Files.readAllBytes(new File(logFileName + ".txt").toPath()));

        assertContains(log, logFile, GRID_NOT_IDLE_MSG);
    }

    /**
     * Tests that idle verify print partitions info when node failing.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCacheIdleVerifyDumpWhenNodeFailing() throws Exception {
        Assume.assumeTrue("CHECKME", cliCommandHandler());

        Ignite ignite = startGrids(3);

        Ignite unstable = startGrid("unstable");

        ignite.cluster().state(ClusterState.ACTIVE);

        createCacheAndPreload(ignite, 100);

        for (int i = 0; i < 3; i++) {
            TestRecordingCommunicationSpi.spi(unstable).blockMessages(GridJobExecuteResponse.class,
                getTestIgniteInstanceName(i));
        }

        injectTestSystemOut();

        IgniteInternalFuture fut = runAsync(() ->
            assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify", "--dump")));

        TestRecordingCommunicationSpi.spi(unstable).waitForBlocked();

        UUID unstableNodeId = unstable.cluster().localNode().id();

        unstable.close();

        fut.get();

        checkExceptionMessageOnReport(unstableNodeId);
    }

    /**
     * Tests that idle verify print partitions info when several nodes failing at same time.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCacheIdleVerifyDumpWhenSeveralNodesFailing() throws Exception {
        int nodes = 6;

        Ignite ignite = startGrids(nodes);

        List<Ignite> unstableNodes = new ArrayList<>(nodes / 2);

        for (int i = 0; i < nodes; i++) {
            if (i % 2 == 1)
                unstableNodes.add(ignite(i));
        }

        ignite.cluster().state(ClusterState.ACTIVE);

        createCacheAndPreload(ignite, 100);

        for (Ignite unstable : unstableNodes) {
            for (int i = 0; i < nodes; i++) {
                TestRecordingCommunicationSpi.spi(unstable).blockMessages(GridJobExecuteResponse.class,
                    getTestIgniteInstanceName(i));
            }
        }

        injectTestSystemOut();

        IgniteInternalFuture fut = runAsync(
            () -> assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify", "--dump"))
        );

        List<UUID> unstableNodeIds = new ArrayList<>(nodes / 2);

        for (Ignite unstable : unstableNodes) {
            TestRecordingCommunicationSpi.spi(unstable).waitForBlocked();

            unstableNodeIds.add(unstable.cluster().localNode().id());

            unstable.close();
        }

        fut.get();

        for (UUID unstableId : unstableNodeIds)
            checkExceptionMessageOnReport(unstableId);
    }

    /** */
    @Test
    public void testCacheIdleVerifyCrcWithCorruptedPartition() throws Exception {
        testCacheIdleVerifyWithCorruptedPartition("--cache", "idle_verify", "--check-crc");

        String out = testOut.toString();

        assertContains(log, out, "The check procedure failed on 1 node.");
        assertContains(log, out, "See log for additional information.");
    }

    /** */
    @Test
    public void testCacheIdleVerifyDumpCrcWithCorruptedPartition() throws Exception {
        testCacheIdleVerifyWithCorruptedPartition("--cache", "idle_verify", "--dump", "--check-crc");

        String parts[] = testOut.toString().split(IdleVerifyDumpTask.class.getSimpleName() + " successfully written output to '");

        assertEquals(2, parts.length);

        String dumpFile = parts[1].split("\\.")[0] + ".txt";

        for (String line : Files.readAllLines(new File(dumpFile).toPath()))
            testOut.write(line.getBytes());

        String outputStr = testOut.toString();

        assertContains(log, outputStr, "The check procedure failed on 1 node.");
        assertContains(log, outputStr, CRC_CHECK_ERR_MSG);
    }

    /** */
    private void corruptPartition(File partitionsDir) throws IOException {
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        for (File partFile : partitionsDir.listFiles((d, n) -> n.startsWith("part"))) {
            try (RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
                byte[] buf = new byte[1024];

                rand.nextBytes(buf);

                raf.seek(4096 * 2 + 1);

                raf.write(buf);
            }
        }
    }

    /** */
    private void testCacheIdleVerifyWithCorruptedPartition(String... args) throws Exception {
        Ignite ignite = startGrids(2);

        ignite.cluster().state(ClusterState.ACTIVE);

        createCacheAndPreload(ignite, 1000);

        Serializable consistId = ignite.configuration().getConsistentId();

        File partitionsDir = U.resolveWorkDirectory(
            ignite.configuration().getWorkDirectory(),
            "db/" + consistId + "/cache-" + DEFAULT_CACHE_NAME,
            false
        );

        stopGrid(0);

        corruptPartition(partitionsDir);

        startGrid(0);

        awaitPartitionMapExchange();

        forceCheckpoint();

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute(args));
    }

    /**
     * Try to finds node failed exception message on output report.
     *
     * @param unstableNodeId Unstable node id.
     */
    private void checkExceptionMessageOnReport(UUID unstableNodeId) throws IOException {
        Matcher fileNameMatcher = dumpFileNameMatcher();

        if (fileNameMatcher.find()) {
            String dumpWithConflicts = new String(Files.readAllBytes(Paths.get(fileNameMatcher.group(1))));

            assertContains(log, dumpWithConflicts, "The check procedure failed on nodes:");

            assertContains(log, dumpWithConflicts, "Node ID: " + unstableNodeId);
        }
        else
            fail("Should be found dump with conflicts");
    }

    /**
     * Tests that idle verify print partitions info over none-persistence client caches.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testCacheIdleVerifyDumpForCorruptedDataOnNonePersistenceClientCache() throws Exception {
        int parts = 32;

        dataRegionConfiguration = new DataRegionConfiguration()
            .setName("none-persistence-region");

        IgniteEx ignite = startGrids(3);

        ignite.cluster().state(ClusterState.ACTIVE);

        IgniteCache<Object, Object> cache = ignite.createCache(new CacheConfiguration<>()
            .setAffinity(new RendezvousAffinityFunction(false, parts))
            .setBackups(2)
            .setName(DEFAULT_CACHE_NAME)
            .setDataRegionName("none-persistence-region"));

        // Adding some assignments without deployments.
        for (int i = 0; i < 100; i++)
            cache.put(i, i);

        injectTestSystemOut();

        GridCacheContext<Object, Object> cacheCtx = ignite.cachex(DEFAULT_CACHE_NAME).context();

        corruptDataEntry(cacheCtx, 0, true, false);

        corruptDataEntry(cacheCtx, parts / 2, false, true);

        assertEquals(
            EXIT_CODE_OK,
            execute("--cache", "idle_verify", "--dump", "--cache-filter", "NOT_PERSISTENT")
        );

        Matcher fileNameMatcher = dumpFileNameMatcher();

        if (fileNameMatcher.find()) {
            String dumpWithConflicts = new String(Files.readAllBytes(Paths.get(fileNameMatcher.group(1))));

            assertContains(log, dumpWithConflicts, "conflict partitions has been found: [counterConflicts=1, " +
                "hashConflicts=2]");
        }
        else
            fail("Should be found dump with conflicts");
    }

    /**
     * @return Build matcher for dump file name.
     */
    @NotNull private Matcher dumpFileNameMatcher() {
        Pattern fileNamePattern = Pattern.compile(".*" + IdleVerifyDumpTask.class.getSimpleName()
            + " successfully written output to '(.*)'");
        return fileNamePattern.matcher(testOut.toString());
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testCacheIdleVerifyMovingParts() throws Exception {
        IgniteEx ignite = startGrids(2);

        ignite.cluster().baselineAutoAdjustEnabled(false);

        ignite.cluster().state(ACTIVE);

        int parts = 32;

        IgniteCache<Object, Object> cache = ignite.createCache(new CacheConfiguration<>()
            .setAffinity(new RendezvousAffinityFunction(false, parts))
            .setBackups(1)
            .setName(DEFAULT_CACHE_NAME)
            .setRebalanceDelay(10_000));

        for (int i = 0; i < 100; i++)
            cache.put(i, i);

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify"));

        assertContains(log, testOut.toString(), "no conflicts have been found");

        startGrid(2);

        resetBaselineTopology();

        assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify"));

        assertContains(log, testOut.toString(), "MOVING partitions");
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testCacheIdleVerifyExpiringEntries() throws Exception {
        IgniteEx ignite = startGrids(3);

        ignite.cluster().state(ACTIVE);

        IgniteCache<Object, Object> cache = ignite.createCache(new CacheConfiguration<>(DEFAULT_CACHE_NAME)
            .setAffinity(new RendezvousAffinityFunction(false, 32))
            .setBackups(1));

        Random rnd = new Random();

        // Put without expiry policy.
        for (int i = 0; i < 5_000; i++)
            cache.put(i, i);

        // Put with expiry policy.
        for (int i = 5_000; i < 10_000; i++) {
            ExpiryPolicy expPol = new CreatedExpiryPolicy(new Duration(TimeUnit.MILLISECONDS, rnd.nextInt(1_000)));
            cache.withExpiryPolicy(expPol).put(i, i);
        }

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify"));

        assertContains(log, testOut.toString(), "no conflicts have been found");
    }

    /** */
    @Test
    public void testCacheSequence() throws Exception {
        Ignite ignite = startGrid();

        ignite.cluster().state(ACTIVE);

        Ignite client = startGrid("client");

        final IgniteAtomicSequence seq1 = client.atomicSequence("testSeq", 1, true);
        seq1.get();

        final IgniteAtomicSequence seq2 = client.atomicSequence("testSeq2", 10, true);
        seq2.get();

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--cache", "list", "testSeq.*", "--seq"));

        String out = testOut.toString();

        assertContains(log, out, "testSeq");
        assertContains(log, out, "testSeq2");
    }

    /**
     * @param h Handler.
     * @param validateClo Validate clo.
     * @param args Args.
     */
    private void validate(TestCommandHandler h, IgniteInClosure<Map<ClusterNode, TxTaskResult>> validateClo,
        String... args) {
        assertEquals(EXIT_CODE_OK, execute(h, args));

        validateClo.apply(h.getLastOperationResult());
    }

    /**
     * @param from From.
     * @param cnt Count.
     */
    private Map<Object, Object> generate(int from, int cnt) {
        Map<Object, Object> map = new TreeMap<>();

        for (int i = 0; i < cnt; i++)
            map.put(i + from, i + from);

        return map;
    }

    /**
     * Test execution of --diagnostic command.
     *
     * @throws Exception if failed.
     */
    @Test
    public void testDiagnosticPageLocksTracker() throws Exception {
        Ignite ignite = startGrid(0, (UnaryOperator<IgniteConfiguration>)cfg -> cfg.setConsistentId("node0/dump"));
        startGrid(1, (UnaryOperator<IgniteConfiguration>)cfg -> cfg.setConsistentId("node1/dump"));
        startGrid(2, (UnaryOperator<IgniteConfiguration>)cfg -> cfg.setConsistentId("node2/dump"));
        startGrid(3, (UnaryOperator<IgniteConfiguration>)cfg -> cfg.setConsistentId("node3/dump"));

        Collection<ClusterNode> nodes = ignite.cluster().nodes();

        List<ClusterNode> nodes0 = new ArrayList<>(nodes);

        ClusterNode node0 = nodes0.get(0);
        ClusterNode node1 = nodes0.get(1);
        ClusterNode node2 = nodes0.get(2);
        ClusterNode node3 = nodes0.get(3);

        ignite.cluster().state(ACTIVE);

        if (cliCommandHandler()) {
            assertEquals(
                EXIT_CODE_OK,
                execute("--diagnostic")
            );

            assertEquals(
                EXIT_CODE_OK,
                execute("--diagnostic", "help")
            );
        }

        // Dump locks only on connected node to default path.
        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump")
        );

        // Check file dump in default path.
        checkNumberFiles(defaultDiagnosticDir, 1);

        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump_log")
        );

        // Dump locks only on connected node to specific path.
        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump", "--path", customDiagnosticDir.getAbsolutePath())
        );

        // Check file dump in specific path.
        checkNumberFiles(customDiagnosticDir, 1);

        // Dump locks only all nodes.
        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump", "--all")
        );

        // Current cluster 4 nodes -> 4 files + 1 from previous operation.
        checkNumberFiles(defaultDiagnosticDir, 5);

        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump_log", "--all")
        );

        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump",
                "--path", customDiagnosticDir.getAbsolutePath(), "--all")
        );

        // Current cluster 4 nodes -> 4 files + 1 from previous operation.
        checkNumberFiles(customDiagnosticDir, 5);

        // Dump locks only 2 nodes use nodeIds as arg.
        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump",
                "--nodes", node0.id().toString() + "," + node2.id().toString())
        );

        // Dump locks only for 2 nodes -> 2 files + 5 from previous operation.
        checkNumberFiles(defaultDiagnosticDir, 7);

        // Dump locks only for 2 nodes use constIds as arg.
        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump",
                "--nodes", node0.consistentId().toString() + "," + node2.consistentId().toString())
        );

        assertEquals(
            EXIT_CODE_OK,
            execute("--diagnostic", "pageLocks", "dump_log",
                "--nodes", node1.id().toString() + "," + node3.id().toString())
        );

        assertEquals(
            EXIT_CODE_OK,
            execute(
                "--diagnostic", "pageLocks", "dump",
                "--path", customDiagnosticDir.getAbsolutePath(),
                "--nodes", node1.consistentId().toString() + "," + node3.consistentId().toString())
        );

        // Dump locks only for 2 nodes -> 2 files + 5 from previous operation.
        checkNumberFiles(customDiagnosticDir, 7);
    }

    /**
     * @param dir Directory.
     * @param numberFiles Number of files.
     */
    private void checkNumberFiles(File dir, int numberFiles) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(ToFileDumpProcessor.PREFIX_NAME));

        assertEquals(numberFiles, files.length);

        for (int i = 0; i < files.length; i++)
            assertTrue(files[i].length() > 0);
    }

    /**
     * Starts several long transactions in order to test --tx command. Transactions will last until unlock latch is
     * released: first transaction will wait for unlock latch directly, some others will wait for key lock acquisition.
     *
     * @param lockLatch Lock latch. Will be released inside body of the first transaction.
     * @param unlockLatch Unlock latch. Should be released externally. First transaction won't be finished until unlock
     * latch is released.
     * @param topChangeBeforeUnlock <code>true</code> should be passed if cluster topology is expected to change between
     * method call and unlock latch release. Commit of the first transaction will be asserted to fail in such case.
     * @return Future to be completed after finish of all started transactions.
     */
    private IgniteInternalFuture<?> startTransactions(
        String testName,
        CountDownLatch lockLatch,
        CountDownLatch unlockLatch,
        boolean topChangeBeforeUnlock
    ) throws Exception {
        IgniteEx client = grid("client");

        AtomicInteger idx = new AtomicInteger();

        return multithreadedAsync(new Runnable() {
            @Override public void run() {
                int id = idx.getAndIncrement();

                switch (id) {
                    case 0:
                        try (Transaction tx = grid(0).transactions().txStart()) {
                            grid(0).cache(DEFAULT_CACHE_NAME).putAll(generate(0, 100));

                            lockLatch.countDown();

                            U.awaitQuiet(unlockLatch);

                            tx.commit();

                            if (topChangeBeforeUnlock)
                                fail("Commit must fail");
                        }
                        catch (Exception e) {
                            if (topChangeBeforeUnlock)
                                assertTrue(X.hasCause(e, TransactionRollbackException.class));
                            else
                                throw e;
                        }

                        break;
                    case 1:
                        U.awaitQuiet(lockLatch);

                        doSleep(3000);

                        try (Transaction tx =
                                 grid(0).transactions().withLabel("label1").txStart(PESSIMISTIC, READ_COMMITTED, Integer.MAX_VALUE, 0)) {
                            grid(0).cache(DEFAULT_CACHE_NAME).putAll(generate(200, 110));

                            grid(0).cache(DEFAULT_CACHE_NAME).put(0, 0);
                        }

                        break;
                    case 2:
                        try (Transaction tx = grid(1).transactions().txStart()) {
                            U.awaitQuiet(lockLatch);

                            grid(1).cache(DEFAULT_CACHE_NAME).put(0, 0);
                        }

                        break;
                    case 3:
                        try (Transaction tx = client.transactions().withLabel("label2").txStart(OPTIMISTIC, READ_COMMITTED, 0, 0)) {
                            U.awaitQuiet(lockLatch);

                            client.cache(DEFAULT_CACHE_NAME).putAll(generate(100, 10));

                            client.cache(DEFAULT_CACHE_NAME).put(0, 0);

                            tx.commit();
                        }

                        break;
                }
            }
        }, 4, "tx-thread-" + testName);
    }

    /** */
    private static class IncrementClosure implements EntryProcessor<Long, Long, Void> {
        /** {@inheritDoc} */
        @Override public Void process(
            MutableEntry<Long, Long> entry,
            Object... arguments
        ) throws EntryProcessorException {
            entry.setValue(entry.exists() ? entry.getValue() + 1 : 0);

            return null;
        }
    }

    /** */
    @Test
    public void testKillHangingLocalTransactions() throws Exception {
        Ignite ignite = startGridsMultiThreaded(2);

        ignite.cluster().state(ACTIVE);

        Ignite client = startGrid("client");

        client.getOrCreateCache(new CacheConfiguration<>(DEFAULT_CACHE_NAME).
            setAtomicityMode(TRANSACTIONAL).
            setWriteSynchronizationMode(FULL_SYNC).
            setAffinity(new RendezvousAffinityFunction(false, 64)));

        Ignite prim = primaryNode(0L, DEFAULT_CACHE_NAME);

        // Blocks lock response to near node.
        TestRecordingCommunicationSpi.spi(prim).blockMessages(GridNearLockResponse.class, client.name());

        TestRecordingCommunicationSpi.spi(client).blockMessages(GridNearTxFinishRequest.class, prim.name());

        GridNearTxLocal clientTx = null;

        try (Transaction tx = client.transactions().txStart(PESSIMISTIC, READ_COMMITTED, 2000, 1)) {
            clientTx = ((TransactionProxyImpl)tx).tx();

            client.cache(DEFAULT_CACHE_NAME).put(0L, 0L);

            fail();
        }
        catch (Exception e) {
            assertTrue(X.hasCause(e, TransactionTimeoutException.class));
        }

        assertNotNull(clientTx);

        IgniteEx primEx = (IgniteEx)prim;

        IgniteInternalTx tx0 = primEx.context().cache().context().tm().activeTransactions().iterator().next();

        assertNotNull(tx0);

        TestCommandHandler h = newCommandHandler();

        validate(h, map -> {
            ClusterNode node = grid(0).cluster().localNode();

            TxTaskResult res = map.get(node);

            for (TxInfo info : res.getInfos())
                assertEquals(tx0.xid(), info.getXid());

            assertEquals(1, map.size());
        }, "--tx", "--xid", tx0.xid().toString(), "--kill");

        tx0.finishFuture().get();

        TestRecordingCommunicationSpi.spi(prim).stopBlock();

        TestRecordingCommunicationSpi.spi(client).stopBlock();

        IgniteInternalFuture<?> nearFinFut = U.field(clientTx, "finishFut");

        nearFinFut.get();

        checkUserFutures();
    }

    /**
     * Verify that in case of setting baseline topology with offline node among others
     * {@link IgniteException} is thrown.
     *
     * @throws Exception If failed.
     */
    @Test
    public void setConsistenceIdsWithOfflineBaselineNode() throws Exception {
        Ignite ignite = startGrids(2);

        ignite.cluster().state(ACTIVE);

        ignite(0).createCache(defaultCacheConfiguration().setNodeFilter(
            (IgnitePredicate<ClusterNode>)node -> node.attribute("some-attr") != null));

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS,
            execute("--baseline", "set", "non-existing-node-id ," + consistentIds(ignite)));
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testCacheIdleVerifyPrintLostPartitions() throws Exception {
        IgniteEx ignite = startGrids(3);

        ignite.cluster().state(ACTIVE);

        ignite.createCache(new CacheConfiguration<>(DEFAULT_CACHE_NAME)
            .setAffinity(new RendezvousAffinityFunction(false, 16))
            .setCacheMode(PARTITIONED)
            .setPartitionLossPolicy(READ_ONLY_SAFE)
            .setBackups(1));

        try (IgniteDataStreamer streamer = ignite.dataStreamer(DEFAULT_CACHE_NAME)) {
            for (int i = 0; i < 10000; i++)
                streamer.addData(i, new byte[i]);
        }

        String g1Name = grid(1).name();

        stopGrid(1);

        cleanPersistenceDir(g1Name);

        //Start node 2 with empty PDS. Rebalance will be started.
        startGrid(1);

        //During rebalance stop node 3. Rebalance will be stopped which lead to lost partitions.
        stopGrid(2);

        injectTestSystemOut();

        assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify"));

        assertContains(log, testOut.toString(), "LOST partitions:");
    }

    /** @throws Exception If failed. */
    @Test
    public void testMasterKeyChange() throws Exception {
        encryptionEnabled = true;

        injectTestSystemOut();

        Ignite ignite = startGrids(1);

        ignite.cluster().state(ACTIVE);

        createCacheAndPreload(ignite, 10);

        assertEquals(EXIT_CODE_OK, execute("--encryption", "get_master_key_name"));

        assertContains(log, testOut.toString(), ignite.encryption().getMasterKeyName());

        assertEquals(EXIT_CODE_OK, execute("--encryption", "change_master_key", MASTER_KEY_NAME_2));

        assertContains(log, testOut.toString(), "The master key changed.");

        assertEquals(MASTER_KEY_NAME_2, ignite.encryption().getMasterKeyName());

        assertEquals(EXIT_CODE_OK, execute("--encryption", "get_master_key_name"));

        assertContains(log, testOut.toString(), ignite.encryption().getMasterKeyName());

        testOut.reset();

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR,
            execute("--encryption", "change_master_key", "non-existing-master-key-name"));

        assertContains(log, testOut.toString(),
            "Master key change was rejected. Unable to get the master key digest.");
    }

    /** @throws Exception If failed. */
    @Test
    public void testCacheGroupKeyChange() throws Exception {
        encryptionEnabled = true;

        injectTestSystemOut();

        int srvNodes = 2;

        IgniteEx ignite = startGrids(srvNodes);

        startGrid(CLIENT_NODE_NAME_PREFIX);

        ignite.cluster().state(ACTIVE);

        List<Ignite> srvGrids = GridFunc.asList(grid(0), grid(1));

        enableCheckpoints(srvGrids, false);

        createCacheAndPreload(ignite, 1000);

        int ret = execute("--encryption", CACHE_GROUP_KEY_IDS, DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, ret);
        assertContains(log, testOut.toString(), "Encryption key identifiers for cache: " + DEFAULT_CACHE_NAME);
        assertEquals(srvNodes, countSubstrs(testOut.toString(), "0 (active)"));

        ret = execute("--encryption", CHANGE_CACHE_GROUP_KEY, DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, ret);
        assertContains(log, testOut.toString(),
            "The encryption key has been changed for the cache group \"" + DEFAULT_CACHE_NAME + '"');

        ret = execute("--encryption", CACHE_GROUP_KEY_IDS, DEFAULT_CACHE_NAME);

        assertEquals(testOut.toString(), EXIT_CODE_OK, ret);
        assertContains(log, testOut.toString(), "Encryption key identifiers for cache: " + DEFAULT_CACHE_NAME);
        assertEquals(srvNodes, countSubstrs(testOut.toString(), "1 (active)"));

        GridTestUtils.waitForCondition(() -> {
            execute("--encryption", REENCRYPTION_STATUS, DEFAULT_CACHE_NAME);

            return srvNodes == countSubstrs(testOut.toString(),
                "re-encryption will be completed after the next checkpoint");
        }, getTestTimeout());

        enableCheckpoints(srvGrids, true);
        forceCheckpoint(srvGrids);

        GridTestUtils.waitForCondition(() -> {
            execute("--encryption", REENCRYPTION_STATUS, DEFAULT_CACHE_NAME);

            return srvNodes == countSubstrs(testOut.toString(), "re-encryption completed or not required");
        }, getTestTimeout());
    }

    /** @throws Exception If failed. */
    @Test
    public void testChangeReencryptionRate() throws Exception {
        int srvNodes = 2;

        IgniteEx ignite = startGrids(srvNodes);

        ignite.cluster().state(ACTIVE);

        injectTestSystemOut();

        int ret = execute("--encryption", REENCRYPTION_RATE);

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(), "re-encryption rate is not limited."));

        double newRate = 0.01;

        ret = execute("--encryption", REENCRYPTION_RATE, Double.toString(newRate));

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(),
            String.format("re-encryption rate has been limited to %.2f MB/s.", newRate)));

        ret = execute("--encryption", REENCRYPTION_RATE);

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(),
            String.format("re-encryption rate is limited to %.2f MB/s.", newRate)));

        ret = execute("--encryption", REENCRYPTION_RATE, "0");

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(), "re-encryption rate is not limited."));
    }

    /** @throws Exception If failed. */
    @Test
    public void testReencryptionSuspendAndResume() throws Exception {
        encryptionEnabled = true;
        reencryptSpeed = 0.01;
        reencryptBatchSize = 1;

        int srvNodes = 2;

        IgniteEx ignite = startGrids(srvNodes);

        ignite.cluster().state(ACTIVE);

        injectTestSystemOut();

        createCacheAndPreload(ignite, 10_000);

        ignite.encryption().changeCacheGroupKey(Collections.singleton(DEFAULT_CACHE_NAME)).get();

        assertTrue(isReencryptionStarted(DEFAULT_CACHE_NAME));

        int ret = execute("--encryption", REENCRYPTION_STATUS, DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, ret);

        Pattern ptrn = Pattern.compile("(?m)Node [-0-9a-f]{36}:\n\\s+(?<left>\\d+) KB of data.+");
        Matcher matcher = ptrn.matcher(testOut.toString());
        int matchesCnt = 0;

        while (matcher.find()) {
            assertEquals(1, matcher.groupCount());

            int pagesLeft = Integer.parseInt(matcher.group("left"));

            assertTrue(pagesLeft > 0);

            matchesCnt++;
        }

        assertEquals(srvNodes, matchesCnt);

        ret = execute("--encryption", REENCRYPTION_SUSPEND, DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(),
            "re-encryption of the cache group \"" + DEFAULT_CACHE_NAME + "\" has been suspended."));
        assertFalse(isReencryptionStarted(DEFAULT_CACHE_NAME));

        ret = execute("--encryption", REENCRYPTION_SUSPEND, DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(),
            "re-encryption of the cache group \"" + DEFAULT_CACHE_NAME + "\" has already been suspended."));

        ret = execute("--encryption", REENCRYPTION_RESUME, DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(),
            "re-encryption of the cache group \"" + DEFAULT_CACHE_NAME + "\" has been resumed."));
        assertTrue(isReencryptionStarted(DEFAULT_CACHE_NAME));

        ret = execute("--encryption", REENCRYPTION_RESUME, DEFAULT_CACHE_NAME);

        assertEquals(EXIT_CODE_OK, ret);
        assertEquals(srvNodes, countSubstrs(testOut.toString(),
            "re-encryption of the cache group \"" + DEFAULT_CACHE_NAME + "\" has already been resumed."));
    }

    /**
     * @param cacheName Cache name.
     * @return {@code True} if re-encryption of the specified cache is started on all server nodes.
     */
    private boolean isReencryptionStarted(String cacheName) {
        for (Ignite grid : G.allGrids()) {
            ClusterNode locNode = grid.cluster().localNode();

            if (locNode.isClient())
                continue;

            if (((IgniteEx)grid).context().encryption().reencryptionFuture(CU.cacheId(cacheName)).isDone())
                return false;
        }

        return true;
    }

    /** @throws Exception If failed. */
    @Test
    public void testMasterKeyChangeOnInactiveCluster() throws Exception {
        encryptionEnabled = true;

        injectTestSystemOut();

        Ignite ignite = startGrids(1);

        TestCommandHandler h = newCommandHandler(createTestLogger());

        assertEquals(EXIT_CODE_OK, execute(h, "--encryption", "get_master_key_name"));

        Object res = h.getLastOperationResult();

        assertEquals(ignite.encryption().getMasterKeyName(), res);

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute(h, "--encryption", "change_master_key", MASTER_KEY_NAME_2));

        assertContains(log, testOut.toString(), "Master key change was rejected. The cluster is inactive.");
    }

    /** @throws Exception If failed. */
    @Test
    public void testChangeSnapshotTransferRateInRuntime() throws Exception {
        int keysCnt = 10_000;

        IgniteConfiguration cfg = optimize(getConfiguration(getTestIgniteInstanceName(0)))
            .setSnapshotThreadPoolSize(1);

        IgniteEx ignite = startGrid(cfg);

        ignite.cluster().state(ACTIVE);

        createCacheAndPreload(ignite, keysCnt);

        // Estimate snapshot creation time.
        long start = System.currentTimeMillis();

        ignite.snapshot().createSnapshot("snapshot1").get(getTestTimeout());

        long maxOpTime = (System.currentTimeMillis() - start) * 2;

        Function<Integer, Integer> propFunc =
            (num) -> execute("--property", "set", "--name", SNAPSHOT_TRANSFER_RATE_DMS_KEY, "--val", String.valueOf(num));

        int rate = SNAPSHOT_LIMITED_TRANSFER_BLOCK_SIZE_BYTES;

        // Limit the transfer rate.
        assertEquals(EXIT_CODE_OK, (int)propFunc.apply(rate));

        IgniteFuture<Void> snpFut = ignite.snapshot().createSnapshot("snapshot2");

        // Make sure that the operation has been slowed down.
        U.sleep(maxOpTime);
        assertFalse(snpFut.isDone());

        // Set transfer rate to unlimited.
        assertEquals(EXIT_CODE_OK, (int)propFunc.apply(0));

        // Add release time of BasicRateLimiter#acquire() for the given rate.
        BasicRateLimiter limiter = new BasicRateLimiter(rate);

        limiter.acquire(SNAPSHOT_LIMITED_TRANSFER_BLOCK_SIZE_BYTES);
        limiter.acquire(SNAPSHOT_LIMITED_TRANSFER_BLOCK_SIZE_BYTES);

        snpFut.get(maxOpTime);
    }

    /** @throws Exception If failed. */
    @Test
    public void testClusterSnapshotCreate() throws Exception {
        doClusterSnapshotCreate(false);
    }

    /** @throws Exception If failed. */
    @Test
    public void testClusterSnapshotCreateSynchronously() throws Exception {
        doClusterSnapshotCreate(true);
    }

    /**
     * Test that 'not OK' status of snapshot operation is set if the operation produces a warning.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testClusterCreateSnapshotWarning() throws Exception {
        IgniteConfiguration cfg = getConfiguration(getTestIgniteInstanceName(0));
        cfg.getConnectorConfiguration().setHost("localhost");
        cfg.getClientConnectorConfiguration().setHost("localhost");

        IgniteEx ig = startGrid(cfg);

        cfg = getConfiguration(getTestIgniteInstanceName(1));
        cfg.getConnectorConfiguration().setHost("localhost");
        cfg.getClientConnectorConfiguration().setHost("localhost");

        startGrid(cfg);

        ig.cluster().state(ACTIVE);
        createCacheAndPreload(ig, 100);

        TestRecordingCommunicationSpi cm = (TestRecordingCommunicationSpi)grid(0).configuration().getCommunicationSpi();

        cm.blockMessages(DataStreamerRequest.class, grid(1).name());

        AtomicBoolean stopLoading = new AtomicBoolean();

        IgniteInternalFuture<?> loadFut = runAsync(() -> {
            try (IgniteDataStreamer<Integer, Integer> ds = ig.dataStreamer(DEFAULT_CACHE_NAME)) {
                int i = 100;

                while (!stopLoading.get()) {
                    ds.addData(i, i);

                    i++;
                }
            }
        });

        cm.waitForBlocked(IgniteDataStreamer.DFLT_PARALLEL_OPS_MULTIPLIER);

        try {
            injectTestSystemOut();

            String snpName = "testDsSnp";

            int code = execute(new ArrayList<>(F.asList("--snapshot", "create", snpName, "--sync")));

            assertEquals(EXIT_CODE_COMPLETED_WITH_WARNINGS, code);

            String out = testOut.toString();

            assertNotContains(log, out, "Failed to perform operation");

            assertContains(log, out, "Snapshot create operation completed with warnings [name=" + snpName);

            boolean dataStmrDetected = out.contains(DataStreamerUpdatesHandler.WRN_MSG);

            String expWarn = dataStmrDetected
                ? DataStreamerUpdatesHandler.WRN_MSG
                : String.format("Cache partitions differ for cache groups [%s]. ", CU.cacheId(DEFAULT_CACHE_NAME))
                    + SnapshotPartitionsQuickVerifyHandler.WRN_MSG;

            assertContains(log, out, expWarn);

            code = execute(new ArrayList<>(F.asList("--snapshot", "check", snpName)));

            assertEquals(EXIT_CODE_OK, code);

            out = testOut.toString();

            assertContains(log, out, expWarn);

            assertContains(log, out, dataStmrDetected
                ? "The check procedure has failed, conflict partitions has been found"
                : "The check procedure has finished, no conflicts have been found");
        }
        finally {
            stopLoading.set(true);
            cm.stopBlock();
            loadFut.get();
        }
    }

    /**
     * Verifies that if an error occurs during the snapshot operation, it takes precedence over any warnings
     * and is properly logged.
     */
    @Test
    public void testClusterCreateSnapshotWarningAndError() throws Exception {
        String snpName = "testSnp";

        startGrids(2).cluster().state(ACTIVE);

        TestRecordingCommunicationSpi failNodeSpi = TestRecordingCommunicationSpi.spi(grid(1));

        failNodeSpi.blockMessages((n, msg) ->
            msg instanceof SingleNodeMessage
                && ((SingleNodeMessage<?>)msg).type() == DistributedProcess.DistributedProcessType.START_SNAPSHOT.ordinal());

        AtomicBoolean stop = new AtomicBoolean();

        runAsync(() -> {
            try (IgniteDataStreamer<Integer, Object> ds = grid(0).dataStreamer(DEFAULT_CACHE_NAME)) {
                ds.allowOverwrite(false);

                int idx = 0;

                while (!stop.get())
                    ds.addData(++idx, idx);
            }
        });

        injectTestSystemOut();

        IgniteInternalFuture<Integer> fut = runAsync(() -> execute("--snapshot", "create", snpName, "--sync"));

        failNodeSpi.waitForBlocked(1, 5_000);

        grid(1).close();

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, (int)fut.get());

        stop.set(true);

        assertContains(log, testOut.toString(), "Snapshot operation interrupted, because baseline node left the cluster");
        assertNotContains(log, testOut.toString(), "Snapshot create operation completed with warnings [name=" + snpName);
    }

    /**
     * @param syncMode Execute operation synchrnously.
     * @throws Exception If failed.
     */
    private void doClusterSnapshotCreate(boolean syncMode) throws Exception {
        int keysCnt = 100;
        String snpName = "snapshot_02052020";

        IgniteEx ig = startGrid(0);
        ig.cluster().state(ACTIVE);

        createCacheAndPreload(ig, keysCnt);

        injectTestSystemOut();

        TestCommandHandler h = newCommandHandler(createTestLogger());

        // Invalid command syntax check.
        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute(h, "--snapshot", "create", snpName, "blah"));
        assertContains(log, testOut.toString(), "Unexpected argument: blah");

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute(h, "--snapshot", "create", snpName, "--sync", "blah"));
        assertContains(log, testOut.toString(), "Unexpected argument: blah");

        List<String> args = new ArrayList<>(F.asList("--snapshot", "create", snpName));

        if (syncMode)
            args.add("--sync");

        assertEquals(EXIT_CODE_OK, execute(h, args));

        MetricRegistry metrics = ig.context().metric().registry(SNAPSHOT_METRICS);

        LongMetric opEndTimeMetric = metrics.findMetric("LastSnapshotEndTime");
        BooleanSupplier endTimeMetricPredicate = () -> opEndTimeMetric.value() > 0;

        String reqId = metrics.findMetric("LastRequestId").getAsString();
        assertFalse(F.isEmpty(reqId));

        // Make sure the operation ID has been shown to the user.
        assertContains(log, testOut.toString(), reqId);

        if (syncMode)
            assertTrue(endTimeMetricPredicate.getAsBoolean());
        else {
            assertTrue("Waiting for snapshot operation end failed.",
                waitForCondition(endTimeMetricPredicate::getAsBoolean, getTestTimeout()));
        }

        assertContains(log, (String)h.getLastOperationResult(), snpName);

        File snpWorkDir = ig.context().pdsFolderResolver().fileTree().snapshotsRoot();

        stopAllGrids();

        IgniteConfiguration cfg = optimize(getConfiguration(getTestIgniteInstanceName(0)));
        cfg.setWorkDirectory(Paths.get(snpWorkDir.getAbsolutePath(), snpName).toString());

        Ignite snpIg = startGrid(cfg);
        snpIg.cluster().state(ACTIVE);

        List<Integer> range = IntStream.range(0, keysCnt).boxed().collect(Collectors.toList());

        snpIg.cache(DEFAULT_CACHE_NAME).forEach(e -> range.remove((Integer)e.getKey()));
        assertTrue("Snapshot must contains cache data [left=" + range + ']', range.isEmpty());
    }

    /** @throws Exception If failed. */
    @Test
    public void testClusterSnapshotOnInactive() throws Exception {
        injectTestSystemOut();

        startGrids(1);

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute("--snapshot", "create", "testSnapshotName"));

        assertContains(log, testOut.toString(), "Snapshot operation has been rejected. The cluster is inactive.");
    }

    /** @throws Exception If fails. */
    @Test
    public void testCancelSnapshot() throws Exception {
        IgniteEx srv = startGrid(0);
        IgniteEx startCli = startClientGrid(CLIENT_NODE_NAME_PREFIX);

        injectTestSystemOut();

        srv.cluster().state(ACTIVE);

        createCacheAndPreload(startCli, 100);

        TestCommandHandler h = newCommandHandler();

        // Cancel snapshot using operation ID.
        doSnapshotCancellationTest(startCli, Collections.singletonList(srv), startCli.cache(DEFAULT_CACHE_NAME),
            snpName -> {
                String reqId = srv.context().metric().registry(SNAPSHOT_METRICS).findMetric("LastRequestId").getAsString();
                assertFalse(F.isEmpty(reqId));

                assertEquals(EXIT_CODE_OK, execute(h, "--snapshot", "cancel", "--id", reqId));
            });

        // Cancel snapshot using snapshot name.
        doSnapshotCancellationTest(startCli, Collections.singletonList(srv), startCli.cache(DEFAULT_CACHE_NAME),
            snpName -> assertEquals(EXIT_CODE_OK, execute(h, "--snapshot", "cancel", "--name", snpName)));
    }

    /** @throws Exception If fails. */
    @Test
    public void testCheckSnapshot() throws Exception {
        String snpName = "snapshot_02052020";

        IgniteEx ig = startGrid(0);
        ig.cluster().state(ACTIVE);

        createCacheAndPreload(ig, 1000);

        snp(ig).createSnapshot(snpName).get();

        TestCommandHandler h = newCommandHandler();

        assertEquals(EXIT_CODE_OK, execute(h, "--snapshot", "check", snpName));

        StringBuilder sb = new StringBuilder();

        ((SnapshotPartitionsVerifyTaskResult)h.getLastOperationResult()).print(sb::append);

        assertContains(log, sb.toString(), "The check procedure has finished, no conflicts have been found");
    }

    /** @throws Exception If fails. */
    @Test
    public void testSnapshotRestoreSynchronously() throws Exception {
        String snpName = "snapshot_02052020";
        int keysCnt = 100;

        IgniteEx ig = startGrids(2);

        ig.cluster().state(ACTIVE);

        injectTestSystemOut();

        String cacheName = "test-cache";

        createCacheAndPreload(ig, cacheName, keysCnt, 32, null);

        ig.snapshot().createSnapshot(snpName).get(getTestTimeout());

        autoConfirmation = false;

        // Invalid command syntax checks.
        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--cancel", "--sync"));
        assertContains(log, testOut.toString(), "--sync and --cancel can't be used together");

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "blah"));
        assertContains(log, testOut.toString(), "Unexpected argument: blah");

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--status", "--sync"));
        assertContains(log, testOut.toString(), "--sync and --status can't be used together");

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--start", "blah"));
        assertContains(log, testOut.toString(), "Unexpected argument: blah");

        autoConfirmation = true;

        // Cache exists.
        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute("--snapshot", "restore", snpName, "--start", "--sync"));
        if (cliCommandHandler()) {
            assertContains(log, testOut.toString(), "Command option '--start' is redundant and must be avoided.");
            assertContains(log, testOut.toString(), "Unable to restore cache group - directory is not empty. " +
                "Cache group should be destroyed manually before perform restore operation [group=" + cacheName);
        }

        ig.cache(cacheName).destroy();
        awaitPartitionMapExchange();

        assertEquals(EXIT_CODE_OK, execute("--snapshot", "restore", snpName, "--sync"));
        if (cliCommandHandler()) {
            assertNotContains(log, testOut.toString(), "Command option '--start' is redundant and must be avoided.");
            assertContains(log, testOut.toString(), "Snapshot cache group restore operation completed successfully");
        }

        IgniteCache<Object, Object> cache = ig.cache(cacheName);

        assertNotNull(cache);

        for (int i = 0; i < keysCnt; i++)
            assertEquals("key=" + i, i, cache.get(i));
    }

    /** @throws Exception If fails. */
    @Test
    public void testSnapshotRestore() throws Exception {
        autoConfirmation = false;

        int keysCnt = 100;
        String snpName = "snapshot_02052020";
        String cacheName1 = "cache1";
        String cacheName2 = "cache2";
        String cacheName3 = "cache3";

        IgniteEx ig = startGrids(2);

        ig.cluster().state(ACTIVE);

        injectTestSystemOut();
        injectTestSystemIn(CONFIRM_MSG, CONFIRM_MSG);

        createCacheAndPreload(ig, cacheName1, keysCnt, 32, null);
        createCacheAndPreload(ig, cacheName2, keysCnt, 32, null);
        createCacheAndPreload(ig, cacheName3, keysCnt, 32, null);

        ig.snapshot().createSnapshot(snpName).get(getTestTimeout());

        IgniteCache<Integer, Integer> cache1 = ig.cache(cacheName1);
        IgniteCache<Integer, Integer> cache2 = ig.cache(cacheName2);
        IgniteCache<Integer, Integer> cache3 = ig.cache(cacheName3);

        cache1.destroy();
        cache2.destroy();
        cache3.destroy();

        awaitPartitionMapExchange();

        assertNull(ig.cache(cacheName1));
        assertNull(ig.cache(cacheName2));
        assertNull(ig.cache(cacheName3));

        TestCommandHandler h = newCommandHandler(createTestLogger());

        assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute(h, "--snapshot", "restore", snpName, cacheName1));
        assertContains(log, testOut.toString(), "Unexpected argument: " + cacheName1);

        // Restore single cache group.
        assertEquals(EXIT_CODE_OK, execute(h, "--snapshot", "restore", snpName, "--groups", cacheName1));

        waitForCondition(() -> ig.cache(cacheName1) != null, getTestTimeout());

        MetricRegistryImpl metrics = ig.context().metric().registry(SNAPSHOT_RESTORE_METRICS);
        Metric operIdMetric = metrics.findMetric("requestId");
        assertNotNull(operIdMetric);

        assertContains(log, testOut.toString(), "Snapshot cache group restore operation started " +
            "[name=" + snpName + ", group(s)=" + cacheName1 + ", id=" + operIdMetric.getAsString() + ']');

        cache1 = ig.cache(cacheName1);

        assertNotNull(cache1);

        for (int i = 0; i < keysCnt; i++)
            assertEquals(cacheName1, Integer.valueOf(i), cache1.get(i));

        cache1.destroy();

        awaitPartitionMapExchange();

        assertNull(ig.cache(cacheName1));
        assertNull(ig.cache(cacheName2));
        assertNull(ig.cache(cacheName3));

        String cacheNames = cacheName1 + ',' + cacheName2;

        // Restore two (of three) groups of caches.
        assertEquals(EXIT_CODE_OK, execute(h, "--snapshot", "restore", snpName, "--groups", cacheNames));
        assertContains(log, testOut.toString(),
            "Snapshot cache group restore operation started [name=" + snpName + ", group(s)=");

        waitForCondition(() -> ig.cache(cacheName1) != null, getTestTimeout());
        waitForCondition(() -> ig.cache(cacheName2) != null, getTestTimeout());

        cache1 = ig.cache(cacheName1);
        cache2 = ig.cache(cacheName2);

        assertNotNull(cache1);
        assertNotNull(cache2);

        for (int i = 0; i < keysCnt; i++) {
            assertEquals(cacheName1, Integer.valueOf(i), cache1.get(i));
            assertEquals(cacheName2, Integer.valueOf(i), cache2.get(i));
        }

        cache1.destroy();
        cache2.destroy();

        awaitPartitionMapExchange();

        for (boolean check: new boolean[] {false, true}) {
            assertNull(ig.cache(cacheName1));
            assertNull(ig.cache(cacheName2));
            assertNull(ig.cache(cacheName3));

            // Restore all public cache groups.
            if (check)
                assertEquals(EXIT_CODE_OK, execute(h, "--snapshot", "restore", snpName, "--check"));
            else
                assertEquals(EXIT_CODE_OK, execute(h, "--snapshot", "restore", snpName));

            if (cliCommandHandler()) {
                String out = testOut.toString();
                assertContains(log, out, "Warning: command will restore ALL USER-CREATED CACHE GROUPS from the snapshot");
                assertContains(log, out, "Snapshot cache group restore operation started [name=" + snpName);
            }

            waitForCondition(() -> ig.cache(cacheName1) != null, getTestTimeout());
            waitForCondition(() -> ig.cache(cacheName2) != null, getTestTimeout());
            waitForCondition(() -> ig.cache(cacheName3) != null, getTestTimeout());

            cache1 = ig.cache(cacheName1);
            cache2 = ig.cache(cacheName2);
            cache3 = ig.cache(cacheName3);

            assertNotNull(cache1);
            assertNotNull(cache2);
            assertNotNull(cache3);

            for (int i = 0; i < keysCnt; i++) {
                assertEquals(cacheName1, Integer.valueOf(i), cache1.get(i));
                assertEquals(cacheName2, Integer.valueOf(i), cache2.get(i));
                assertEquals(cacheName3, Integer.valueOf(i), cache2.get(i));
            }

            cache1.destroy();
            cache2.destroy();
            cache3.destroy();

            awaitPartitionMapExchange();
        }
    }

    /** @throws Exception If fails. */
    @Test
    public void testIncrementalSnapshotRestore() throws Exception {
        walCompactionEnabled(true);

        int keysCnt = 100;
        String snpName = "snapshot_06032023";
        String cacheName1 = "cache1";

        IgniteEx ig = startGrids(2);

        ig.cluster().state(ACTIVE);

        injectTestSystemOut();
        injectTestSystemIn(CONFIRM_MSG);

        createCacheAndPreload(ig, cacheName1, keysCnt, 32, null);

        ig.snapshot().createSnapshot(snpName).get(getTestTimeout());

        try (IgniteDataStreamer<Object, Object> streamer = ig.dataStreamer(cacheName1)) {
            for (int i = keysCnt; i < keysCnt * 2; i++)
                streamer.addData(i, i);
        }

        ig.snapshot().createIncrementalSnapshot(snpName).get(getTestTimeout());

        IgniteCache<Integer, Integer> cache1 = ig.cache(cacheName1);

        cache1.destroy();

        awaitPartitionMapExchange();

        assertNull(ig.cache(cacheName1));

        boolean autoConfirmation0 = autoConfirmation;
        autoConfirmation = false;

        try {
            // Missed increment index.
            assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--increment"));
            assertContains(
                log,
                testOut.toString(),
                "Please specify a value for argument: --increment"
            );

            // Wrong params.
            assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--increment", "wrong"));
            assertContains(log, testOut.toString(), "Can't parse number 'wrong'");

            // Missed increment index.
            assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--increment", "1", "--increment", "2"));
            assertContains(log, testOut.toString(), "--increment argument specified twice");

            // Non existent increment.
            assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--increment", "2", "--sync"));
            assertContains(log, testOut.toString(), "No incremental snapshot found [snpName=" + snpName);
        }
        finally {
            autoConfirmation = autoConfirmation0;
        }

        // Succesfull restore.
        assertEquals(EXIT_CODE_OK, execute("--snapshot", "restore", snpName, "--increment", "1", "--sync"));

        cache1 = ig.cache(cacheName1);

        assertNotNull(cache1);

        for (int i = 0; i < 2 * keysCnt; i++)
            assertEquals(Integer.valueOf(i), cache1.get(i));

        cache1.destroy();

        awaitPartitionMapExchange();

        assertNull(ig.cache(cacheName1));

        // Specify full increment index value.
        assertEquals(EXIT_CODE_OK, execute("--snapshot", "restore", snpName, "--increment", "0000000000000001", "--sync"));

        cache1 = ig.cache(cacheName1);

        assertNotNull(cache1);

        for (int i = 0; i < 2 * keysCnt; i++)
            assertEquals(Integer.valueOf(i), cache1.get(i));
    }

    /** @throws Exception If fails. */
    @Test
    public void testSnapshotCreateCheckAndRestoreCustomDir() throws Exception {
        int keysCnt = 100;
        String snpName = "snapshot_30052022";
        File snpDir = U.resolveWorkDirectory(U.defaultWorkDirectory(), "ex_snapshots", true);

        assertTrue("Target directory is not empty: " + snpDir, F.isEmpty(snpDir.list()));

        try {
            Ignite ignite = startGrids(2);
            ignite.cluster().state(ACTIVE);

            createCacheAndPreload(ignite, keysCnt);

            injectTestSystemOut();

            assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "create", snpName, "--dest", "A", "--dest", "B"));
            assertContains(log, testOut.toString(), "--dest argument specified twice");

            assertEquals(EXIT_CODE_OK,
                execute("--snapshot", "create", snpName, "--sync", "--dest", snpDir.getAbsolutePath()));

            ignite.destroyCache(DEFAULT_CACHE_NAME);

            assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "restore", snpName, "--sync"));
            assertContains(log, testOut.toString(), "Snapshot does not exists [snapshot=" + snpName);

            assertEquals(EXIT_CODE_INVALID_ARGUMENTS,
                execute("--snapshot", "restore", snpName, "--src", "A", "--src", "B"));
            assertContains(log, testOut.toString(), "--src argument specified twice");

            assertEquals(EXIT_CODE_INVALID_ARGUMENTS, execute("--snapshot", "check", snpName));
            assertContains(log, testOut.toString(), "Snapshot does not exists [snapshot=" + snpName);

            assertEquals(EXIT_CODE_OK, execute("--snapshot", "check", snpName, "--src", snpDir.getAbsolutePath()));
            assertContains(log, testOut.toString(), "The check procedure has finished, no conflicts have been found.");

            assertEquals(EXIT_CODE_OK,
                execute("--snapshot", "restore", snpName, "--sync", "--src", snpDir.getAbsolutePath()));

            IgniteCache<Integer, Integer> cache = ignite.cache(DEFAULT_CACHE_NAME);

            assertEquals(keysCnt, cache.size());

            for (int i = 0; i < keysCnt; i++)
                assertEquals(Integer.valueOf(i), cache.get(i));
        }
        finally {
            U.delete(snpDir);
        }
    }

    /** @throws Exception If fails. */
    @Test
    public void testSnapshotRestoreCancelAndStatus() throws Exception {
        int keysCnt = 2048;
        String snpName = "snapshot_25052021";
        String missingSnpName = "snapshot_MISSING";

        IgniteEx ig = startGrid(getConfiguration(getTestIgniteInstanceName(0)).setSnapshotThreadPoolSize(1));
        startGrid(1).cluster().state(ACTIVE);

        injectTestSystemOut();

        createCacheAndPreload(ig, keysCnt);

        ig.snapshot().createSnapshot(snpName).get(getTestTimeout());

        int locPartsCnt = ig.cachex(DEFAULT_CACHE_NAME).context().topology().localPartitionsNumber();

        ig.destroyCache(DEFAULT_CACHE_NAME);
        awaitPartitionMapExchange();

        CountDownLatch ioStartLatch = new CountDownLatch(1);
        IgniteSnapshotManager snpMgr = ig.context().cache().context().snapshotMgr();

        // Replace the IO factory in the snapshot manager so we have enough time to test the status command.
        snpMgr.ioFactory(new SlowDownFileIoFactory(snpMgr.ioFactory(), getTestTimeout() / locPartsCnt, ioStartLatch));

        // Restore single cache group.
        IgniteFuture<Void> restoreFut = snpMgr.restoreSnapshot(snpName, Collections.singleton(DEFAULT_CACHE_NAME));

        ioStartLatch.await(getTestTimeout(), TimeUnit.MILLISECONDS);
        assertFalse(restoreFut.isDone());

        // Check the status with a control command.
        assertEquals(EXIT_CODE_OK, execute("--snapshot", "status"));

        Pattern operIdPtrn = Pattern.compile("Operation request ID: (?<id>[-\\w]{36})");
        Matcher matcher = operIdPtrn.matcher(testOut.toString());
        assertTrue(matcher.find());

        String operIdStr = matcher.group("id");
        assertNotNull(operIdStr);

        // Check "status" with the wrong snapshot name.
        assertEquals(EXIT_CODE_OK, execute("--snapshot", "restore", missingSnpName, "--status"));
        assertContains(log, testOut.toString(),
            "Snapshot cache group restore operation is NOT running [snapshot=" + missingSnpName + ']');

        // Check "cancel" with the wrong snapshot name.
        assertEquals(EXIT_CODE_OK, execute("--snapshot", "restore", missingSnpName, "--cancel"));
        assertContains(log, testOut.toString(),
            "Snapshot cache group restore operation is NOT running [snapshot=" + missingSnpName + ']');

        // Cancel operation using control command.
        assertEquals(EXIT_CODE_OK, execute("--snapshot", "cancel", "--id", operIdStr));
        assertContains(log, testOut.toString(),
            "Snapshot operation cancelled [id=" + operIdStr + ']');

        GridTestUtils.assertThrowsAnyCause(log, () -> restoreFut.get(getTestTimeout()), IgniteCheckedException.class,
            "Operation has been canceled by the user.");

        // Make sure the context disappeared at node 1.
        boolean ctxDisposed =
            waitForCondition(() -> !grid(1).context().cache().context().snapshotMgr().isRestoring(), getTestTimeout());

        assertTrue(ctxDisposed);

        assertEquals(EXIT_CODE_OK, execute("--snapshot", "status"));
        assertContains(log, testOut.toString(), "There is no create or restore snapshot operation in progress.");

        assertNull(ig.cache(DEFAULT_CACHE_NAME));
    }

    /** @throws Exception If fails. */
    @Test
    public void testSnapshotStatusInMemory() throws Exception {
        persistenceEnable(false);

        startGrid();

        checkSnapshotStatus(false, false, false, null);
    }

    /** @throws Exception If fails. */
    @Test
    public void testSnapshotStatus() throws Exception {
        walCompactionEnabled(true);

        String snapshotName = "snapshot1";
        int keysCnt = 10_000;

        IgniteEx srv = startGrids(3);

        startClientGrid("client");

        srv.cluster().state(ACTIVE);

        createCacheAndPreload(srv, keysCnt);

        checkSnapshotStatus(false, false, false, null);

        TestRecordingCommunicationSpi spi = TestRecordingCommunicationSpi.spi(grid(1));

        spi.blockMessages((node, msg) -> msg instanceof SingleNodeMessage);

        // Create snapshot.
        IgniteFuture<Void> fut = srv.snapshot().createSnapshot(snapshotName);

        spi.waitForBlocked();

        checkSnapshotStatus(true, false, false, snapshotName);

        spi.stopBlock();

        fut.get(getTestTimeout());

        checkSnapshotStatus(false, false, false, null);

        // Create incremental snapshot.
        spi.blockMessages((node, msg) -> msg instanceof SingleNodeMessage);

        fut = srv.snapshot().createIncrementalSnapshot(snapshotName);

        spi.waitForBlocked();

        checkSnapshotStatus(true, false, true, snapshotName);

        spi.stopBlock();

        fut.get(getTestTimeout());

        checkSnapshotStatus(false, false, false, null);

        // Restore snapshot.
        srv.destroyCache(DEFAULT_CACHE_NAME);

        awaitPartitionMapExchange();

        spi.blockMessages((node, msg) -> msg instanceof SingleNodeMessage);

        fut = srv.snapshot().restoreSnapshot(snapshotName, F.asList(DEFAULT_CACHE_NAME));

        spi.waitForBlocked();

        checkSnapshotStatus(false, true, false, snapshotName);

        spi.stopBlock();

        fut.get(getTestTimeout());

        checkSnapshotStatus(false, false, false, null);

        // Restore incremental snapshot.
        srv.destroyCache(DEFAULT_CACHE_NAME);

        awaitPartitionMapExchange();

        spi.blockMessages((node, msg) -> msg instanceof SingleNodeMessage);

        fut = srv.snapshot().restoreSnapshot(snapshotName, F.asList(DEFAULT_CACHE_NAME), 1);

        spi.waitForBlocked();

        checkSnapshotStatus(false, true, true, snapshotName);

        spi.stopBlock();

        fut.get(getTestTimeout());

        checkSnapshotStatus(false, false, false, null);
    }

    /**
     * @param isCreating {@code True} if create snapshot operation is in progress.
     * @param isRestoring {@code True} if restore snapshot operation is in progress.
     * @param isIncremental {@code True} if incremental snapshot operation.
     * @param expName Expected snapshot name.
     */
    private void checkSnapshotStatus(boolean isCreating, boolean isRestoring, boolean isIncremental, String expName) throws Exception {
        Collection<Ignite> srvs = F.view(G.allGrids(), n -> !n.cluster().localNode().isClient());

        assertTrue(waitForCondition(() -> srvs.stream().allMatch(
                ignite -> {
                    IgniteSnapshotManager mgr = ((IgniteEx)ignite).context().cache().context().snapshotMgr();

                    return isCreating == mgr.isSnapshotCreating() && isRestoring == mgr.isRestoring();
                }),
            getTestTimeout()));

        injectTestSystemOut();

        int status = execute("--snapshot", "status");

        String out = testOut.toString();

        assertEquals(out, EXIT_CODE_OK, status);

        if (!isCreating && !isRestoring) {
            assertContains(log, out, "There is no create or restore snapshot operation in progress.");

            return;
        }

        if (isCreating)
            assertContains(log, out, "Create snapshot operation is in progress.");
        else
            assertContains(log, out, "Restore snapshot operation is in progress.");

        assertContains(log, out, "Incremental: " + isIncremental);
        assertContains(log, out, "Snapshot name: " + expName);

        if (isIncremental)
            assertContains(log, out, "Increment index: 1");

        srvs.forEach(srv -> assertContains(log, out, srv.cluster().localNode().id().toString()));
    }

    /** @throws Exception If failed. */
    @Test
    @WithSystemProperty(key = IGNITE_PDS_SKIP_CHECKPOINT_ON_NODE_STOP, value = "true")
    public void testCleaningGarbageAfterCacheDestroyedAndNodeStop_ControlConsoleUtil() throws Exception {
        new IgniteCacheGroupsWithRestartsTest().testFindAndDeleteGarbage(this::executeTaskViaControlConsoleUtil);
    }

    /**
     * Verification of successful warm-up stop.
     * <p/>
     * Steps:
     * 1)Starting node with warm-up;
     * 2)Stop warm-up;
     * 3)Waiting for a successful stop of warm-up and start of node.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testSuccessStopWarmUp() throws Exception {
        Assume.assumeTrue(cliCommandHandler());

        WarmUpTestPluginProvider provider = new WarmUpTestPluginProvider();

        IgniteConfiguration cfg = getConfiguration(getTestIgniteInstanceName(0)).setPluginProviders(provider);
        cfg.getDataStorageConfiguration().setDefaultWarmUpConfiguration(new BlockedWarmUpConfiguration());

        cfg.getConnectorConfiguration().setHost("localhost");
        cfg.getClientConnectorConfiguration().setHost("localhost");

        IgniteInternalFuture<IgniteEx> fut = runAsync(() -> startGrid(cfg));

        BlockedWarmUpStrategy blockedWarmUpStgy = (BlockedWarmUpStrategy)provider.strats.get(1);

        try {
            U.await(blockedWarmUpStgy.startLatch, 60, TimeUnit.SECONDS);

            // Arguments --user and --password are needed for additional sending of the GridClientAuthenticationRequest.
            assertEquals(EXIT_CODE_OK, execute("--user", "user", "--password", "123", "--warm-up", "--stop"));

            assertEquals(0, blockedWarmUpStgy.stopLatch.getCount());
        }
        finally {
            blockedWarmUpStgy.stopLatch.countDown();

            fut.get(60_000);
        }
    }

    /**
     * Check that command will not be executed because node has already started.
     * <p/>
     * Steps:
     * 1)Starting node;
     * 2)Attempt to stop warm-up;
     * 3)Waiting for an error because node has already started.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testFailStopWarmUp() throws Exception {
        Assume.assumeTrue(cliCommandHandler());

        startGrid(0);

        assertEquals(EXIT_CODE_UNEXPECTED_ERROR, execute("--warm-up", "--stop"));
    }

    /** @throws Exception If fails. */
    @Test
    public void testCacheIdleVerifyLogLevelDebug() throws Exception {
        IgniteEx ignite = startGrids(2);

        ignite.cluster().state(ACTIVE);

        IgniteCache<Object, Object> cache = ignite.createCache(new CacheConfiguration<>(DEFAULT_CACHE_NAME)
                .setAffinity(new RendezvousAffinityFunction(false, 32))
                .setBackups(1));

        cache.put("key", "value");

        injectTestSystemOut();

        setLoggerDebugLevel();

        assertEquals(EXIT_CODE_OK, execute("--cache", "idle_verify"));
        assertContains(log, testOut.toString(), "no conflicts have been found");
    }

    /**
     * Test to make sure that the '--baseline' command shows correct cluster state
     *
     * @throws Exception if failed.
     */
    @Test
    public void testClusterStateInBaselineCommand() throws Exception {
        Ignite ignite = startGrids(1);

        injectTestSystemOut();

        for (ClusterState state : ClusterState.values()) {
            ignite.cluster().state(state);
            assertEquals(EXIT_CODE_OK, execute("--baseline"));
            assertEquals(state, ignite.cluster().state());
            assertClusterState(state, testOut.toString());
        }
    }

    /** */
    @Test
    public void testOfflineCommand() throws Exception {
        try {
            registerCommands(new OfflineTestCommand());

            startGrid(0);

            injectTestSystemOut();

            String input = "Test Offline Command";

            assertEquals(EXIT_CODE_OK, execute("--offline-test", "--input", input));

            assertTrue(testOut.toString().contains(input));
        }
        finally {
            unregisterAll();
        }
    }

    /**
     * @param ignite Ignite to execute task on.
     * @param delFoundGarbage If clearing mode should be used.
     * @return Result of task run.
     */
    private FindAndDeleteGarbageInPersistenceTaskResult executeTaskViaControlConsoleUtil(
        IgniteEx ignite,
        boolean delFoundGarbage
    ) {
        TestCommandHandler hnd = newCommandHandler();

        List<String> args = new ArrayList<>(Arrays.asList("--yes", "--port", connectorPort(ignite),
            "--cache", "find_garbage", ignite.localNode().id().toString()));

        if (delFoundGarbage)
            args.add("--delete");

        hnd.execute(args);

        return hnd.getLastOperationResult();
    }

    /**
     * @param str String.
     * @param substr Substring to find in the specified string.
     * @return The number of substrings found in the specified string.
     */
    private int countSubstrs(String str, String substr) {
        int cnt = 0;

        for (int off = 0; (off = str.indexOf(substr, off)) != -1; off++)
            ++cnt;

        return cnt;
    }

    /** Test IO factory that slows down file creation. */
    private static class SlowDownFileIoFactory implements FileIOFactory {
        /** Delegated factory. */
        private final FileIOFactory delegate;

        /** Max slowdown interval. */
        private final long maxTimeout;

        /** Latch to notify when the first file will be created. */
        private final CountDownLatch ioStartLatch;

        /** Next file slowdown interval. */
        private long timeout = 10;

        /**
         * @param delegate Delegated factory.
         * @param maxTimeout Max slowdown interval.
         * @param ioStartLatch Latch to notify when the first file will be created.
         */
        private SlowDownFileIoFactory(FileIOFactory delegate, long maxTimeout, CountDownLatch ioStartLatch) {
            this.delegate = delegate;
            this.maxTimeout = maxTimeout;
            this.ioStartLatch = ioStartLatch;
        }

        /** {@inheritDoc} */
        @Override public FileIO create(File file, OpenOption... modes) throws IOException {
            try {
                if (ioStartLatch.getCount() > 0)
                    ioStartLatch.countDown();

                long currTimeout = maxTimeout;

                synchronized (this) {
                    if (timeout < maxTimeout) {
                        currTimeout = timeout;

                        timeout += timeout;
                    }
                }

                U.sleep(currTimeout);

                return delegate.create(file, modes);
            }
            catch (IgniteInterruptedCheckedException e) {
                Thread.currentThread().interrupt();

                throw new RuntimeException(e);
            }
        }
    }

    /**
     * @param state Current state of the cluster.
     * @param logOutput Logger output where current cluster state is supposed to be specified.
     */
    public static void assertClusterState(ClusterState state, String logOutput) {
        assertTrue(Pattern.compile("Cluster state: " + state + "\\s+").matcher(logOutput).find());
    }

    /** */
    public static class OfflineTestCommand implements OfflineCommand<OfflineTestCommandArg, Void> {
        /** {@inheritDoc} */
        @Override public String description() {
            return null;
        }

        /** {@inheritDoc} */
        @Override public Class<OfflineTestCommandArg> argClass() {
            return OfflineTestCommandArg.class;
        }

        /** {@inheritDoc} */
        @Override public Void execute(OfflineTestCommandArg arg, Consumer<String> printer) {
            printer.accept(arg.input());

            return null;
        }
    }

    /** */
    public static class OfflineTestCommandArg extends IgniteDataTransferObject {
        /** */
        @Argument
        private String input;

        /** */
        public String input() {
            return input;
        }

        /** */
        public void input(String input) {
            this.input = input;
        }

        /** {@inheritDoc} */
        @Override protected void writeExternalData(ObjectOutput out) throws IOException {
            U.writeString(out, input);
        }

        /** {@inheritDoc} */
        @Override protected void readExternalData(ObjectInput in) throws IOException {
            input = U.readString(in);
        }
    }
}
