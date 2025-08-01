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

package org.apache.ignite.internal.processors.cache.distributed.near;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.distributed.GridDistributedTxPrepareRequest;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxEntry;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.tostring.GridToStringInclude;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.plugin.extensions.communication.MessageReader;
import org.apache.ignite.plugin.extensions.communication.MessageWriter;
import org.jetbrains.annotations.Nullable;

/**
 * Near transaction prepare request to primary node. 'Near' means 'Initiating node' here, not 'Near Cache'.
 */
public class GridNearTxPrepareRequest extends GridDistributedTxPrepareRequest {
    /** */
    private static final int NEAR_FLAG_MASK = 0x01;

    /** */
    private static final int FIRST_CLIENT_REQ_FLAG_MASK = 0x02;

    /** */
    private static final int IMPLICIT_SINGLE_FLAG_MASK = 0x04;

    /** */
    private static final int EXPLICIT_LOCK_FLAG_MASK = 0x08;

    /** */
    private static final int ALLOW_WAIT_TOP_FUT_FLAG_MASK = 0x10;

    /** Recovery value flag. */
    private static final int RECOVERY_FLAG_MASK = 0x40;

    /** Future ID. */
    private IgniteUuid futId;

    /** Mini future ID. */
    private int miniId;

    /** Topology version. */
    private AffinityTopologyVersion topVer;

    /** Task name hash. */
    private int taskNameHash;

    /** */
    @GridToStringExclude
    private byte flags;

    /** Transaction label. */
    @GridToStringInclude
    @Nullable private String txLbl;

    /**
     * Empty constructor.
     */
    public GridNearTxPrepareRequest() {
        // No-op.
    }

    /**
     * @param futId Future ID.
     * @param topVer Topology version.
     * @param tx Transaction.
     * @param timeout Transaction timeout.
     * @param reads Read entries.
     * @param writes Write entries.
     * @param near {@code True} if mapping is for near caches.
     * @param txNodes Transaction nodes mapping.
     * @param last {@code True} if this last prepare request for node.
     * @param onePhaseCommit One phase commit flag.
     * @param retVal Return value flag.
     * @param implicitSingle Implicit single flag.
     * @param explicitLock Explicit lock flag.
     * @param taskNameHash Task name hash.
     * @param firstClientReq {@code True} if first optimistic tx prepare request sent from client node.
     * @param allowWaitTopFut {@code True} if it is safe for first client request to wait for topology future.
     * @param addDepInfo Deployment info flag.
     */
    public GridNearTxPrepareRequest(
        IgniteUuid futId,
        AffinityTopologyVersion topVer,
        GridNearTxLocal tx,
        long timeout,
        Collection<IgniteTxEntry> reads,
        Collection<IgniteTxEntry> writes,
        boolean near,
        Map<UUID, Collection<UUID>> txNodes,
        boolean last,
        boolean onePhaseCommit,
        boolean retVal,
        boolean implicitSingle,
        boolean explicitLock,
        int taskNameHash,
        boolean firstClientReq,
        boolean allowWaitTopFut,
        boolean addDepInfo,
        boolean recovery
    ) {
        super(tx,
            timeout,
            reads,
            writes,
            txNodes,
            retVal,
            last,
            onePhaseCommit,
            addDepInfo);

        assert futId != null;
        assert !firstClientReq || tx.optimistic() : tx;

        this.futId = futId;
        this.topVer = topVer;
        this.taskNameHash = taskNameHash;

        txLbl = tx.label();

        setFlag(near, NEAR_FLAG_MASK);
        setFlag(implicitSingle, IMPLICIT_SINGLE_FLAG_MASK);
        setFlag(explicitLock, EXPLICIT_LOCK_FLAG_MASK);
        setFlag(firstClientReq, FIRST_CLIENT_REQ_FLAG_MASK);
        setFlag(allowWaitTopFut, ALLOW_WAIT_TOP_FUT_FLAG_MASK);
        setFlag(recovery, RECOVERY_FLAG_MASK);
    }

    /**
     * @return {@code True} if it is safe for first client request to wait for topology future
     *      completion.
     */
    public boolean allowWaitTopologyFuture() {
        return isFlag(ALLOW_WAIT_TOP_FUT_FLAG_MASK);
    }

    /**
     * @return Recovery flag.
     */
    public final boolean recovery() {
        return isFlag(RECOVERY_FLAG_MASK);
    }

    /**
     * @param val Recovery flag.
     */
    public void recovery(boolean val) {
        setFlag(val, RECOVERY_FLAG_MASK);
    }

    /**
     * @return {@code True} if first optimistic tx prepare request sent from client node.
     */
    public final boolean firstClientRequest() {
        return isFlag(FIRST_CLIENT_REQ_FLAG_MASK);
    }

