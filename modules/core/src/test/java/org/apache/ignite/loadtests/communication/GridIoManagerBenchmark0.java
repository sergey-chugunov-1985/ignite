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

package org.apache.ignite.loadtests.communication;

import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteKernal;
import org.apache.ignite.internal.managers.communication.GridIoManager;
import org.apache.ignite.internal.managers.communication.GridMessageListener;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.spi.communication.CommunicationSpi;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.testframework.GridTestUtils;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Ignore;
import org.junit.Test;

import static org.apache.ignite.internal.managers.communication.GridIoPolicy.PUBLIC_POOL;

/**
 *
 */
// Benchmark.
@Ignore("https://issues.apache.org/jira/browse/IGNITE-13728")
public class GridIoManagerBenchmark0 extends GridCommonAbstractTest {
    /** */
    public static final int CONCUR_MSGS = 10 * 1024;

    /** */
    private static final int THREADS = 2;

    /** */
    private static final long TEST_TIMEOUT = 3 * 60 * 1000;

    /** {@inheritDoc} */
    @Override protected void beforeTestsStarted() throws Exception {
        startGridsMultiThreaded(2);
    }

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration c = super.getConfiguration(igniteInstanceName);

        c.setCommunicationSpi(getCommunication());

        return c;
    }

    /**
     * @param len Length.
     * @return Test string.
     */
    private static String generateTestString(int len) {
        assert len > 0;
        SB sb = new SB();

        for (int i = 0; i < len; i++)
            sb.a(Character.forDigit(i % 10, 10));

        return sb.toString();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testThroughput() throws Exception {
        final IgniteKernal sndKernal = (IgniteKernal)grid(0);
        final IgniteKernal rcvKernal = (IgniteKernal)grid(1);

        final ClusterNode sndNode = sndKernal.localNode();
        final ClusterNode rcvNode = rcvKernal.localNode();

        final GridIoManager snd = sndKernal.context().io();
        final GridIoManager rcv = rcvKernal.context().io();

        info("Senders: " + THREADS);
        info("Messages: " + CONCUR_MSGS);

        final Semaphore sem = new Semaphore(CONCUR_MSGS);
        final LongAdder msgCntr = new LongAdder();

        final String topic = "test-topic";

        rcv.addMessageListener(
            topic,
            new GridMessageListener() {
                @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                    try {
                        rcv.sendToCustomTopic(sndNode, topic, (Message)msg, PUBLIC_POOL);
                    }
                    catch (IgniteCheckedException e) {
                        error("Failed to send message.", e);
                    }
                }
            });

        snd.addMessageListener(topic, new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                msgCntr.increment();

                sem.release();
            }
        });

        Timer t = new Timer("results-reporter");

        t.schedule(new TimerTask() {
            private long ts = System.currentTimeMillis();

            @Override public void run() {
                long newTs = System.currentTimeMillis();
                long qrys = msgCntr.sumThenReset();

                long time = newTs - ts;

                X.println("Communication benchmark [qps=" + qrys * 1000 / time +
                    ", executed=" + qrys + ", time=" + time + ']');

                ts = newTs;
            }
        }, 10000, 10000);

        final AtomicBoolean finish = new AtomicBoolean();

        IgniteInternalFuture<?> f = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                try {
                    IgniteUuid msgId = IgniteUuid.randomUuid();

                    while (!finish.get()) {
                        sem.acquire();

                        snd.sendToCustomTopic(rcvNode, topic, new GridTestMessage(msgId, (String)null), PUBLIC_POOL);
                    }
                }
                catch (IgniteCheckedException e) {
                    X.println("Message send failed", e);
                }
                catch (InterruptedException ignored) {
                    // No-op.
                }

                return null;
            }
        }, THREADS, "send-thread");

        Thread.sleep(TEST_TIMEOUT);

        finish.set(true);

        sem.release(CONCUR_MSGS * 2);

        t.cancel();

        f.get();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testLatency() throws Exception {
        final IgniteKernal sndKernal = (IgniteKernal)grid(0);
        final IgniteKernal rcvKernal = (IgniteKernal)grid(1);

        final ClusterNode sndNode = sndKernal.localNode();
        final ClusterNode rcvNode = rcvKernal.localNode();

        final GridIoManager snd = sndKernal.context().io();
        final GridIoManager rcv = rcvKernal.context().io();

        final LongAdder msgCntr = new LongAdder();

        final Integer topic = 1;

        final Map<IgniteUuid, CountDownLatch> map = new ConcurrentHashMap<>();

        rcv.addMessageListener(
            topic,
            new GridMessageListener() {
                @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                    try {
                        rcv.sendToCustomTopic(sndNode, topic, (Message)msg, PUBLIC_POOL);
                    }
                    catch (IgniteCheckedException e) {
                        error("Failed to send message.", e);
                    }
                }
            });

        snd.addMessageListener(topic, new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                map.get(((GridTestMessage)msg).id()).countDown();
            }
        });

        Timer t = new Timer("results-reporter");

        t.schedule(new TimerTask() {
            private long ts = System.currentTimeMillis();

            @Override public void run() {
                long newTs = System.currentTimeMillis();
                long qrys = msgCntr.sumThenReset();

                long time = newTs - ts;

                X.println("Communication benchmark [qps=" + qrys * 1000 / time +
                    ", executed=" + qrys + ", time=" + time + ']');

                ts = newTs;
            }
        }, 10000, 10000);

        final AtomicBoolean finish = new AtomicBoolean();

        IgniteInternalFuture<?> f = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                try {
                    IgniteUuid msgId = IgniteUuid.randomUuid();

                    while (!finish.get()) {
                        CountDownLatch latch = new CountDownLatch(1);

                        map.put(msgId, latch);

                        snd.sendToCustomTopic(rcvNode, topic, new GridTestMessage(msgId, (String)null), PUBLIC_POOL);

                        latch.await();

                        msgCntr.increment();
                    }
                }
                catch (IgniteCheckedException e) {
                    X.println("Message send failed", e);
                }
                catch (InterruptedException ignored) {
                    // No-op.
                }

                return null;
            }
        }, 1, "send-thread");

        Thread.sleep(TEST_TIMEOUT);

        finish.set(true);

        t.cancel();

        f.get();
    }

    /**
     * @throws Exception If failed.
     */
    @Test
    public void testVariableLoad() throws Exception {
        final IgniteKernal sndKernal = (IgniteKernal)grid(0);
        final IgniteKernal rcvKernal = (IgniteKernal)grid(1);

        final ClusterNode sndNode = sndKernal.localNode();
        final ClusterNode rcvNode = rcvKernal.localNode();

        final GridIoManager snd = sndKernal.context().io();
        final GridIoManager rcv = rcvKernal.context().io();

        info("Senders: " + THREADS);
        info("Messages: " + CONCUR_MSGS);

        final Semaphore sem = new Semaphore(CONCUR_MSGS);
        final LongAdder msgCntr = new LongAdder();

        final String topic = "test-topic";

        final Map<IgniteUuid, CountDownLatch> latches = new ConcurrentHashMap<>();

        rcv.addMessageListener(
            topic,
            new GridMessageListener() {
                @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                    try {
                        rcv.sendToCustomTopic(sndNode, topic, (Message)msg, PUBLIC_POOL);
                    }
                    catch (IgniteCheckedException e) {
                        error("Failed to send message.", e);
                    }
                }
            });

        snd.addMessageListener(topic, new GridMessageListener() {
            @Override public void onMessage(UUID nodeId, Object msg, byte plc) {
                msgCntr.increment();

                sem.release();

                CountDownLatch latch = latches.get(((GridTestMessage)msg).id());

                if (latch != null)
                    latch.countDown();
            }
        });

        final AtomicBoolean finish = new AtomicBoolean();
        final AtomicReference<CountDownLatch> latchRef = new AtomicReference<>();

        IgniteInternalFuture<?> f = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                while (!finish.get()) {
                    CountDownLatch latch = latchRef.get();

                    if (latch != null)
                        U.await(latch);

                    IgniteUuid msgId = IgniteUuid.randomUuid();

                    sem.acquire();

                    snd.sendToCustomTopic(rcvNode, topic, new GridTestMessage(msgId, (String)null), PUBLIC_POOL);
                }

                return null;
            }
        }, THREADS, "send-thread");

        IgniteInternalFuture<?> f1 = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
            private long ts = System.currentTimeMillis();

            @Override public Object call() throws Exception {
                try {
                    while (!finish.get()) {
                        info(U.nl() + ">>>" + U.nl() + ">>> High load." + U.nl() + ">>>");

                        U.sleep(15 * 1000);

                        reportNumbers();

                        info(U.nl() + ">>>" + U.nl() + ">>> Low load." + U.nl() + ">>>");

                        CountDownLatch latch = new CountDownLatch(1);

                        try {
                            // Here will be a pause.
                            latchRef.set(latch);

                            U.sleep(7 * 1000);

                            reportNumbers();
                        }
                        finally {
                            latch.countDown();
                        }
                    }
                }
                catch (IgniteCheckedException e) {
                    X.println("Message send failed", e);
                }

                return null;
            }

            /**
             *
             */
            void reportNumbers() {
                long newTs = System.currentTimeMillis();
                long qrys = msgCntr.sumThenReset();

                long time = newTs - ts;

                X.println("Communication benchmark [qps=" + qrys * 1000 / time +
                    ", executed=" + qrys + ", time=" + time + ']');

                ts = newTs;

            }
        }, 1, "load-dispatcher");

        IgniteInternalFuture<?> f2 = GridTestUtils.runMultiThreadedAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                while (!finish.get()) {
                    U.sleep(1000);

                    IgniteUuid msgId = IgniteUuid.randomUuid();
                    CountDownLatch latch = new CountDownLatch(1);

                    latches.put(msgId, latch);

                    snd.sendToCustomTopic(rcvNode, topic, new GridTestMessage(msgId, (String)null), PUBLIC_POOL);

                    long start = System.currentTimeMillis();

                    latch.await();

                    info("Response time: " + (System.currentTimeMillis() - start));
                }

                return null;
            }
        }, THREADS, "low-loader");

        Thread.sleep(TEST_TIMEOUT);

        finish.set(true);

        sem.release(CONCUR_MSGS * 2);

        f.get();
        f1.get();
        f2.get();
    }

    /**
     * @return SPI instance.
     */
    private CommunicationSpi getCommunication() {
        TcpCommunicationSpi spi = new TcpCommunicationSpi();

        spi.setTcpNoDelay(true);

        info("Comm SPI: " + spi);

        return spi;
    }

    /** {@inheritDoc} */
    @Override protected long getTestTimeout() {
        return TEST_TIMEOUT + 60 * 1000;
    }
}
