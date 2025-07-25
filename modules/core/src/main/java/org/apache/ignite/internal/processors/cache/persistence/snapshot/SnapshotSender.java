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

package org.apache.ignite.internal.processors.cache.persistence.snapshot;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.binary.BinaryType;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.internal.processors.cache.persistence.partstate.GroupPartitionId;
import org.apache.ignite.internal.processors.marshaller.MappedName;
import org.jetbrains.annotations.Nullable;

/**
 *
 */
public abstract class SnapshotSender {
    /** Busy processing lock. */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Executor to run operation at. */
    private final Executor exec;

    /** {@code true} if sender is currently working. */
    private volatile boolean closed;

    /** Ignite logger to use. */
    protected final IgniteLogger log;

    /**
     * @param log Ignite logger to use.
     */
    protected SnapshotSender(IgniteLogger log, Executor exec) {
        this.exec = exec;
        this.log = log.getLogger(SnapshotSender.class);
    }

    /**
     * @return Executor to run internal operations on.
     */
    public Executor executor() {
        return exec;
    }

    /**
     * @param mappings Local node marshaller mappings.
     */
    public final void sendMarshallerMeta(List<Map<Integer, MappedName>> mappings) {
        if (!lock.readLock().tryLock())
            return;

        try {
            if (closed)
                return;

            if (mappings == null)
                return;

            sendMarshallerMeta0(mappings);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @param types Collection of known binary types.
     */
    public final void sendBinaryMeta(Collection<BinaryType> types) {
        if (!lock.readLock().tryLock())
            return;

        try {
            if (closed)
                return;

            if (types == null)
                return;

            sendBinaryMeta0(types);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @param ccfgFile Cache configuration file.
     * @param ccfg Cache configuration.
     */
    public final void sendCacheConfig(File ccfgFile, CacheConfiguration<?, ?> ccfg) {
        if (!lock.readLock().tryLock())
            return;

        try {
            if (closed)
                return;

            sendCacheConfig0(ccfgFile, ccfg);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @param from Partition file to send.
     * @param to Destination file.
     * @param storagePath Cache storage path.
     * @param pair Group id with partition id pair.
     * @param length Partition length.
     * @see CacheConfiguration#getStoragePaths()
     * @see CacheConfiguration#getIndexPath()
     */
    public final void sendPart(File from, File to, @Nullable String storagePath, GroupPartitionId pair, Long length) {
        if (!lock.readLock().tryLock())
            return;

        try {
            if (closed)
                return;

            sendPart0(from, to, storagePath, pair, length);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * @param delta Delta pages file.
     * @param snpPart Snapshot partition.
     * @param pair Group id with partition id pair.
     */
    public final void sendDelta(File delta, File snpPart, GroupPartitionId pair) {
        if (!lock.readLock().tryLock())
            return;

        try {
            if (closed)
                return;

            sendDelta0(delta, snpPart, pair);
        }
        finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Closes this snapshot sender and releases any resources associated with it.
     * If the sender is already closed then invoking this method has no effect.
     *
     * @param th An exception occurred during snapshot operation processing.
     */
    public final void close(@Nullable Throwable th) {
        lock.writeLock().lock();

        try {
            close0(th);

            closed = true;
        }
        finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * @param partsCnt Number of objects to process.
     */
    protected abstract void init(int partsCnt);

    /**
     * @param from Partition file to send.
     * @param to Patition file to copy to.
     * @param storagePath Cache storage path.
     * @param pair Group id with partition id pair.
     * @param length Partition length.
     * @see CacheConfiguration#getStoragePaths()
     * @see CacheConfiguration#getIndexPath()
     */
    protected abstract void sendPart0(File from, File to, @Nullable String storagePath, GroupPartitionId pair, Long length);

    /**
     * @param delta Delta pages file.
     * @param snpPart Snapshot partition.
     * @param pair Group id with partition id pair.
     */
    protected abstract void sendDelta0(File delta, File snpPart, GroupPartitionId pair);

    /**
     * @param mappings Local node marshaller mappings.
     */
    protected void sendMarshallerMeta0(List<Map<Integer, MappedName>> mappings) {
        // No-op by default.
    }

    /**
     * @param types Collection of known binary types.
     */
    protected void sendBinaryMeta0(Collection<BinaryType> types) {
        // No-op by default.
    }

    /**
     * @param ccfgFile Cache configuration file.
     * @param ccfg Cache configuration.
     */
    protected void sendCacheConfig0(File ccfgFile, CacheConfiguration<?, ?> ccfg) {
        // No-op by default.
    }

    /**
     * Closes this snapshot sender and releases any resources associated with it.
     * If the sender is already closed then invoking this method has no effect.
     */
    protected void close0(@Nullable Throwable th) {
        // No-op by default.
    }
}