    /**
     * @return {@code True} if mapping is for near-enabled caches.
     */
    public final boolean near() {
        return isFlag(NEAR_FLAG_MASK);
    }

    /**
     * @return Future ID.
     */
    public IgniteUuid futureId() {
        return futId;
    }

    /**
     * @return Mini future ID.
     */
    public int miniId() {
        return miniId;
    }

    /**
     * @param miniId Mini future ID.
     */
    public void miniId(int miniId) {
        this.miniId = miniId;
    }

    /**
     * @return Task name hash.
     */
    public int taskNameHash() {
        return taskNameHash;
    }

    /**
     * @return Implicit single flag.
     */
    public final boolean implicitSingle() {
        return isFlag(IMPLICIT_SINGLE_FLAG_MASK);
    }

    /**
     * @return Explicit lock flag.
     */
    public final boolean explicitLock() {
        return isFlag(EXPLICIT_LOCK_FLAG_MASK);
    }

    /**
     * @return Topology version.
     */
    @Override public AffinityTopologyVersion topologyVersion() {
        return topVer;
    }

    /**
     * @return Transaction label.
     */
    @Nullable public String txLabel() {
        return txLbl;
    }

    /**
     *
     */
    public void cloneEntries() {
        reads(cloneEntries(reads()));
        writes(cloneEntries(writes()));
    }

    /**
     * Clones entries so that tx entries with initialized near entries are not passed to DHT transaction.
     * Used only when local part of prepare is invoked.
     *
     * @param c Collection of entries to clone.
     * @return Cloned collection.
     */
    private Collection<IgniteTxEntry> cloneEntries(Collection<IgniteTxEntry> c) {
        if (F.isEmpty(c))
            return Collections.emptyList();

        Collection<IgniteTxEntry> cp = new ArrayList<>(c.size());

        for (IgniteTxEntry e : c) {
            GridCacheContext<?, ?> cacheCtx = e.context();

            // Clone only if it is a near cache.
            if (cacheCtx.isNear())
                cp.add(e.cleanCopy(cacheCtx.nearTx().dht().context()));
            else
                cp.add(e);
        }

        return cp;
    }

    /** {@inheritDoc} */
    @Override protected boolean transferExpiryPolicy() {
        return true;
    }

    /**
     * Sets flag mask.
     *
     * @param flag Set or clear.
     * @param mask Mask.
     */
    private void setFlag(boolean flag, int mask) {
        flags = flag ? (byte)(flags | mask) : (byte)(flags & ~mask);
    }

    /**
     * Reags flag mask.
     *
     * @param mask Mask to read.
     * @return Flag value.
     */
    private boolean isFlag(int mask) {
        return (flags & mask) != 0;
    }

    /** {@inheritDoc} */
    @Override public boolean writeTo(ByteBuffer buf, MessageWriter writer) {
        writer.setBuffer(buf);

        if (!super.writeTo(buf, writer))
            return false;

        if (!writer.isHeaderWritten()) {
            if (!writer.writeHeader(directType()))
                return false;

            writer.onHeaderWritten();
        }

        switch (writer.state()) {
            case 21:
                if (!writer.writeByte(flags))
                    return false;

                writer.incrementState();

            case 22:
                if (!writer.writeIgniteUuid(futId))
                    return false;

                writer.incrementState();

            case 23:
                if (!writer.writeInt(miniId))
                    return false;

                writer.incrementState();

            case 24:
                if (!writer.writeInt(taskNameHash))
                    return false;

                writer.incrementState();

            case 25:
                if (!writer.writeAffinityTopologyVersion(topVer))
                    return false;

                writer.incrementState();

            case 26:
                if (!writer.writeString(txLbl))
                    return false;

                writer.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean readFrom(ByteBuffer buf, MessageReader reader) {
        reader.setBuffer(buf);

        if (!super.readFrom(buf, reader))
            return false;

        switch (reader.state()) {
            case 21:
                flags = reader.readByte();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

            case 22:
                futId = reader.readIgniteUuid();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

            case 23:
                miniId = reader.readInt();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

            case 24:
                taskNameHash = reader.readInt();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

            case 25:
                topVer = reader.readAffinityTopologyVersion();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

            case 26:
                txLbl = reader.readString();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public short directType() {
        return 55;
    }

    /** {@inheritDoc} */
    @Override public int partition() {
        return U.safeAbs(version().hashCode());
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        StringBuilder flags = new StringBuilder();

        if (near())
            flags.append("[near]");
        if (firstClientRequest())
            flags.append("[firstClientReq]");
        if (implicitSingle())
            flags.append("[implicitSingle]");
        if (explicitLock())
            flags.append("[explicitLock]");

        return S.toString(GridNearTxPrepareRequest.class, this,
            "flags", flags.toString(),
            "super", super.toString());
    }
}
