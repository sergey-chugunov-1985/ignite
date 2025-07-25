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

package org.apache.ignite.internal.processors.cache.persistence.tree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.failure.FailureContext;
import org.apache.ignite.failure.FailureType;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.IgniteVersionUtils;
import org.apache.ignite.internal.UnregisteredBinaryTypeException;
import org.apache.ignite.internal.UnregisteredClassException;
import org.apache.ignite.internal.metric.IoStatisticsHolder;
import org.apache.ignite.internal.metric.IoStatisticsHolderNoOp;
import org.apache.ignite.internal.pagemem.PageIdUtils;
import org.apache.ignite.internal.pagemem.PageMemory;
import org.apache.ignite.internal.pagemem.wal.IgniteWriteAheadLogManager;
import org.apache.ignite.internal.pagemem.wal.record.delta.FixCountRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.FixLeftmostChildRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.FixRemoveId;
import org.apache.ignite.internal.pagemem.wal.record.delta.InsertRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.MetaPageAddRootRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.MetaPageCutRootRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.MetaPageInitRootInlineFlagsCreatedVersionRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.NewRootInitRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.RemoveRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.ReplaceRecord;
import org.apache.ignite.internal.pagemem.wal.record.delta.SplitExistingPageRecord;
import org.apache.ignite.internal.processors.cache.persistence.CorruptedDataStructureException;
import org.apache.ignite.internal.processors.cache.persistence.DataStructure;
import org.apache.ignite.internal.processors.cache.persistence.diagnostic.pagelocktracker.PageLockTrackerManager;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusInnerIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusLeafIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.BPlusMetaIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.IOVersions;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIO;
import org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIoResolver;
import org.apache.ignite.internal.processors.cache.persistence.tree.reuse.LongListReuseBag;
import org.apache.ignite.internal.processors.cache.persistence.tree.reuse.ReuseBag;
import org.apache.ignite.internal.processors.cache.persistence.tree.reuse.ReuseList;
import org.apache.ignite.internal.processors.cache.persistence.tree.util.PageHandler;
import org.apache.ignite.internal.processors.cache.persistence.tree.util.PageHandlerWrapper;
import org.apache.ignite.internal.processors.failure.FailureProcessor;
import org.apache.ignite.internal.util.GridArrays;
import org.apache.ignite.internal.util.GridLongList;
import org.apache.ignite.internal.util.IgniteTree;
import org.apache.ignite.internal.util.lang.GridCursor;
import org.apache.ignite.internal.util.lang.GridTreePrinter;
import org.apache.ignite.internal.util.lang.GridTuple3;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteInClosure;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.IgniteSystemProperties.IGNITE_BPLUS_TREE_LOCK_RETRIES;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Bool.DONE;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Bool.FALSE;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Bool.READY;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Bool.TRUE;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Result.FOUND;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Result.GO_DOWN;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Result.GO_DOWN_X;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Result.NOT_FOUND;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Result.RETRY;
import static org.apache.ignite.internal.processors.cache.persistence.tree.BPlusTree.Result.RETRY_ROOT;
import static org.apache.ignite.internal.processors.cache.persistence.tree.io.PageIoResolver.DEFAULT_PAGE_IO_RESOLVER;

/**
 * <h3>Abstract B+Tree</h3>
 *
 * B+Tree is a block-based tree structure. Each block is represented with the page ({@link PageIO}) and contains a
 * single tree node. There are two types of pages/nodes: {@link BPlusInnerIO} and {@link BPlusLeafIO}.
 * <p/>
 * Every page in the tree contains a list of <i>items</i>. Item is just a fixed-size binary payload.
 * Inner nodes and leaves may have different item sizes. There's a limit on how many items each page can hold.
 * It is defined by a {@link BPlusIO#getMaxCount(long, int)} method of the corresponding IO. There should be no empty
 * pages in trees, so:
 * <ul>
 *     <li>a leaf page must have from {@code 1} to {@code max} items</li>
 *     <li>
 *         an inner page must have from {@code 0} to {@code max} items (an inner page with 0 items is a routing page,
 *         it still has 1 pointer to 1 child, it's not considered an empty page; see below)
 *     </li>
 * </ul>
 * <p/>
 * Items might have different meaning depending on the type of the page. In case of leaves, every item must describe a
 * key and a value. In case of inner nodes, items describe only keys if {@link #canGetRowFromInner} is {@code false},
 * or a key and a value otherwise. Items in every page are sorted according to the order dscribed by
 * {@link #compare(BPlusIO, long, int, Object)} method. Specifics of the data stored in items are defined in the
 * implementation and generally don't matter.
 * <p/>
 * All pages in the tree are divided into levels. Leaves are always at the level {@code 0}. Levels of inner pages are
 * thus positive. Each level represents a singly linked list - each page has a link to the <i>forward</i> page at the
 * same level. It can be retrieved by calling {@link BPlusIO#getForward(long)}. This link must be a zero if there's no
 * forward page. Forward links on level {@code 0} allow iterating tree's keys and values effectively without traversing
 * any inner nodes ({@code AbstractForwardCursor}). Forward links in inner nodes have different purpose, more on that
 * later.
 * <p/>
 * Leaves have no links other than forward links. But inner nodes also have links to their children nodes. Every inner
 * node can be viewed like the following structure:
 * <pre><code>
 *       item(0)     item(1)        ...          item(N-1)
 * link(0)     link(1)     link(2)  ...  link(N-1)       link(N)
 * </code></pre>
 * There are {@code N} items and {@code N+1} links. Each link points to page of a lower level. For example, pages on
 * level {@code 2} always point to pages of level {@code 1}. For an item {@code i} left subtree is defined by
 * {@code link(i)} and right subtree is defined by {@code link(i+1)} ({@link BPlusInnerIO#getLeft(long, int)} and
 * {@link BPlusInnerIO#getRight(long, int)}). All items in the left subtree are less or equal to the original item
 * (basic property for the trees).
 * <p/>
 * There's one more important property of these links: {@code forward(left(i)) == right(i)}. It is called the
 * <i>triangle invariant</i>. More information on B+Tree structure can easily be found online. Following documentation
 * concentrates more on specifics of this particular B+Tree implementation.
 * <p/>
 *
 * <h3>General operations</h3>
 * This implementation allows for concurrent reads and update. Given that each page locks individually, there are
 * general rules to avoid deadlocks.
 * <ul>
 *     <li>
 *         Pages within a level always locked from left to right.
 *     </li>
 *     <li>
 *         If there's already a lock on the page of level X then no locks should be acquired on levels less than X.
 *         In other words, locks are aquired from the bottom to the top (in the direction from leaves to root).
 *         The only exception to this rule is the allocation of a new page on a lower level that no one sees yet.
 *         </li>
 * </ul>
 * All basic operations fit into a similar pattern. First, the search is performed ({@link Get}). It goes recursively
 * from the root to the leaf (if it's needed). On each level several outcomes are possible.
 * <ul>
 *     <li>Exact value is found on the leaf level and operation can be completed.</li>
 *     <li>Insertion point is found and recursive procedure continues on the lower level.</li>
 *     <li>Insertion point is not found due to concurrent modifications, but retry in the same node is possible.</li>
 *     <li>Insertion point is not found due to concurrent modifications, but retry in the same node is impossible.</li>
 * </ul>
 * All these options, and more, are described in the class {@link Result}. Please refer to its usages for specifics of
 * each operation. Once the path and the leaf for put/remove is found, the operation is then performed from the bottom
 * to the top. Specifics are described in corresponding classes ({@link Put}, {@link Remove}).
 * <p/>
 *
 * <h3>Maintained invariants</h3>
 * <ol>
 *     <li>Triangle invariant (see above), used to detect concurrent tree structure changes</li>
 *     <li>Each key existing in an inner page also exists in exactly one leaf, as its rightmost key</li>
 *     <li>
 *         For each leaf that is not the rightmost leaf in the tree (i.e. its forwardId is not 0), its rightmost key
 *         exists in exactly one of its ancestor blocks.
 *         <p/>
 *         The invariant is maintained using special cases in insert with split, replace and remove scenarios.
 *     </li>
 * </ol>
 *
 * <h3>Invariants that are NOT maintained</h3>
 * <ol>
 *     <li>
 *         Classic <a href="https://en.wikipedia.org/wiki/B-tree">B-Tree</a> (and B+Tree as well) makes sure
 *         that each non-root node is at least half-full. This implementation does NOT maintain this invariant.
 *     </li>
 * </ol>
 *
 * <h3>Merge properties</h3>
 * When a key is removed from a leaf node, the node might become empty and hence a mandatory merge happens. If
 * the parent is a <em>routing page</em> (see below), another mandatory merge will happen. (Mandatory merges are
 * the ones that must happen to maintain the tree invariants). This procedure may propagate a few levels up if there
 * is a chain of routing pages as ancestors.
 * <p/>
 * After all mandatory merges happen, we try to go up and make another merge (by merging the reached ancestor and its
 * sibling, if they fit in one block). Such a merge is called a <em>regular merge</em> in the code. It is not
 * mandatory to maintain invariants, but it improves tree structure from the point of view of performance. If first
 * regular merge is successful, the attempt will be repeated one level higher, and so on.
 * <p/>
 *
 * <h3>Routing pages</h3>
 * An inner (i.e. non-leaf) page is called a <em>routing page</em> if it contains zero items (hence, zero keys), but
 * it still contains one pointer to a child one level below. (This is valid because an inner page contains one pointer
 * more than item count.)
 * <p/>
 * An inner page becomes a routing page when removing last item from it (as a consequence to one of its children
 * becoming empty due to a removal somewhere below), AND due to inability to merge the page with its sibling because
 * the sibling is full.
 * <p/>
 * A confusion might arise between routing pages and empty pages. A routing page does not contain any items, but it does
 * contain a pointer to its single child, so it is not treated as an empty page (and we keep such pages in the tree).
 */
@SuppressWarnings({"ConstantValueVariableUse"})
public abstract class BPlusTree<L, T extends L> extends DataStructure implements IgniteTree<L, T> {
    /** */
    private static final Object[] EMPTY = {};

    /** Wrapper for tree pages operations. Noop by default. Override for test purposes. */
    public static PageHandlerWrapper<Result> testHndWrapper = null;

    /** */
    public static final ThreadLocal<Boolean> suspendFailureDiagnostic = ThreadLocal.withInitial(() -> false);

    /** Destroy msg. */
    public static final String CONC_DESTROY_MSG = "Tree is being concurrently destroyed: ";

    /** */
    private static volatile boolean interrupted;

    /** */
    public static final int IGNITE_BPLUS_TREE_LOCK_RETRIES_DEFAULT = 1000;

    /** */
    private static final int LOCK_RETRIES = IgniteSystemProperties.getInteger(
        IGNITE_BPLUS_TREE_LOCK_RETRIES, IGNITE_BPLUS_TREE_LOCK_RETRIES_DEFAULT);

    /** */
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    /** */
    private final float minFill;

    /** */
    private final float maxFill;

    /** */
    protected final long metaPageId;

    /** */
    private boolean canGetRowFromInner;

    /** */
    private IOVersions<? extends BPlusInnerIO<L>> innerIos;

    /** */
    private IOVersions<? extends BPlusLeafIO<L>> leafIos;

    /** */
    private final AtomicLong globalRmvId;

    /** */
    private volatile TreeMetaData treeMeta;

    /** Failure processor. */
    private final FailureProcessor failureProcessor;

    /** Flag for enabling single-threaded append-only tree creation. */
    private boolean sequentialWriteOptsEnabled;

    /** */
    private final GridTreePrinter<Long> treePrinter = new GridTreePrinter<Long>() {
        /** */
        private boolean keys = true;

        /** {@inheritDoc} */
        @Override protected List<Long> getChildren(final Long pageId) {
            if (pageId == null || pageId == 0L)
                return null;

            try {
                long page = acquirePage(pageId);

                try {
                    long pageAddr = readLock(pageId, page); // No correctness guaranties.

                    if (pageAddr == 0)
                        return null;

                    try {
                        BPlusIO io = io(pageAddr);

                        if (io.isLeaf())
                            return Collections.emptyList();

                        int cnt = io.getCount(pageAddr);

                        assert cnt >= 0 : cnt;

                        List<Long> res;

                        if (cnt > 0) {
                            res = new ArrayList<>(cnt + 1);

                            for (int i = 0; i < cnt; i++)
                                res.add(inner(io).getLeft(pageAddr, i));

                            res.add(inner(io).getRight(pageAddr, cnt - 1));
                        }
                        else {
                            long left = inner(io).getLeft(pageAddr, 0);

                            res = left == 0 ? Collections.<Long>emptyList() : Collections.singletonList(left);
                        }

                        return res;
                    }
                    finally {
                        readUnlock(pageId, page, pageAddr);
                    }
                }
                finally {
                    releasePage(pageId, page);
                }
            }
            catch (IgniteCheckedException ignored) {
                throw new AssertionError("Can not acquire page.");
            }
        }

        /** {@inheritDoc} */
        @Override protected String formatTreeNode(final Long pageId) {
            if (pageId == null)
                return ">NPE<";

            if (pageId == 0L)
                return "<Zero>";

            try {
                long page = acquirePage(pageId);
                try {
                    long pageAddr = readLock(pageId, page); // No correctness guaranties.
                    if (pageAddr == 0)
                        return "<Obsolete>";

                    try {
                        BPlusIO<L> io = io(pageAddr);

                        return printPage(io, pageAddr, keys);
                    }
                    finally {
                        readUnlock(pageId, page, pageAddr);
                    }
                }
                finally {
                    releasePage(pageId, page);
                }
            }
            catch (IgniteCheckedException e) {
                throw new IllegalStateException(e);
            }
        }
    };

    /** */
    private final PageHandler<Get, Result> askNeighbor;

    /**
     *
     */
    private class AskNeighbor extends GetPageHandler<Get> {
        /** {@inheritDoc} */
        @Override public Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, Get g, int isBack) {
            assert !io.isLeaf(); // Inner page.

            boolean back = isBack == TRUE.ordinal();

            long res = doAskNeighbor(io, pageAddr, back);

            if (back) {
                if (io.getForward(pageAddr) != g.backId) // See how g.backId is setup in removeDown for this check.
                    return RETRY;

                g.backId(res);
            }
            else {
                assert isBack == FALSE.ordinal() : isBack;

                g.fwdId(res);
            }

            return FOUND;
        }
    }

    /** */
    private final PageHandler<Get, Result> search;

    /**
     *
     */
    public class Search extends GetPageHandler<Get> {
        /** {@inheritDoc} */
        @Override public Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, Get g, int lvl)
            throws IgniteCheckedException {
            // Check the triangle invariant.
            if (io.getForward(pageAddr) != g.fwdId)
                return RETRY;

            boolean needBackIfRouting = g.backId != 0;

            g.backId(0L); // Usually we'll go left down and don't need it.

            int cnt = io.getCount(pageAddr);

            int idx;

            if (g.findLast)
                idx = io.isLeaf() ? cnt - 1 : -cnt - 1; // (-cnt - 1) mimics not_found result of findInsertionPoint
                // in case of cnt = 0 we end up in 'not found' branch below with idx being 0 after fix() adjustment
            else
                idx = findInsertionPoint(lvl, io, pageAddr, 0, cnt, g.row, g.shift);

            boolean found = idx >= 0;

            if (found) { // Found exact match.
                assert g.getClass() != GetCursor.class;

                if (g.found(io, pageAddr, idx, lvl))
                    return FOUND;

                // Else we need to reach leaf page, go left down.
            }
            else {
                idx = fix(idx);

                if (g.notFound(io, pageAddr, idx, lvl)) // No way down, stop here.
                    return NOT_FOUND;
            }

            assert !io.isLeaf() : io;

            // If idx == cnt then we go right down, else left down: getLeft(cnt) == getRight(cnt - 1).
            g.pageId(inner(io).getLeft(pageAddr, idx));

            // If we see the tree in consistent state, then our right down page must be forward for our left down page,
            // we need to setup fwdId and/or backId to be able to check this invariant on lower level.
            if (idx < cnt) {
                // Go left down here.
                g.fwdId(inner(io).getRight(pageAddr, idx));
            }
            else {
                // Go right down here or it is an empty branch.
                assert idx == cnt;

                // Here child's forward is unknown to us (we either go right or it is an empty "routing" page),
                // need to ask our forward about the child's forward (it must be leftmost child of our forward page).
                // This is ok from the locking standpoint because we take all locks in the forward direction.
                long fwdId = io.getForward(pageAddr);

                // Setup fwdId.
                if (fwdId == 0)
                    g.fwdId(0L);
                else {
                    // We can do askNeighbor on forward page here because we always take locks in forward direction.
                    Result res = askNeighbor(fwdId, g, false);

                    if (res != FOUND)
                        return res; // Retry.
                }

                // Setup backId.
                if (cnt != 0) // It is not a routing page and we are going to the right, can get backId here.
                    g.backId(inner(io).getLeft(pageAddr, cnt - 1));
                else if (needBackIfRouting) {
                    // Can't get backId here because of possible deadlock and it is only needed for remove operation.
                    return GO_DOWN_X;
                }
            }

            return GO_DOWN;
        }
    }

    /** */
    private final PageHandler<Put, Result> replace;

    /**
     *
     */
    public class Replace extends GetPageHandler<Put> {
        /** {@inheritDoc} */
        @Override public Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, Put p, int lvl)
            throws IgniteCheckedException {
            // Check the triangle invariant.
            if (io.getForward(pageAddr) != p.fwdId)
                return RETRY;

            assert p.btmLvl == 0 : "split is impossible with replace";
            assert lvl == 0 : "Replace via page handler is only possible on the leaves level.";

            final int cnt = io.getCount(pageAddr);
            final int idx = findInsertionPoint(lvl, io, pageAddr, 0, cnt, p.row, 0);

            if (idx < 0) // Not found, split or merge happened.
                return RETRY;

            assert p.oldRow == null : "The old row must be set only once.";

            // Lock the leaf if the row should be replaced in an inner node as well.
            if (canGetRowFromInner && idx + 1 == cnt && p.fwdId != 0L) {
                Tail<L> tail = p.addTail(pageId, page, pageAddr, io, lvl, Tail.EXACT);

                // Row index is cached, because it won't change until the leaf is unlocked.
                tail.idx = (short)idx;

                return FOUND;
            }

            // Row exists in this leaf only. No other actions will be required.

            // Read old row before actual replacement.
            p.oldRow = p.needOld ? getRow(io, pageAddr, idx) : (T)Boolean.TRUE;

            p.replaceRowInPage(io, pageId, page, pageAddr, idx);

            p.finish();

            return FOUND;
        }
    }

    /** */
    private final PageHandler<Put, Result> insert;

    /**
     *
     */
    public class Insert extends GetPageHandler<Put> {
        /** {@inheritDoc} */
        @Override public Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, Put p, int lvl)
            throws IgniteCheckedException {
            assert p.btmLvl == lvl : "we must always insert at the bottom level: " + p.btmLvl + " " + lvl;

            // Check triangle invariant.
            if (io.getForward(pageAddr) != p.fwdId)
                return RETRY;

            int cnt = io.getCount(pageAddr);
            int idx = findInsertionPoint(lvl, io, pageAddr, 0, cnt, p.row, 0);

            if (idx >= 0) // We do not support concurrent put of the same key.
                throw new IllegalStateException("Duplicate row in index.");

            idx = fix(idx);

            // Do insert.
            L moveUpRow = p.insert(pageId, page, pageAddr, io, idx, lvl);

            // Check if split happened.
            if (moveUpRow != null) {
                p.btmLvl++; // Get high.
                p.row = moveUpRow;

                if (p.invoke != null)
                    p.invoke.row = moveUpRow;

                // Here forward page can't be concurrently removed because we keep write lock on tail which is the only
                // page who knows about the forward page, because it was just produced by split.
                p.rightId = io.getForward(pageAddr);
                p.setTailForSplit(pageId, page, pageAddr, io, p.btmLvl - 1);

                assert p.rightId != 0;
            }
            else
                p.finish();

            return FOUND;
        }
    }

    /** */
    private final PageHandler<Remove, Result> rmvFromLeaf;

    /**
     *
     */
    private class RemoveFromLeaf<R extends Remove> extends GetPageHandler<R> {
        /** {@inheritDoc} */
        @Override public Result run0(long leafId, long leafPage, long leafAddr, BPlusIO<L> io, R r, int lvl)
            throws IgniteCheckedException {
            assert lvl == 0 : lvl; // Leaf.

            // Check the triangle invariant.
            if (io.getForward(leafAddr) != r.fwdId)
                return RETRY;

            final int cnt = io.getCount(leafAddr);

            assert cnt <= Short.MAX_VALUE : cnt;

            int idx = findInsertionPoint(lvl, io, leafAddr, 0, cnt, r.row, 0);

            if (idx < 0)
                return RETRY; // We've found exact match on search but now it's gone.

            return doRemoveOrLockTail(idx, cnt, 1, leafId, leafPage, leafAddr, io, r);
        }

        /**
         * @param idx Insertion index.
         * @param cnt Row count.
         * @param rmvCnt Number of rows to remove.
         * @param leafId Leaf page ID.
         * @param leafPage Leaf page pointer.
         * @param leafAddr Leaf page address.
         * @param io IO.
         * @param r Remove operation.
         * @throws IgniteCheckedException If failed.
         */
        protected Result doRemoveOrLockTail(
            int idx,
            int cnt,
            int rmvCnt,
            long leafId,
            long leafPage,
            long leafAddr,
            BPlusIO<L> io,
            R r
        ) throws IgniteCheckedException {
            assert idx >= 0 && idx < cnt : idx;

            // Need to do inner replace when we remove the rightmost element and the leaf has a forward page,
            // i.e. it is not the rightmost leaf of the tree.
            boolean needReplaceInner = canGetRowFromInner && idx == cnt - rmvCnt && io.getForward(leafAddr) != 0;

            // !!! Before modifying state we have to make sure that we will not go for retry.

            // We may need to replace inner key or want to merge this leaf with sibling after the remove -> keep lock.
            if (needReplaceInner ||
                // We need to make sure that we have back or forward to be able to merge.
                ((r.fwdId != 0 || r.backId != 0) && mayMerge(cnt - rmvCnt, io.getMaxCount(leafAddr, pageSize())))) {
                // If we have backId then we've already locked back page, nothing to do here.
                if (r.fwdId != 0 && r.backId == 0) {
                    Result res = r.lockForward(0);

                    if (res != FOUND) {
                        assert r.tail == null;

                        return res; // Retry.
                    }

                    assert r.tail != null; // We've just locked forward page.
                }

                // Retry must reset these fields when we release the whole branch without remove.
                assert !r.needReplaceInner && r.needMergeEmptyBranch == FALSE
                        : "needReplaceInner=" + r.needReplaceInner + ", needMergeEmptyBranch=" + r.needMergeEmptyBranch;

                if (cnt == rmvCnt) // It was the last element on the leaf.
                    r.needMergeEmptyBranch = TRUE;

                r.needReplaceInner = needReplaceInner;

                Tail<L> t = r.addTail(leafId, leafPage, leafAddr, io, 0, Tail.EXACT);

                t.idx = (short)idx;

                // We will do the actual remove only when we made sure that
                // we've locked the whole needed branch correctly.
                return FOUND;
            }

            r.removeDataRowFromLeaf(leafId, leafPage, leafAddr, null, io, cnt, idx);

            return FOUND;
        }
    }

    /** */
    private final PageHandler<Remove, Result> rmvRangeFromLeaf;

    /**
     *
     */
    private class RemoveRangeFromLeaf extends RemoveFromLeaf<RemoveRange> {
        /** {@inheritDoc} */
        @Override public Result run0(long leafId, long leafPage, long leafAddr, BPlusIO<L> io, RemoveRange r, int lvl)
            throws IgniteCheckedException {
            assert lvl == 0 : lvl; // Leaf.

            // Check the triangle invariant.
            if (io.getForward(leafAddr) != r.fwdId)
                return RETRY;

            final int cnt = io.getCount(leafAddr);

            assert cnt <= Short.MAX_VALUE : cnt;

            int idx = findInsertionPoint(lvl, io, leafAddr, 0, cnt, r.lower, 0);

            if (idx < 0) {
                idx = fix(idx);

                // Before the page was locked, its state could have changed, so you need to make sure that
                // it has elements from the range, otherwise repeat the search.
                if (idx == cnt || compare(io, leafAddr, idx, r.upper) > 0)
                    return RETRY;
            }

            r.highIdx = findInsertionPoint(lvl, io, leafAddr, idx, cnt, r.upper, 0);

            int highIdx = r.highIdx >= 0 ? r.highIdx : fix(r.highIdx) - 1;

            if (r.remaining != -1 && highIdx - idx + 1 >= r.remaining)
                highIdx = idx + r.remaining - 1;

            assert highIdx >= idx : "low=" + idx + ", high=" + highIdx;

            r.highIdx = r.highIdx >= 0 ? highIdx : -highIdx - 1;

            Result res = doRemoveOrLockTail(idx, cnt, highIdx - idx + 1, leafId, leafPage, leafAddr, io, r);

            // Search row should point to the rightmost element, otherwise we won't find it on the inner node.
            if (res == FOUND && r.needReplaceInner)
                r.row = getRow(io, leafAddr, highIdx, r.x);

            return res;
        }
    }

    /** */
    private final PageHandler<Remove, Result> lockBackAndRmvFromLeaf;

    /**
     *
     */
    private class LockBackAndRmvFromLeaf extends GetPageHandler<Remove> {
        /** {@inheritDoc} */
        @Override protected Result run0(long backId, long backPage, long backAddr, BPlusIO<L> io, Remove r, int lvl)
            throws IgniteCheckedException {
            // Check that we have consistent view of the world.
            if (io.getForward(backAddr) != r.pageId)
                return RETRY;

            // Correct locking order: from back to forward.
            Result res = r.doRemoveFromLeaf();

            // Keep locks on back and leaf pages for subsequent merges.
            if (res == FOUND && r.tail != null)
                r.addTail(backId, backPage, backAddr, io, lvl, Tail.BACK);

            return res;
        }
    }

    /** */
    private final PageHandler<Remove, Result> lockBackAndTail;

    /**
     *
     */
    private class LockBackAndTail extends GetPageHandler<Remove> {
        /** {@inheritDoc} */
        @Override public Result run0(long backId, long backPage, long backAddr, BPlusIO<L> io, Remove r, int lvl)
            throws IgniteCheckedException {
            // Check that we have consistent view of the world.
            if (io.getForward(backAddr) != r.pageId)
                return RETRY;

            // Correct locking order: from back to forward.
            Result res = r.doLockTail(lvl);

            if (res == FOUND)
                r.addTail(backId, backPage, backAddr, io, lvl, Tail.BACK);

            return res;
        }
    }

    /** */
    private final PageHandler<Remove, Result> lockTailForward;

    /**
     *
     */
    private class LockTailForward extends GetPageHandler<Remove> {
        /** {@inheritDoc} */
        @Override protected Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, Remove r, int lvl)
            throws IgniteCheckedException {
            r.addTail(pageId, page, pageAddr, io, lvl, Tail.FORWARD);

            return FOUND;
        }
    }

    /**
     * Page handler that adds the page to the tail of {@link Update} object.
     * Results in {@link Result#FOUND} if added to tail successfully.
     * Results in {@link Result#RETRY} if triangle invariant is violated.
     */
    private final PageHandler<Update, Result> lockTailExact;

    /** */
    private class LockTailExact extends GetPageHandler<Update> {
        /** {@inheritDoc} */
        @Override protected Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, Update u, int lvl) {
            // Check the triangle invariant.
            if (io.getForward(pageAddr) != u.fwdId)
                return RETRY;

            u.addTail(pageId, page, pageAddr, io, lvl, Tail.EXACT);

            return FOUND;
        }
    }

    /** */
    private final PageHandler<Remove, Result> lockTail;

    /**
     *
     */
    private class LockTail extends GetPageHandler<Remove> {
        /** {@inheritDoc} */
        @Override public Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, Remove r, int lvl)
            throws IgniteCheckedException {
            assert lvl > 0 : lvl; // We are not at the bottom.

            // Check that we have a correct view of the world.
            if (io.getForward(pageAddr) != r.fwdId)
                return RETRY;

            // We don't have a back page, need to lock our forward and become a back for it.
            if (r.fwdId != 0 && r.backId == 0) {
                Result res = r.lockForward(lvl);

                if (res != FOUND)
                    return res; // Retry.
            }

            r.addTail(pageId, page, pageAddr, io, lvl, Tail.EXACT);

            return FOUND;
        }
    }

    /** */
    private final PageHandler<Void, Bool> cutRoot = new CutRoot();

    /**
     *
     */
    private class CutRoot extends PageHandler<Void, Bool> {
        /** {@inheritDoc} */
        @Override public Bool run(int cacheId, long metaId, long metaPage, long metaAddr, PageIO iox, Boolean walPlc,
            Void ignore, int lvl,
            IoStatisticsHolder statHolder)
            throws IgniteCheckedException {
            // Safe cast because we should never recycle meta page until the tree is destroyed.
            BPlusMetaIO io = (BPlusMetaIO)iox;

            assert lvl == io.getRootLevel(metaAddr); // Can drop only root.

            io.cutRoot(metaAddr, pageSize());

            if (needWalDeltaRecord(metaId, metaPage, walPlc))
                wal.log(new MetaPageCutRootRecord(cacheId, metaId));

            int newLvl = lvl - 1;

            assert io.getRootLevel(metaAddr) == newLvl;

            treeMeta = new TreeMetaData(newLvl, io.getFirstPageId(metaAddr, newLvl));

            return TRUE;
        }
    }

    /** */
    private final PageHandler<Long, Bool> addRoot = new AddRoot();

    /**
     *
     */
    private class AddRoot extends PageHandler<Long, Bool> {
        /** {@inheritDoc} */
        @Override public Bool run(int cacheId, long metaId, long metaPage, long pageAddr, PageIO iox, Boolean walPlc,
            Long rootPageId, int lvl,
            IoStatisticsHolder statHolder)
            throws IgniteCheckedException {
            assert rootPageId != null;

            // Safe cast because we should never recycle meta page until the tree is destroyed.
            BPlusMetaIO io = (BPlusMetaIO)iox;

            assert lvl == io.getLevelsCount(pageAddr);

            io.addRoot(pageAddr, rootPageId, pageSize());

            if (needWalDeltaRecord(metaId, metaPage, walPlc))
                wal.log(new MetaPageAddRootRecord(cacheId, metaId, rootPageId));

            assert io.getRootLevel(pageAddr) == lvl;
            assert io.getFirstPageId(pageAddr, lvl) == rootPageId;

            treeMeta = new TreeMetaData(lvl, rootPageId);

            return TRUE;
        }
    }

    /** */
    private final PageHandler<Long, Bool> initRoot = new InitRoot();

    /**
     *
     */
    private class InitRoot extends PageHandler<Long, Bool> {
        /** {@inheritDoc} */
        @Override public Bool run(int cacheId, long metaId, long metaPage, long pageAddr, PageIO iox, Boolean walPlc,
            Long rootId, int inlineSize,
            IoStatisticsHolder statHolder)
            throws IgniteCheckedException {
            assert rootId != null;

            // Safe cast because we should never recycle meta page until the tree is destroyed.
            BPlusMetaIO io = (BPlusMetaIO)iox;

            io.initRoot(pageAddr, rootId, pageSize());
            io.setInlineSize(pageAddr, inlineSize);
            io.initFlagsAndVersion(pageAddr, BPlusMetaIO.DEFAULT_FLAGS, IgniteVersionUtils.VER);

            if (needWalDeltaRecord(metaId, metaPage, walPlc))
                wal.log(new MetaPageInitRootInlineFlagsCreatedVersionRecord(cacheId, metaId, rootId, inlineSize));

            assert io.getRootLevel(pageAddr) == 0;
            assert io.getFirstPageId(pageAddr, 0) == rootId;

            treeMeta = new TreeMetaData(0, rootId);

            return TRUE;
        }
    }

    /**
     * @param name Tree name.
     * @param cacheGrpId Cache group ID.
     * @param cacheGrpName Cache group name.
     * @param pageMem Page memory.
     * @param wal Write ahead log manager.
     * @param globalRmvId Remove ID.
     * @param metaPageId Meta page ID.
     * @param reuseList Reuse list.
     * @param innerIos Inner IO versions.
     * @param leafIos Leaf IO versions.
     * @param pageFlag Default flag value for allocated pages.
     * @param failureProcessor if the tree is corrupted.
     * @param pageLockTrackerManager Page lock tracker manager.
     * @throws IgniteCheckedException If failed.
     */
    protected BPlusTree(
        String name,
        int cacheGrpId,
        String cacheGrpName,
        PageMemory pageMem,
        @Nullable IgniteWriteAheadLogManager wal,
        AtomicLong globalRmvId,
        long metaPageId,
        @Nullable ReuseList reuseList,
        IOVersions<? extends BPlusInnerIO<L>> innerIos,
        IOVersions<? extends BPlusLeafIO<L>> leafIos,
        byte pageFlag,
        @Nullable FailureProcessor failureProcessor,
        PageLockTrackerManager pageLockTrackerManager
    ) throws IgniteCheckedException {
        this(
            name,
            cacheGrpId,
            cacheGrpName,
            pageMem,
            wal,
            globalRmvId,
            metaPageId,
            reuseList,
            pageFlag,
            failureProcessor,
            pageLockTrackerManager,
            DEFAULT_PAGE_IO_RESOLVER,
            null
        );

        setIos(innerIos, leafIos);
    }

    /**
     * @param name Tree name.
     * @param cacheGrpId Cache ID.
     * @param grpName Cache group name.
     * @param pageMem Page memory.
     * @param wal Write ahead log manager.
     * @param globalRmvId Remove ID.
     * @param metaPageId Meta page ID.
     * @param reuseList Reuse list.
     * @param pageFlag Default flag value for allocated pages.
     * @param failureProcessor if the tree is corrupted.
     * @param pageLockTrackerManager Page lock tracker manager.
     */
    protected BPlusTree(
        String name,
        int cacheGrpId,
        String grpName,
        PageMemory pageMem,
        @Nullable IgniteWriteAheadLogManager wal,
        AtomicLong globalRmvId,
        long metaPageId,
        ReuseList reuseList,
        byte pageFlag,
        @Nullable FailureProcessor failureProcessor,
        PageLockTrackerManager pageLockTrackerManager,
        PageIoResolver pageIoRslvr,
        @Nullable PageHandlerWrapper<Result> hndWrapper
    ) {
        super(name, cacheGrpId, grpName, pageMem, wal, pageLockTrackerManager, pageIoRslvr, pageFlag);

        // TODO make configurable: 0 <= minFill <= maxFill <= 1
        minFill = 0f; // Testing worst case when merge happens only on empty page.
        maxFill = 0f; // Avoiding random effects on testing.

        assert metaPageId != 0L;

        this.metaPageId = metaPageId;
        this.reuseList = reuseList;
        this.globalRmvId = globalRmvId;
        this.failureProcessor = failureProcessor;

        // Initialize page handlers.
        askNeighbor = wrap(hndWrapper, new AskNeighbor());
        search = wrap(hndWrapper, new Search());
        lockTailExact = wrap(hndWrapper, new LockTailExact());
        lockTail = wrap(hndWrapper, new LockTail());
        lockTailForward = wrap(hndWrapper, new LockTailForward());
        lockBackAndTail = wrap(hndWrapper, new LockBackAndTail());
        lockBackAndRmvFromLeaf = wrap(hndWrapper, new LockBackAndRmvFromLeaf());
        rmvFromLeaf = wrap(hndWrapper, new RemoveFromLeaf<>());
        insert = wrap(hndWrapper, new Insert());
        replace = wrap(hndWrapper, new Replace());
        rmvRangeFromLeaf = wrap(hndWrapper, new RemoveRangeFromLeaf());
    }

    /**
     * Returns a wrapped page handler. By default, there is no wrapper.
     *
     * @param hndWrapper Page handler wrapper for this tree.
     * @param hnd Page handler.
     * @return Wrapped page handler.
     */
    private <X> PageHandler<X, Result> wrap(PageHandlerWrapper<Result> hndWrapper, PageHandler<?, Result> hnd) {
        // Wrap handler using test wrapper.
        if (testHndWrapper != null)
            hnd = testHndWrapper.wrap(this, hnd);

        // Additionally wrap using tree page handler wrapper, if it's specified.
        return (PageHandler<X, Result>)(hndWrapper == null ? hnd : hndWrapper.wrap(this, hnd));
    }

    /**
     * @param innerIos Inner IO versions.
     * @param leafIos Leaf IO versions.
     */
    public void setIos(IOVersions<? extends BPlusInnerIO<L>> innerIos,
        IOVersions<? extends BPlusLeafIO<L>> leafIos) {
        assert innerIos != null;
        assert leafIos != null;

        this.canGetRowFromInner = innerIos.latest().canGetRow(); // TODO refactor
        this.innerIos = innerIos;
        this.leafIos = leafIos;
    }

    /** Flag for enabling single-threaded append-only tree creation. */
    public void enableSequentialWriteMode() {
        sequentialWriteOptsEnabled = true;
    }

    /**
     * Initialize new tree.
     *
     * @param initNew {@code True} if new tree should be created.
     * @throws IgniteCheckedException If failed.
     */
    protected final void initTree(boolean initNew) throws IgniteCheckedException {
        initTree(initNew, 0);
    }

    /**
     * Initialize new tree.
     *
     * @param initNew {@code True} if new tree should be created.
     * @param inlineSize Inline size.
     * @throws IgniteCheckedException If failed.
     */
    protected final void initTree(boolean initNew, int inlineSize) throws IgniteCheckedException {
        if (initNew) {
            // Allocate the first leaf page, it will be our root.
            long rootId = allocatePage(null);

            init(rootId, latestLeafIO());

            // Initialize meta page with new root page.
            Bool res = write(metaPageId, initRoot, BPlusMetaIO.VERSIONS.latest(), rootId, inlineSize, FALSE,
                statisticsHolder());

            assert res == TRUE : res;

            assert treeMeta != null;
        }
    }

    /**
     * @return Tree meta data.
     * @throws IgniteCheckedException If failed.
     */
    private TreeMetaData treeMeta() throws IgniteCheckedException {
        return treeMeta(0L);
    }

    /**
     * @param metaPageAddr Meta page address. If equals {@code 0}, it means that we should do read lock on
     * meta page and get meta page address. Otherwise we will not do the lock and will use the given address.
     * @return Tree meta data.
     * @throws IgniteCheckedException If failed.
     */
    private TreeMetaData treeMeta(final long metaPageAddr) throws IgniteCheckedException {
        TreeMetaData meta0 = treeMeta;

        if (meta0 != null)
            return meta0;

        final long metaPage = acquirePage(metaPageId);
        try {
            long pageAddr;

            if (metaPageAddr == 0L) {
                pageAddr = readLock(metaPageId, metaPage);

                assert pageAddr != 0 : "Failed to read lock meta page [metaPageId=" +
                    U.hexLong(metaPageId) + ']';
            }
            else
                pageAddr = metaPageAddr;

            try {
                BPlusMetaIO io = BPlusMetaIO.VERSIONS.forPage(pageAddr);

                int rootLvl = io.getRootLevel(pageAddr);
                long rootId = io.getFirstPageId(pageAddr, rootLvl);

                treeMeta = meta0 = new TreeMetaData(rootLvl, rootId);
            }
            finally {
                if (metaPageAddr == 0L)
                    readUnlock(metaPageId, metaPage, pageAddr);
            }
        }
        finally {
            releasePage(metaPageId, metaPage);
        }

        return meta0;
    }

    /**
     * @return Root level.
     * @throws IgniteCheckedException If failed.
     */
    private int getRootLevel() throws IgniteCheckedException {
        return getRootLevel(0L);
    }

    /**
     * @param metaPageAddr Meta page address. If equals {@code 0}, it means that we should do read lock on
     * meta page and get meta page address. Otherwise we will not do the lock and will use the given address.
     * @return Root level.
     * @throws IgniteCheckedException If failed.
     */
    private int getRootLevel(long metaPageAddr) throws IgniteCheckedException {
        TreeMetaData meta0 = treeMeta(metaPageAddr);

        assert meta0 != null;

        return meta0.rootLvl;
    }

    /**
     * @param metaId Meta page ID.
     * @param metaPage Meta page pointer.
     * @param lvl Level, if {@code 0} then it is a bottom level, if negative then root.
     * @return Page ID.
     */
    private long getFirstPageId(long metaId, long metaPage, int lvl) {
        return getFirstPageId(metaId, metaPage, lvl, 0L);
    }

    /**
     * @param metaId Meta page ID.
     * @param metaPage Meta page pointer.
     * @param lvl Level, if {@code 0} then it is a bottom level, if negative then root.
     * @param metaPageAddr Meta page address. If equals {@code 0}, it means that we should do read lock on
     * meta page and get meta page address. Otherwise we will not do the lock and will use the given address.
     * @return Page ID.
     */
    private long getFirstPageId(long metaId, long metaPage, int lvl, final long metaPageAddr) {
        long pageAddr = metaPageAddr != 0L ? metaPageAddr : readLock(metaId, metaPage); // Meta can't be removed.

        try {
            BPlusMetaIO io = BPlusMetaIO.VERSIONS.forPage(pageAddr);

            if (lvl < 0)
                lvl = io.getRootLevel(pageAddr);

            if (lvl >= io.getLevelsCount(pageAddr))
                return 0;

            return io.getFirstPageId(pageAddr, lvl);
        }
        finally {
            if (metaPageAddr == 0L)
                readUnlock(metaId, metaPage, pageAddr);
        }
    }

    /**
     * @param upper Upper bound.
     * @param upIncl {@code true} if upper bound is inclusive.
     * @param c Filter closure.
     * @param rowFactory Row factory or (@code null} for default factory.
     * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
     * @return Cursor.
     * @throws IgniteCheckedException If failed.
     */
    private GridCursor<T> findLowerUnbounded(
        L upper,
        boolean upIncl,
        TreeRowClosure<L, T> c,
        TreeRowFactory<L, T> rowFactory,
        Object x
    ) throws IgniteCheckedException {
        ForwardCursor cursor = new ForwardCursor(upper, upIncl, c, rowFactory, x);

        long firstPageId;

        long metaPage = acquirePage(metaPageId);
        try {
            firstPageId = getFirstPageId(metaPageId, metaPage, 0); // Level 0 is always at the bottom.
        }
        finally {
            releasePage(metaPageId, metaPage);
        }

        try {
            long firstPage = acquirePage(firstPageId);

            try {
                long pageAddr = readLock(firstPageId, firstPage); // We always merge pages backwards, the first page is never removed.

                try {
                    cursor.init(pageAddr, io(pageAddr), -1);
                }
                finally {
                    readUnlock(firstPageId, firstPage, pageAddr);
                }
            }
            finally {
                releasePage(firstPageId, firstPage);
            }
        }
        catch (RuntimeException | AssertionError e) {
            throw new BPlusTreeRuntimeException(e, grpId, metaPageId, firstPageId);
        }

        return cursor;
    }

    /**
     * Check if the tree is getting destroyed.
     */
    protected final void checkDestroyed() throws IgniteCheckedException {
        if (destroyed.get())
            throw new IgniteCheckedException(CONC_DESTROY_MSG + name());
    }

    /** {@inheritDoc} */
    @Override public final GridCursor<T> find(L lower, L upper) throws IgniteCheckedException {
        return find(lower, upper, null);
    }

    /** {@inheritDoc} */
    @Override public final GridCursor<T> find(L lower, L upper, Object x) throws IgniteCheckedException {
        return find(lower, upper, null, x);
    }

    /**
     * @param lower Lower bound inclusive or {@code null} if unbounded.
     * @param upper Upper bound inclusive or {@code null} if unbounded.
     * @param c Filter closure.
     * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
     * @return Cursor.
     * @throws IgniteCheckedException If failed.
     */
    public GridCursor<T> find(L lower, L upper, TreeRowClosure<L, T> c, Object x) throws IgniteCheckedException {
        return find(lower, upper, true, true, c, null, x);
    }

    /**
     * @param lower Lower bound or {@code null} if unbounded.
     * @param upper Upper bound or {@code null} if unbounded.
     * @param lowIncl {@code true} if lower bound is inclusive.
     * @param upIncl {@code true} if upper bound is inclusive.
     * @param c Filter closure.
     * @param rowFactory Row factory or (@code null} for default factory.
     * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
     * @return Cursor.
     * @throws IgniteCheckedException If failed.
     */
    public GridCursor<T> find(
        L lower,
        L upper,
        boolean lowIncl,
        boolean upIncl,
        TreeRowClosure<L, T> c,
        TreeRowFactory<L, T> rowFactory,
        Object x
    ) throws IgniteCheckedException {
        checkDestroyed();

        ForwardCursor cursor = new ForwardCursor(lower, upper, lowIncl, upIncl, c, rowFactory, x);

        try {
            if (lower == null)
                return findLowerUnbounded(upper, upIncl, c, rowFactory, x);

            cursor.find();

            return cursor;
        }
        catch (CorruptedDataStructureException e) {
            throw e;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteCheckedException("Runtime failure on bounds: [lower=" + lower + ", upper=" + upper + "]", e);
        }
        catch (RuntimeException | AssertionError e) {
            long[] pageIds = pages(
                lower == null || cursor == null || cursor.getCursor == null,
                () -> new long[]{cursor.getCursor.pageId}
            );

            throw corruptedTreeException(
                "Runtime failure on bounds: [lower=" + lower + ", upper=" + upper + "]",
                e, grpId, pageIds
            );
        }
        finally {
            checkDestroyed();
        }
    }

    /** {@inheritDoc} */
    @Override public T findFirst() throws IgniteCheckedException {
        return findFirst(null);
    }

    /**
     * Returns a value mapped to the lowest key, or {@code null} if tree is empty or no entry matches the passed filter.
     * @param filter Filter closure.
     * @return Value.
     * @throws IgniteCheckedException If failed.
     */
    public T findFirst(TreeRowClosure<L, T> filter) throws IgniteCheckedException {
        checkDestroyed();

        long curPageId = 0L;
        long nextPageId = 0L;

        try {
            for (;;) {

                long metaPage = acquirePage(metaPageId);

                try {
                    curPageId = getFirstPageId(metaPageId, metaPage, 0); // Level 0 is always at the bottom.
                }
                finally {
                    releasePage(metaPageId, metaPage);
                }

                long curPage = acquirePage(curPageId);
                try {
                    long curPageAddr = readLock(curPageId, curPage);

                    if (curPageAddr == 0)
                        continue; // The first page has gone: restart scan.

                    try {
                        BPlusIO<L> io = io(curPageAddr);

                        assert io.isLeaf();

                        for (;;) {
                            int cnt = io.getCount(curPageAddr);

                            for (int i = 0; i < cnt; ++i) {
                                if (filter == null || filter.apply(this, io, curPageAddr, i))
                                    return getRow(io, curPageAddr, i);
                            }

                            nextPageId = io.getForward(curPageAddr);

                            if (nextPageId == 0)
                                return null;

                            long nextPage = acquirePage(nextPageId);

                            try {
                                long nextPageAddr = readLock(nextPageId, nextPage);

                                // In the current implementation the next page can't change when the current page is locked.
                                assert nextPageAddr != 0 : nextPageAddr;

                                try {
                                    long pa = curPageAddr;
                                    curPageAddr = 0; // Set to zero to avoid double unlocking in finalizer.

                                    readUnlock(curPageId, curPage, pa);

                                    long p = curPage;
                                    curPage = 0; // Set to zero to avoid double release in finalizer.

                                    releasePage(curPageId, p);

                                    curPageId = nextPageId;
                                    curPage = nextPage;
                                    curPageAddr = nextPageAddr;

                                    nextPage = 0;
                                    nextPageAddr = 0;
                                }
                                finally {
                                    if (nextPageAddr != 0)
                                        readUnlock(nextPageId, nextPage, nextPageAddr);
                                }
                            }
                            finally {
                                if (nextPage != 0)
                                    releasePage(nextPageId, nextPage);
                            }
                        }
                    }
                    finally {
                        if (curPageAddr != 0)
                            readUnlock(curPageId, curPage, curPageAddr);
                    }
                }
                finally {
                    if (curPage != 0)
                        releasePage(curPageId, curPage);
                }
            }
        }
        catch (CorruptedDataStructureException e) {
            throw e;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteCheckedException("Runtime failure on first row lookup", e);
        }
        catch (RuntimeException | AssertionError e) {
            throw corruptedTreeException("Runtime failure on first row lookup", e, grpId, curPageId, nextPageId);
        }
        finally {
            checkDestroyed();
        }
    }

    /** {@inheritDoc} */
    @Override public T findLast() throws IgniteCheckedException {
        return findLast(null);
    }

    /**
     * Returns a value mapped to the greatest key, or {@code null} if tree is empty or no entry matches the passed filter.
     * @param c Filter closure.
     * @return Value.
     * @throws IgniteCheckedException If failed.
     */
    public T findLast(final TreeRowClosure<L, T> c) throws IgniteCheckedException {
        checkDestroyed();

        Get g = null;

        try {
            if (c == null) {
                g = new GetOne(null, null, null, true);

                doFind(g);

                return (T)g.row;
            }
            else {
                GetLast gLast = new GetLast(c);

                g = gLast;

                return gLast.find();
            }
        }
        catch (CorruptedDataStructureException e) {
            throw e;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteCheckedException("Runtime failure on last row lookup", e);
        }
        catch (RuntimeException | AssertionError e) {
            Get g0 = g;

            long[] pageIds = pages(g == null, () -> new long[]{g0.pageId});

            throw corruptedTreeException("Runtime failure on last row lookup", e, grpId, pageIds);
        }
        finally {
            checkDestroyed();
        }
    }

    /**
     * @param row Lookup row for exact match.
     * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
     * @return Found result or {@code null}
     * @throws IgniteCheckedException If failed.
     */
    public final <R> R findOne(L row, Object x) throws IgniteCheckedException {
        return findOne(row, null, x);
    }

    /**
     * @param row Lookup row for exact match.
     * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
     * @return Found result or {@code null}.
     * @throws IgniteCheckedException If failed.
     */
    public final <R> R findOne(L row, TreeRowClosure<L, T> c, Object x) throws IgniteCheckedException {
        checkDestroyed();

        GetOne g = new GetOne(row, c, x, false);

        try {
            doFind(g);

            return (R)g.row;
        }
        catch (CorruptedDataStructureException e) {
            throw e;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteCheckedException("Runtime failure on lookup row: " + row, e);
        }
        catch (RuntimeException | AssertionError e) {
            throw corruptedTreeException("Runtime failure on lookup row: " + row, e, grpId, g.pageId);
        }
        finally {
            checkDestroyed();
        }
    }

    /**
     * @param row Lookup row for exact match.
     * @return Found row.
     * @throws IgniteCheckedException If failed.
     */
    @Override public final T findOne(L row) throws IgniteCheckedException {
        return findOne(row, null, null);
    }

    /**
     * @param g Get.
     * @throws IgniteCheckedException If failed.
     */
    private void doFind(Get g) throws IgniteCheckedException {
        assert !sequentialWriteOptsEnabled;

        for (;;) { // Go down with retries.
            g.init();

            switch (findDown(g, g.rootId, 0L, g.rootLvl)) {
                case RETRY:
                case RETRY_ROOT:
                    checkDestroyed();
                    checkInterrupted();

                    continue;

                default:
                    return;
            }
        }
    }

    /**
     * @param g Get.
     * @param pageId Page ID.
     * @param fwdId Expected forward page ID.
     * @param lvl Level.
     * @return Result code.
     * @throws IgniteCheckedException If failed.
     */
    private Result findDown(final Get g, final long pageId, final long fwdId, final int lvl)
        throws IgniteCheckedException {
        long page = acquirePage(pageId);

        try {
            for (;;) {
                g.checkLockRetry();

                // Init args.
                g.pageId = pageId;
                g.fwdId = fwdId;

                Result res = read(pageId, page, search, g, lvl, RETRY);

                switch (res) {
                    case GO_DOWN:
                    case GO_DOWN_X:
                        assert g.pageId != pageId;
                        assert g.fwdId != fwdId || fwdId == 0;

                        // Go down recursively.
                        res = findDown(g, g.pageId, g.fwdId, lvl - 1);

                        switch (res) {
                            case RETRY:
                                checkInterrupted();

                                continue; // The child page got split, need to reread our page.

                            default:
                                return res;
                        }

                    case NOT_FOUND:
                        assert lvl == 0 : lvl;

                        g.row = null; // Mark not found result.

                        return res;

                    default:
                        return res;
                }
            }
        }
        finally {
            if (g.canRelease(pageId, lvl))
                releasePage(pageId, page);
        }
    }

    /**
     * @param instance Instance name.
     * @param type Tree type.
     * @return Tree name.
     */
    public static String treeName(String instance, String type) {
        return instance + "##" + type;
    }

    /**
     * For debug.
     *
     * @return Tree as {@link String}.
     * @throws IgniteCheckedException If failed.
     */
    @SuppressWarnings("unused")
    public final String printTree() throws IgniteCheckedException {
        long rootPageId;

        long metaPage = acquirePage(metaPageId);
        try {
            rootPageId = getFirstPageId(metaPageId, metaPage, -1);
        }
        finally {
            releasePage(metaPageId, metaPage);
        }

        return treePrinter.print(rootPageId);
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    public final void validateTree() throws IgniteCheckedException {
        long rootPageId;
        int rootLvl;

        long metaPage = acquirePage(metaPageId);
        try {
            rootLvl = getRootLevel();

            if (rootLvl < 0)
                fail("Root level: " + rootLvl);

            validateFirstPages(metaPageId, metaPage, rootLvl);

            rootPageId = getFirstPageId(metaPageId, metaPage, rootLvl);

            validateDownPages(rootPageId, 0L, rootLvl);

            validateDownKeys(rootPageId, null, rootLvl);
        }
        finally {
            releasePage(metaPageId, metaPage);
        }
    }

    /**
     * @param pageId Page ID.
     * @param minRow Minimum row.
     * @throws IgniteCheckedException If failed.
     */
    private void validateDownKeys(long pageId, L minRow, int lvl) throws IgniteCheckedException {
        long page = acquirePage(pageId);
        try {
            long pageAddr = readLock(pageId, page); // No correctness guaranties.

            try {
                BPlusIO<L> io = io(pageAddr);

                int cnt = io.getCount(pageAddr);

                if (cnt < 0)
                    fail("Negative count: " + cnt);

                if (io.isLeaf()) {
                    for (int i = 0; i < cnt; i++) {
                        if (minRow != null && compare(lvl, io, pageAddr, i, minRow) <= 0)
                            fail("Wrong sort order: " + U.hexLong(pageId) + " , at " + i + " , minRow: " + minRow);

                        minRow = io.getLookupRow(this, pageAddr, i);
                    }

                    return;
                }

                // To find our inner key we have to go left and then always go to the right.
                for (int i = 0; i < cnt; i++) {
                    L row = io.getLookupRow(this, pageAddr, i);

                    if (minRow != null && compare(lvl, io, pageAddr, i, minRow) <= 0)
                        fail("Min row violated: " + row + " , minRow: " + minRow);

                    long leftId = inner(io).getLeft(pageAddr, i);

                    L leafRow = getGreatestRowInSubTree(leftId);

                    int cmp = compare(lvl, io, pageAddr, i, leafRow);

                    if (cmp < 0 || (cmp != 0 && canGetRowFromInner))
                        fail("Wrong inner row: " + U.hexLong(pageId) + " , at: " + i + " , leaf:  " + leafRow +
                            " , inner: " + row);

                    validateDownKeys(leftId, minRow, lvl - 1);

                    minRow = row;
                }

                // Need to handle the rightmost child subtree separately or handle empty routing page.
                long rightId = inner(io).getLeft(pageAddr, cnt); // The same as getRight(cnt - 1)

                validateDownKeys(rightId, minRow, lvl - 1);
            }
            finally {
                readUnlock(pageId, page, pageAddr);
            }
        }
        finally {
            releasePage(pageId, page);
        }
    }

    /**
     * @param pageId Page ID.
     * @return Search row.
     * @throws IgniteCheckedException If failed.
     */
    private L getGreatestRowInSubTree(long pageId) throws IgniteCheckedException {
        long page = acquirePage(pageId);
        try {
            long pageAddr = readLock(pageId, page); // No correctness guaranties.

            try {
                BPlusIO<L> io = io(pageAddr);

                int cnt = io.getCount(pageAddr);

                if (io.isLeaf()) {
                    if (cnt <= 0) // This code is called only if the tree is not empty, so we can't see empty leaf.
                        fail("Invalid leaf count: " + cnt + " " + U.hexLong(pageId));

                    return io.getLookupRow(this, pageAddr, cnt - 1);
                }

                long rightId = inner(io).getLeft(pageAddr, cnt); // The same as getRight(cnt - 1), but good for routing pages.

                return getGreatestRowInSubTree(rightId);
            }
            finally {
                readUnlock(pageId, page, pageAddr);
            }
        }
        finally {
            releasePage(pageId, page);
        }
    }

    /**
     * @param metaId Meta page ID.
     * @param metaPage Meta page pointer.
     * @param rootLvl Root level.
     * @throws IgniteCheckedException If failed.
     */
    private void validateFirstPages(long metaId, long metaPage, int rootLvl) throws IgniteCheckedException {
        for (int lvl = rootLvl; lvl > 0; lvl--)
            validateFirstPage(metaId, metaPage, lvl);
    }

    /**
     * @param msg Message.
     */
    private void fail(Object msg) {
        AssertionError err = new AssertionError(msg);

        processFailure(FailureType.CRITICAL_ERROR, err);

        throw err;
    }

    /**
     * @param metaId Meta page ID.
     * @param metaPage Meta page pointer.
     * @param lvl Level.
     * @throws IgniteCheckedException If failed.
     */
    private void validateFirstPage(long metaId, long metaPage, int lvl) throws IgniteCheckedException {
        if (lvl == 0)
            fail("Leaf level: " + lvl);

        long pageId = getFirstPageId(metaId, metaPage, lvl);

        long leftmostChildId;

        long page = acquirePage(pageId);
        try {
            long pageAddr = readLock(pageId, page); // No correctness guaranties.

            try {
                BPlusIO<L> io = io(pageAddr);

                if (io.isLeaf())
                    fail("Leaf.");

                leftmostChildId = inner(io).getLeft(pageAddr, 0);
            }
            finally {
                readUnlock(pageId, page, pageAddr);
            }
        }
        finally {
            releasePage(pageId, page);
        }

        long firstDownPageId = getFirstPageId(metaId, metaPage, lvl - 1);

        if (firstDownPageId != leftmostChildId)
            fail(new SB("First: meta ").appendHex(firstDownPageId).a(", child ").appendHex(leftmostChildId));
    }

    /**
     * @param pageId Page ID.
     * @param fwdId Forward ID.
     * @param lvl Level.
     * @throws IgniteCheckedException If failed.
     */
    private void validateDownPages(long pageId, long fwdId, int lvl) throws IgniteCheckedException {
        long page = acquirePage(pageId);
        try {
            long pageAddr = readLock(pageId, page); // No correctness guaranties.

            try {
                long realPageId = BPlusIO.getPageId(pageAddr);

                if (realPageId != pageId)
                    fail(new SB("ABA on page ID: ref ").appendHex(pageId).a(", buf ").appendHex(realPageId));

                BPlusIO<L> io = io(pageAddr);

                if (io.isLeaf() != (lvl == 0)) // Leaf pages only at the level 0.
                    fail("Leaf level mismatch: " + lvl);

                long actualFwdId = io.getForward(pageAddr);

                if (actualFwdId != fwdId)
                    fail(new SB("Triangle: expected fwd ").appendHex(fwdId).a(", actual fwd ").appendHex(actualFwdId));

                int cnt = io.getCount(pageAddr);

                if (cnt < 0)
                    fail("Negative count: " + cnt);

                if (io.isLeaf()) {
                    if (cnt == 0 && getRootLevel() != 0)
                        fail("Empty leaf page.");
                }
                else {
                    // Recursively go down if we are on inner level.
                    for (int i = 0; i < cnt; i++)
                        validateDownPages(inner(io).getLeft(pageAddr, i), inner(io).getRight(pageAddr, i), lvl - 1);

                    if (fwdId != 0) {
                        // For the rightmost child ask neighbor.
                        long fwdId0 = fwdId;
                        long fwdPage = acquirePage(fwdId0);
                        try {
                            long fwdPageAddr = readLock(fwdId0, fwdPage); // No correctness guaranties.

                            try {
                                if (io(fwdPageAddr) != io)
                                    fail("IO on the same level must be the same");

                                fwdId = inner(io).getLeft(fwdPageAddr, 0);
                            }
                            finally {
                                readUnlock(fwdId0, fwdPage, fwdPageAddr);
                            }
                        }
                        finally {
                            releasePage(fwdId0, fwdPage);
                        }
                    }

                    long leftId = inner(io).getLeft(pageAddr, cnt); // The same as io.getRight(cnt - 1) but works for routing pages.

                    validateDownPages(leftId, fwdId, lvl - 1);
                }
            }
            finally {
                readUnlock(pageId, page, pageAddr);
            }
        }
        finally {
            releasePage(pageId, page);
        }
    }

    /**
     * @param io IO.
     * @param pageAddr Page address.
     * @param keys Keys.
     * @return String.
     * @throws IgniteCheckedException If failed.
     */
    private String printPage(BPlusIO<L> io, long pageAddr, boolean keys) throws IgniteCheckedException {
        StringBuilder b = new StringBuilder();

        b.append(formatPageId(PageIO.getPageId(pageAddr)));

        b.append(" [ ");
        b.append(io.isLeaf() ? "L " : "I ");

        int cnt = io.getCount(pageAddr);
        long fwdId = io.getForward(pageAddr);

        b.append("cnt=").append(cnt).append(' ');
        b.append("fwd=").append(formatPageId(fwdId)).append(' ');

        if (!io.isLeaf()) {
            b.append("lm=").append(formatPageId(inner(io).getLeft(pageAddr, 0))).append(' ');

            if (cnt > 0)
                b.append("rm=").append(formatPageId(inner(io).getRight(pageAddr, cnt - 1))).append(' ');
        }

        if (keys)
            b.append("keys=").append(printPageKeys(io, pageAddr)).append(' ');

        b.append(']');

        return b.toString();
    }

    /**
     * @param io IO.
     * @param pageAddr Page address.
     * @return Keys as String.
     * @throws IgniteCheckedException If failed.
     */
    private String printPageKeys(BPlusIO<L> io, long pageAddr) throws IgniteCheckedException {
        int cnt = io.getCount(pageAddr);

        StringBuilder b = new StringBuilder();

        b.append('[');

        for (int i = 0; i < cnt; i++) {
            if (i != 0)
                b.append(',');

            b.append(io.isLeaf() || canGetRowFromInner ? getRow(io, pageAddr, i) : io.getLookupRow(this, pageAddr, i));
        }

        b.append(']');

        return b.toString();
    }

    /**
     * @param x Long.
     * @return String.
     */
    private static String formatPageId(long x) {
        return U.hexLong(x);
    }

    /**
     * @param idx Index after binary search, which can be negative.
     * @return Always positive index.
     */
    private static int fix(int idx) {
        assert checkIndex(idx) : idx;

        if (idx < 0)
            idx = -idx - 1;

        return idx;
    }

    /**
     * @param idx Index.
     * @return {@code true} If correct.
     */
    private static boolean checkIndex(int idx) {
        return idx > -Short.MAX_VALUE && idx < Short.MAX_VALUE;
    }

    /**
     * Check if interrupted.
     * @throws IgniteInterruptedCheckedException If interrupted.
     */
    private static void checkInterrupted() throws IgniteInterruptedCheckedException {
        // We should not interrupt operations in the middle, because otherwise we'll end up in inconsistent state.
        // Because of that we do not check for Thread.interrupted()
        if (interrupted)
            throw new IgniteInterruptedCheckedException("Interrupted.");
    }

    /**
     * Interrupt all operations on all threads and all indexes.
     */
    @SuppressWarnings("unused")
    public static void interruptAll() {
        interrupted = true;
    }

    /**
     * @param row Lookup row.
     * @return Removed row.
     * @throws IgniteCheckedException If failed.
     */
    @Override public final T remove(L row) throws IgniteCheckedException {
        return doRemove(new Remove(row, true));
    }

    /**
     * @param row Lookup row.
     * @throws IgniteCheckedException If failed.
     * @return {@code True} if removed row.
     */
    public final boolean removex(L row) throws IgniteCheckedException {
        Boolean res = (Boolean)doRemove(new Remove(row, false));

        return res != null ? res : false;
    }

    /**
     * @param lower Lower bound (inclusive).
     * @param upper Upper bound (inclusive).
     * @param limit Limit of processed entries by single call, {@code 0} for no limit.
     * @return Removed rows.
     * @throws IgniteCheckedException If failed.
     */
    public List<L> remove(L lower, L upper, int limit) throws IgniteCheckedException {
        // We may not find the lower bound if the inner node
        // contain a key that is not present on the leaf page.
        assert canGetRowFromInner : "Not supported";
        assert limit >= 0 : limit;

        RemoveRange rmvOp = new RemoveRange(lower, upper, true, null, limit);

        doRemove(rmvOp);

        assert rmvOp.isDone();

        return Collections.unmodifiableList(rmvOp.removedRows);
    }

    /**
     * @param lower Lower bound (inclusive).
     * @param upper Upper bound (inclusive).
     * @param x Implementation specific argument.
     * @param limit Limit of processed entries by single call, {@code 0} or negative value for no limit.
     * @return {@code True} if removed at least one row.
     * @throws IgniteCheckedException If failed.
     */
    protected boolean removex(L lower, L upper, Object x, int limit) throws IgniteCheckedException {
        Boolean res = (Boolean)doRemove(new RemoveRange(lower, upper, false, x, limit));

        return res != null ? res : false;
    }

    /** {@inheritDoc} */
    @Override public void invoke(L row, Object z, InvokeClosure<T> c) throws IgniteCheckedException {
        checkDestroyed();

        Invoke x = new Invoke(row, z, c);

        try {
            for (;;) {
                x.init();

                Result res = invokeDown(x, x.rootId, 0L, 0L, x.rootLvl);

                switch (res) {
                    case RETRY:
                    case RETRY_ROOT:
                        checkInterrupted();

                        continue;

                    default:
                        if (!x.isFinished()) {
                            res = x.tryFinish();

                            if (res == RETRY || res == RETRY_ROOT) {
                                checkInterrupted();

                                continue;
                            }

                            assert x.isFinished() : res;
                        }

                        return;
                }
            }
        }
        catch (UnregisteredClassException | UnregisteredBinaryTypeException | CorruptedDataStructureException e) {
            throw e;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteCheckedException("Runtime failure on search row: " + row, e);
        }
        catch (RuntimeException | AssertionError e) {
            throw corruptedTreeException("Runtime failure on search row: " + row, e, grpId, x.pageId);
        }
        finally {
            x.releaseAll();
            checkDestroyed();
        }
    }

    /**
     * @param x Invoke operation.
     * @param pageId Page ID.
     * @param backId Expected backward page ID if we are going to the right.
     * @param fwdId Expected forward page ID.
     * @param lvl Level.
     * @return Result code.
     * @throws IgniteCheckedException If failed.
     */
    private Result invokeDown(final Invoke x, final long pageId, final long backId, final long fwdId, final int lvl)
        throws IgniteCheckedException {
        assert lvl >= 0 : lvl;

        if (x.isTail(pageId, lvl))
            return FOUND; // We've already locked this page, so return that we are ok.

        long page = acquirePage(pageId);

        try {
            Result res = RETRY;

            for (;;) {
                if (res == RETRY)
                    x.checkLockRetry();

                // Init args.
                x.pageId(pageId);
                x.fwdId(fwdId);
                x.backId(backId);

                res = read(pageId, page, search, x, lvl, RETRY);

                switch (res) {
                    case GO_DOWN_X:
                        assert backId != 0;
                        assert x.backId == 0; // We did not setup it yet.

                        x.backId(pageId); // Dirty hack to setup a check inside of askNeighbor.

                        // We need to get backId here for our child page, it must be the last child of our back.
                        res = askNeighbor(backId, x, true);

                        if (res != FOUND)
                            return res; // Retry.

                        assert x.backId != pageId; // It must be updated in askNeighbor.

                        // Intentional fallthrough.
                    case GO_DOWN:
                        // Go down recursively.
                        res = invokeDown(x, x.pageId, x.backId, x.fwdId, lvl - 1);

                        if (res == RETRY_ROOT || x.isFinished())
                            return res;

                        if (res == RETRY) {
                            checkInterrupted();

                            continue;
                        }

                        assert x.op != null; // Guarded by isFinished.

                        res = x.op.finishOrLockTail(pageId, page, backId, fwdId, lvl);

                        return res;

                    case NOT_FOUND:
                        if (lvl == 0)
                            x.invokeClosure();

                        // Level must be equal to bottom level. This is the place when we would insert values into
                        // parent nodes during splits.
                        assert lvl == (x.isPut() ? ((Put)x.op).btmLvl : 0)
                            : "NOT_FOUND on the wrong level  [lvl=" + lvl + ", x=" + x
                            + ", btmLvl=" + (x.isPut() ? ((Put)x.op).btmLvl : 0) + ']';

                        return x.onNotFound(pageId, page, fwdId, lvl);

                    case FOUND:
                        // Item can only be found in the leaf page.
                        assert lvl == 0 : "Invoke found an item in an inner node instead of going down: lvl=" + lvl;

                        x.invokeClosure();

                        return x.onFound(pageId, page, backId, fwdId, lvl);

                    default:
                        return res;
                }
            }
        }
        finally {
            x.levelExit();

            if (x.canRelease(pageId, lvl))
                releasePage(pageId, page);
        }
    }

    /**
     * @return r Remove operation.
     * @throws IgniteCheckedException If failed.
     */
    private T doRemove(Remove r) throws IgniteCheckedException {
        assert !sequentialWriteOptsEnabled;

        L row = r.row;

        checkDestroyed();

        try {
            for (;;) {
                r.init();

                Result res = removeDown(r, r.rootId, 0L, 0L, r.rootLvl);

                switch (res) {
                    case RETRY:
                    case RETRY_ROOT:
                        checkInterrupted();

                        continue;

                    default:
                        if (!r.isFinished()) {
                            res = r.finishTail();

                            // If not found, then the tree grew beyond our call stack -> retry from the actual root.
                            if (res == RETRY || res == NOT_FOUND) {
                                assert r.checkTailLevel(getRootLevel()) : "tail=" + r.tail + ", res=" + res;

                                checkInterrupted();

                                continue;
                            }

                            assert res == FOUND : res;
                        }

                        assert r.isFinished();

                        return r.rmvd;
                }
            }
        }
        catch (CorruptedDataStructureException e) {
            throw e;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteCheckedException("Runtime failure on search row: " + row, e);
        }
        catch (RuntimeException | AssertionError e) {
            throw corruptedTreeException("Runtime failure on search row: " + row, e, grpId, r.pageId);
        }
        finally {
            r.releaseAll();
            checkDestroyed();
        }
    }

    /**
     * @param r Remove operation.
     * @param pageId Page ID.
     * @param backId Expected backward page ID if we are going to the right.
     * @param fwdId Expected forward page ID.
     * @param lvl Level.
     * @return Result code.
     * @throws IgniteCheckedException If failed.
     */
    private Result removeDown(final Remove r, final long pageId, final long backId, final long fwdId, final int lvl)
        throws IgniteCheckedException {
        assert lvl >= 0 : lvl;

        if (r.isTail(pageId, lvl))
            return FOUND; // We've already locked this page, so return that we are ok.

        long page = acquirePage(pageId);

        try {
            for (;;) {
                r.checkLockRetry();

                // Init args.
                r.pageId = pageId;
                r.fwdId = fwdId;
                r.backId = backId;

                Result res = read(pageId, page, search, r, lvl, RETRY);

                switch (res) {
                    case GO_DOWN_X:
                        assert backId != 0;
                        assert r.backId == 0; // We did not setup it yet.

                        r.backId = pageId; // Dirty hack to setup a check inside of askNeighbor.

                        // We need to get backId here for our child page, it must be the last child of our back.
                        res = askNeighbor(backId, r, true);

                        if (res != FOUND)
                            return res; // Retry.

                        assert r.backId != pageId; // It must be updated in askNeighbor.

                        // Intentional fallthrough.
                    case GO_DOWN:
                        res = removeDown(r, r.pageId, r.backId, r.fwdId, lvl - 1);

                        if (res == RETRY) {
                            checkInterrupted();

                            continue;
                        }

                        if (res == RETRY_ROOT || r.isFinished())
                            return res;

                        res = r.finishOrLockTail(pageId, page, backId, fwdId, lvl);

                        return res;

                    case NOT_FOUND:
                        // We are at the bottom.
                        assert lvl == 0 : lvl;

                        if (!r.ceil())
                            return r.finish(res);

                        // Intentional fallthrough to remove something from this page.

                    case FOUND:
                        return r.tryRemoveFromLeaf(pageId, page, backId, fwdId, lvl);

                    default:
                        return res;
                }
            }
        }
        finally {
            r.page = 0L;

            if (r.canRelease(pageId, lvl))
                releasePage(pageId, page);
        }
    }

    /**
     * @param cnt Count.
     * @param cap Capacity.
     * @return {@code true} If may merge.
     */
    private boolean mayMerge(int cnt, int cap) {
        int minCnt = (int)(minFill * cap);

        if (cnt <= minCnt) {
            assert cnt == 0; // TODO remove

            return true;
        }

        assert cnt > 0;

        int maxCnt = (int)(maxFill * cap);

        if (cnt > maxCnt)
            return false;

        assert false; // TODO remove

        // Randomization is for smoothing worst case scenarios. Probability of merge attempt
        // is proportional to free space in our page (discounted on fill factor).
        return randomInt(maxCnt - minCnt) >= cnt - minCnt;
    }

    /**
     * @return Root level.
     * @throws IgniteCheckedException If failed.
     */
    public final int rootLevel() throws IgniteCheckedException {
        checkDestroyed();

        return getRootLevel();
    }

    /**
     * @return {@code True} in case the tree is empty.
     * @throws IgniteCheckedException If failed.
     */
    public final boolean isEmpty() throws IgniteCheckedException {
        checkDestroyed();

        for (;;) {
            TreeMetaData treeMeta = treeMeta();

            long rootId, rootPage = acquirePage(rootId = treeMeta.rootId);

            try {
                long rootAddr = readLock(rootId, rootPage);

                if (rootAddr == 0) {
                    checkDestroyed();

                    continue;
                }

                try {
                    BPlusIO<L> io = io(rootAddr);

                    return io.getCount(rootAddr) == 0;
                }
                finally {
                    readUnlock(rootId, rootPage, rootAddr);
                }
            }
            finally {
                releasePage(rootId, rootPage);
            }
        }
    }

    /**
     * Returns number of elements in the tree by scanning pages of the bottom (leaf) level.
     * Since a concurrent access is permitted, there is no guarantee about
     * momentary consistency: the method may miss updates made in already scanned pages.
     *
     * @return Number of elements in the tree.
     * @throws IgniteCheckedException If failed.
     */
    @Override public final long size() throws IgniteCheckedException {
        return size(null);
    }

    /**
     * Returns number of elements in the tree that match the filter by scanning through the pages of the leaf level.
     * Since a concurrent access to the tree is permitted, there is no guarantee about
     * momentary consistency: the method may not see updates made in already scanned pages.
     *
     * @param filter The filter to use or null to count all elements.
     * @return Number of either all elements in the tree or the elements that match the filter.
     * @throws IgniteCheckedException If failed.
     */
    public long size(@Nullable TreeRowClosure<L, T> filter) throws IgniteCheckedException {
        checkDestroyed();

        for (;;) {
            long curPageId;

            long metaPage = acquirePage(metaPageId);

            try {
                curPageId = getFirstPageId(metaPageId, metaPage, 0); // Level 0 is always at the bottom.
            }
            finally {
                releasePage(metaPageId, metaPage);
            }

            long cnt = 0;

            long curPage = acquirePage(curPageId);

            try {
                long curPageAddr = readLock(curPageId, curPage);

                if (curPageAddr == 0)
                    continue; // The first page has gone: restart scan.

                try {
                    BPlusIO<L> io = io(curPageAddr);

                    assert io.isLeaf();

                    for (;;) {
                        int curPageSize = io.getCount(curPageAddr);

                        if (filter == null)
                            cnt += curPageSize;
                        else {
                            for (int i = 0; i < curPageSize; ++i) {
                                if (filter.apply(this, io, curPageAddr, i))
                                    cnt++;
                            }
                        }

                        long nextPageId = io.getForward(curPageAddr);

                        if (nextPageId == 0) {
                            checkDestroyed();

                            return cnt;
                        }

                        long nextPage = acquirePage(nextPageId);

                        try {
                            long nextPageAddr = readLock(nextPageId, nextPage);

                            // In the current implementation the next page can't change when the current page is locked.
                            assert nextPageAddr != 0 : nextPageAddr;

                            try {
                                long pa = curPageAddr;
                                curPageAddr = 0; // Set to zero to avoid double unlocking in finalizer.

                                readUnlock(curPageId, curPage, pa);

                                long p = curPage;
                                curPage = 0; // Set to zero to avoid double release in finalizer.

                                releasePage(curPageId, p);

                                curPageId = nextPageId;
                                curPage = nextPage;
                                curPageAddr = nextPageAddr;

                                nextPage = 0;
                                nextPageAddr = 0;
                            }
                            finally {
                                if (nextPageAddr != 0)
                                    readUnlock(nextPageId, nextPage, nextPageAddr);
                            }
                        }
                        finally {
                            if (nextPage != 0)
                                releasePage(nextPageId, nextPage);
                        }
                    }
                }
                finally {
                    if (curPageAddr != 0)
                        readUnlock(curPageId, curPage, curPageAddr);
                }
            }
            finally {
                if (curPage != 0)
                    releasePage(curPageId, curPage);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override public final T put(T row) throws IgniteCheckedException {
        return doPut(row, true);
    }

    /**
     * @param row New value.
     * @throws IgniteCheckedException If failed.
     * @return {@code True} if replaced existing row.
     */
    public boolean putx(T row) throws IgniteCheckedException {
        Boolean res = (Boolean)doPut(row, false);

        return res != null ? res : false;
    }

    /**
     * @param row New value.
     * @param needOld {@code True} If need return old value.
     * @return Old row.
     * @throws IgniteCheckedException If failed.
     */
    private T doPut(T row, boolean needOld) throws IgniteCheckedException {
        checkDestroyed();

        Put p = new Put(row, needOld);

        try {
            for (;;) { // Go down with retries.
                p.init();

                Result res = putDown(p, p.rootId, 0L, p.rootLvl);

                switch (res) {
                    case RETRY:
                    case RETRY_ROOT:
                        checkInterrupted();

                        continue;

                    case FOUND:
                        // We may need to perform an inner replace on the upper level.
                        if (!p.isFinished()) {
                            res = p.finishTail();

                            // If not found, then the root split has happened and operation should be retried from the actual root.
                            if (res == RETRY || res == NOT_FOUND) {
                                p.releaseTail();

                                assert p.checkTailLevel(getRootLevel()) : "tail=" + p.tail + ", res=" + res;

                                checkInterrupted();

                                continue;
                            }
                        }

                        return p.oldRow;

                    default:
                        throw new IllegalStateException("Result: " + res);
                }
            }
        }
        catch (CorruptedDataStructureException e) {
            throw e;
        }
        catch (IgniteCheckedException e) {
            throw new IgniteCheckedException("Runtime failure on row: " + row, e);
        }
        catch (RuntimeException | AssertionError e) {
            throw corruptedTreeException("Runtime failure on row: " + row, e, grpId, p.pageId);
        }
        finally {
            checkDestroyed();
        }
    }

    /**
     * Releases the lock that is held by long tree destroy process for a short period of time and acquires it again,
     * allowing other processes to acquire it.
     */
    protected void temporaryReleaseLock() {
        // No-op.
    }

    /**
     * Releases the lock that is held by long tree destroy process for a short period of time and acquires it again,
     * allowing other processes to acquire it.
     * @param lockedPages Deque of locked pages. {@link GridTuple3} contains page id, page pointer and page address.
     * Pages are ordered in that order as they were locked by destroy method. We unlock them in reverse order and
     * unlock in direct order.
     */
    private void temporaryReleaseLock(Deque<GridTuple3<Long, Long, Long>> lockedPages) {
        lockedPages.iterator().forEachRemaining(t -> writeUnlock(t.get1(), t.get2(), t.get3(), true));

        temporaryReleaseLock();

        lockedPages.descendingIterator().forEachRemaining(t -> writeLock(t.get1(), t.get2()));
    }

    /**
     * Maximum time for which tree destroy process is allowed to hold the lock, after this time exceeds,
     * {@link BPlusTree#temporaryReleaseLock()} is called and hold time is reset.
     *
     * @return Time, in milliseconds.
     */
    protected long maxLockHoldTime() {
        return Long.MAX_VALUE;
    }

    /**
     * Destroys tree. This method is allowed to be invoked only when the tree is out of use (no concurrent operations
     * are trying to read or update the tree after destroy beginning).
     *
     * @return Number of pages recycled from this tree. If the tree was destroyed by someone else concurrently returns
     *     {@code 0}, otherwise it should return at least {@code 2} (for meta page and root page), unless this tree is
     *     used as metadata storage, or {@code -1} if we don't have a reuse list and did not do recycling at all.
     * @throws IgniteCheckedException If failed.
     */
    public final long destroy() throws IgniteCheckedException {
        return destroy(null, false);
    }

    /**
     * Destroys tree. This method is allowed to be invoked only when the tree is out of use (no concurrent operations
     * are trying to read or update the tree after destroy beginning).
     *
     * @param c Visitor closure. Visits only leaf pages.
     * @param forceDestroy Whether to proceed with destroying, even if tree is already marked as destroyed (see
     * {@link #markDestroyed()}).
     * @return Number of pages recycled from this tree. If the tree was destroyed by someone else concurrently returns
     *     {@code 0}, otherwise it should return at least {@code 2} (for meta page and root page), unless this tree is
     *     used as metadata storage, or {@code -1} if we don't have a reuse list and did not do recycling at all.
     * @throws IgniteCheckedException If failed.
     */
    public final long destroy(@Nullable IgniteInClosure<L> c, boolean forceDestroy) throws IgniteCheckedException {
        close();

        if (!markDestroyed() && !forceDestroy)
            return 0;

        if (reuseList == null)
            return -1;

        LongListReuseBag bag = new LongListReuseBag();

        long pagesCnt = 0;

        AtomicLong lockHoldStartTime = new AtomicLong(U.currentTimeMillis());

        Deque<GridTuple3<Long, Long, Long>> lockedPages = new LinkedList<>();

        final long lockMaxTime = maxLockHoldTime();

        long metaPage = acquirePage(metaPageId);

        try {
            long metaPageAddr = writeLock(metaPageId, metaPage); // No checks, we must be out of use.

            lockedPages.push(new GridTuple3<>(metaPageId, metaPage, metaPageAddr));

            try {
                assert metaPageAddr != 0L;

                int rootLvl = getRootLevel(metaPageAddr);

                if (rootLvl < 0)
                    fail("Root level: " + rootLvl);

                long rootPageId = getFirstPageId(metaPageId, metaPage, rootLvl, metaPageAddr);

                pagesCnt += destroyDownPages(bag, rootPageId, rootLvl, c, lockHoldStartTime, lockMaxTime, lockedPages);

                bag.addFreePage(recyclePage(metaPageId, metaPage, metaPageAddr, null));

                pagesCnt++;
            }
            finally {
                writeUnlock(metaPageId, metaPage, metaPageAddr, true);

                lockedPages.pop();
            }
        }
        finally {
            releasePage(metaPageId, metaPage);
        }

        reuseList.addForRecycle(bag);

        assert bag.isEmpty() : bag.size();

        return pagesCnt;
    }

    /**
     * Recursively destroys tree pages. Should be initially called with id of root page as {@code pageId}
     * and root level as {@code lvl}.
     *
     * @param bag Reuse bag.
     * @param pageId Page id.
     * @param lvl Current level of tree.
     * @param c Visitor closure. Visits only leaf pages.
     * @param lockHoldStartTime When lock has been aquired last time.
     * @param lockMaxTime Maximum time to hold the lock.
     * @param lockedPages Deque of locked pages. Is used to release write-locked pages when temporary releasing
     * checkpoint read lock.
     * @return Count of destroyed pages.
     * @throws IgniteCheckedException If failed.
     */
    protected long destroyDownPages(
        LongListReuseBag bag,
        long pageId,
        int lvl,
        @Nullable IgniteInClosure<L> c,
        AtomicLong lockHoldStartTime,
        long lockMaxTime,
        Deque<GridTuple3<Long, Long, Long>> lockedPages
    ) throws IgniteCheckedException {
        if (pageId == 0)
            return 0;

        long pagesCnt = 0;

        long page = acquirePage(pageId);

        try {
            long pageAddr = writeLock(pageId, page);

            if (pageAddr == 0L)
                return 0; // This page was possibly recycled, but we still need to destroy the rest of the tree.

            lockedPages.push(new GridTuple3<>(pageId, page, pageAddr));

            try {
                BPlusIO<L> io = io(pageAddr);

                if (io.isLeaf() != (lvl == 0)) // Leaf pages only at the level 0.
                    fail("Leaf level mismatch: " + lvl);

                int cnt = io.getCount(pageAddr);

                if (cnt < 0)
                    fail("Negative count: " + cnt);

                if (!io.isLeaf()) {
                    // Recursively go down if we are on inner level.
                    // When i == cnt it is the same as io.getRight(cnt - 1) but works for routing pages.
                    for (int i = 0; i <= cnt; i++) {
                        long leftId = inner(io).getLeft(pageAddr, i);

                        inner(io).setLeft(pageAddr, i, 0);

                        pagesCnt += destroyDownPages(
                            bag,
                            leftId,
                            lvl - 1,
                            c,
                            lockHoldStartTime,
                            lockMaxTime,
                            lockedPages
                        );
                    }
                }

                if (c != null && io.isLeaf())
                    io.visit(pageAddr, c);

                bag.addFreePage(recyclePage(pageId, page, pageAddr, null));

                pagesCnt++;
            }
            finally {
                writeUnlock(pageId, page, pageAddr, true);

                lockedPages.pop();
            }

            if (U.currentTimeMillis() - lockHoldStartTime.get() > lockMaxTime) {
                temporaryReleaseLock(lockedPages);

                lockHoldStartTime.set(U.currentTimeMillis());
            }
        }
        finally {
            releasePage(pageId, page);
        }

        if (bag.size() == 128) {
            reuseList.addForRecycle(bag);

            assert bag.isEmpty() : bag.size();
        }

        return pagesCnt;
    }

    /**
     * @return {@code True} if state was changed.
     */
    public boolean markDestroyed() {
        return destroyed.compareAndSet(false, true);
    }

    /**
     * @return {@code True} if marked as destroyed.
     */
    public boolean destroyed() {
        return destroyed.get();
    }

    /**
     * @param pageAddr Meta page address.
     * @return First page IDs.
     */
    protected Iterable<Long> getFirstPageIds(long pageAddr) {
        List<Long> res = new ArrayList<>();

        BPlusMetaIO mio = BPlusMetaIO.VERSIONS.forPage(pageAddr);

        for (int lvl = mio.getRootLevel(pageAddr); lvl >= 0; lvl--)
            res.add(mio.getFirstPageId(pageAddr, lvl));

        return res;
    }

    /**
     * @param pageId Page ID.
     * @param page Page pointer.
     * @param pageAddr Page address
     * @param io IO.
     * @param fwdId Forward page ID.
     * @param fwdBuf Forward buffer.
     * @param idx Insertion index.
     * @return {@code true} The middle index was shifted to the right.
     * @throws IgniteCheckedException If failed.
     */
    private boolean splitPage(
        long pageId, long page, long pageAddr, BPlusIO io, long fwdId, long fwdBuf, int idx
    ) throws IgniteCheckedException {
        int cnt = io.getCount(pageAddr);

        int mid = sequentialWriteOptsEnabled ? (int)(cnt * 0.85) : cnt >>> 1;

        boolean res = false;

        if (idx > mid) { // If insertion is going to be to the forward page, keep more in the back page.
            mid++;

            res = true;
        }

        // Update forward page.
        io.splitForwardPage(pageAddr, fwdId, fwdBuf, mid, cnt, pageSize(), metrics);

        // Update existing page.
        io.splitExistingPage(pageAddr, mid, fwdId);

        if (needWalDeltaRecord(pageId, page, null))
            wal.log(new SplitExistingPageRecord(grpId, pageId, mid, fwdId));

        return res;
    }

    /**
     * @param pageId Page ID.
     * @param page Page pointer.
     * @param pageAddr Page address
     * @param walPlc Full page WAL record policy.
     */
    private void writeUnlockAndClose(long pageId, long page, long pageAddr, Boolean walPlc) {
        try {
            writeUnlock(pageId, page, pageAddr, walPlc, true);
        }
        finally {
            releasePage(pageId, page);
        }
    }

    /**
     * @param pageId Inner page ID.
     * @param g Get.
     * @param back Get back (if {@code true}) or forward page (if {@code false}).
     * @return Operation result.
     * @throws IgniteCheckedException If failed.
     */
    private Result askNeighbor(long pageId, Get g, boolean back) throws IgniteCheckedException {
        return read(pageId, askNeighbor, g, back ? TRUE.ordinal() : FALSE.ordinal(), RETRY);
    }

    /**
     * @param p Put.
     * @param pageId Page ID.
     * @param fwdId Expected forward page ID.
     * @param lvl Level.
     * @return Result code.
     * @throws IgniteCheckedException If failed.
     */
    private Result putDown(final Put p, final long pageId, final long fwdId, int lvl)
        throws IgniteCheckedException {
        assert lvl >= 0 : lvl;

        final long page = acquirePage(pageId);

        try {
            for (;;) {
                p.checkLockRetry();

                // Init args.
                p.pageId = pageId;
                p.fwdId = fwdId;

                Result res = read(pageId, page, search, p, lvl, RETRY);

                switch (res) {
                    case GO_DOWN:
                    case GO_DOWN_X:
                        assert lvl > 0 : lvl;
                        assert p.pageId != pageId;
                        assert p.fwdId != fwdId || fwdId == 0;

                        // Go down recursively.
                        res = putDown(p, p.pageId, p.fwdId, lvl - 1);

                        if (res == RETRY_ROOT || p.isFinished())
                            return res;

                        if (res == RETRY) {
                            checkInterrupted();

                            continue;
                        }

                        // We have to either insert split row to this level,
                        // perform inner replace, lock the tail or retry.
                        res = p.finishOrLockTail(pageId, page, 0L, fwdId, lvl);

                        return res;

                    case FOUND: // Do replace.
                        assert lvl == 0 : "This replace can happen only at the bottom level.";

                        return p.tryReplace(pageId, page, fwdId, lvl);

                    case NOT_FOUND: // Do insert.
                        assert lvl == p.btmLvl : "must insert at the bottom level";

                        return p.tryInsert(pageId, page, fwdId, lvl);

                    default:
                        return res;
                }
            }
        }
        finally {
            if (p.canRelease(pageId, lvl))
                releasePage(pageId, page);
        }
    }

    /**
     * @param io IO.
     * @param pageAddr Page address.
     * @param back Backward page.
     * @return Page ID.
     */
    private long doAskNeighbor(BPlusIO<L> io, long pageAddr, boolean back) {
        long res;

        if (back) {
            // Count can be 0 here if it is a routing page, in this case we have a single child.
            int cnt = io.getCount(pageAddr);

            // We need to do get the rightmost child: io.getRight(cnt - 1),
            // here io.getLeft(cnt) is the same, but handles negative index if count is 0.
            res = inner(io).getLeft(pageAddr, cnt);
        }
        else // Leftmost child.
            res = inner(io).getLeft(pageAddr, 0);

        assert res != 0 : "inner page with no route down: " + U.hexLong(PageIO.getPageId(pageAddr));

        return res;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(BPlusTree.class, this);
    }

    /**
     * Get operation.
     */
    public abstract class Get {
        /** */
        long rmvId;

        /** Starting point root level. May be outdated. Must be modified only in {@link Get#init()}. */
        int rootLvl;

        /** Starting point root ID. May be outdated. Must be modified only in {@link Get#init()}. */
        long rootId;

        /** */
        L row;

        /** In/Out parameter: Page ID. */
        long pageId;

        /** In/Out parameter: expected forward page ID. */
        long fwdId;

        /** In/Out parameter: in case of right turn this field will contain backward page ID for the child. */
        long backId;

        /** */
        int shift;

        /** If this operation is a part of invoke. */
        Invoke invoke;

        /** Ignore row passed, find last row */
        boolean findLast;

        /** Number of repetitions to capture a lock in the B+Tree (countdown). */
        int lockRetriesCnt = getLockRetries();

        /**
         * @param row Row.
         * @param findLast find last row.
         */
        Get(L row, boolean findLast) {
            assert findLast ^ row != null;

            this.row = row;
            this.findLast = findLast;
        }

        /**
         * @param g Other operation to copy from.
         */
        final void copyFrom(Get g) {
            rmvId = g.rmvId;
            rootLvl = g.rootLvl;
            pageId = g.pageId;
            fwdId = g.fwdId;
            backId = g.backId;
            shift = g.shift;
            findLast = g.findLast;
        }

        /**
         * Initialize operation.
         *
         * @throws IgniteCheckedException If failed.
         */
        final void init() throws IgniteCheckedException {
            TreeMetaData meta0 = treeMeta();

            assert meta0 != null;

            restartFromRoot(meta0.rootId, meta0.rootLvl, globalRmvId.get());
        }

        /**
         * @param rootId Root page ID.
         * @param rootLvl Root level.
         * @param rmvId Remove ID to be afraid of.
         */
        void restartFromRoot(long rootId, int rootLvl, long rmvId) {
            this.rootId = rootId;
            this.rootLvl = rootLvl;
            this.rmvId = rmvId;
        }

        /**
         * @param io IO.
         * @param pageAddr Page address.
         * @param idx Index of found entry.
         * @param lvl Level.
         * @return {@code true} If we need to stop.
         * @throws IgniteCheckedException If failed.
         */
        boolean found(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            assert lvl >= 0;

            return lvl == 0; // Stop if we are at the bottom.
        }

        /**
         * @param io IO.
         * @param pageAddr Page address.
         * @param idx Insertion point.
         * @param lvl Level.
         * @return {@code true} If we need to stop.
         * @throws IgniteCheckedException If failed.
         */
        boolean notFound(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            assert lvl >= 0;

            return lvl == 0; // Stop if we are at the bottom.
        }

        /**
         * @param pageId Page.
         * @param lvl Level.
         * @return {@code true} If we can release the given page.
         */
        public boolean canRelease(long pageId, int lvl) {
            return pageId != 0L;
        }

        /**
         * @param backId Back page ID.
         */
        void backId(long backId) {
            this.backId = backId;
        }

        /**
         * @param pageId Page ID.
         */
        void pageId(long pageId) {
            this.pageId = pageId;
        }

        /**
         * @param fwdId Forward page ID.
         */
        void fwdId(long fwdId) {
            this.fwdId = fwdId;
        }

        /**
         * @return {@code true} If the operation is finished.
         */
        boolean isFinished() {
            throw new IllegalStateException();
        }

        /**
         * @throws IgniteCheckedException If the operation can not be retried.
         */
        void checkLockRetry() throws IgniteCheckedException {
            if (lockRetriesCnt == 0) {
                String errMsg = lockRetryErrorMessage(getClass().getSimpleName());

                IgniteCheckedException e = new IgniteCheckedException(errMsg);

                processFailure(FailureType.CRITICAL_ERROR, e);

                throw e;
            }

            lockRetriesCnt--;
        }

        /**
         * @return Operation row.
         */
        public L row() {
            return row;
        }
    }

    /**
     * Get a single entry.
     */
    private final class GetOne extends Get {
        /** */
        Object x;

        /** */
        TreeRowClosure<L, T> c;

        /**
         * @param row Row.
         * @param c Closure filter.
         * @param x Implementation specific argument.
         * @param findLast Ignore row passed, find last row
         */
        private GetOne(L row, TreeRowClosure<L, T> c, Object x, boolean findLast) {
            super(row, findLast);

            this.x = x;
            this.c = c;
        }

        /** {@inheritDoc} */
        @Override boolean found(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            // Check if we are on an inner page and can't get row from it.
            if (lvl != 0 && !canGetRowFromInner)
                return false;

            row = c == null || c.apply(BPlusTree.this, io, pageAddr, idx) ? getRow(io, pageAddr, idx, x) : null;

            return true;
        }
    }

    /**
     * Get a cursor for range.
     */
    private final class GetCursor extends Get {
        /** */
        AbstractForwardCursor cursor;

        /**
         * @param lower Lower bound.
         * @param shift Shift.
         * @param cursor Cursor.
         */
        GetCursor(L lower, int shift, AbstractForwardCursor cursor) {
            super(lower, false);

            assert shift != 0; // Either handle range of equal rows or find a greater row after concurrent merge.

            this.shift = shift;
            this.cursor = cursor;
        }

        /** {@inheritDoc} */
        @Override boolean found(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            throw new IllegalStateException(); // Must never be called because we always have a shift.
        }

        /** {@inheritDoc} */
        @Override boolean notFound(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            if (lvl != 0)
                return false;

            cursor.init(pageAddr, io, idx);

            return true;
        }
    }

    /**
     * Get the last item in the tree which matches the passed filter.
     */
    private final class GetLast extends Get {
        /** */
        private final TreeRowClosure<L, T> c;

        /** */
        private boolean retry = true;

        /** */
        private long lastPageId;

        /** */
        private T row0;

        /**
         * @param c Filter closure.
         */
        public GetLast(TreeRowClosure<L, T> c) {
            super(null, true);

            assert c != null;

            this.c = c;
        }

        /** {@inheritDoc} */
        @Override boolean found(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            if (lvl != 0)
                return false;

            for (int i = idx; i >= 0; i--) {
                if (c.apply(BPlusTree.this, io, pageAddr, i)) {
                    retry = false;
                    row0 = getRow(io, pageAddr, i);

                    return true;
                }
            }

            if (pageId == rootId)
                retry = false; // We are at the root page, there are no other leafs.

            if (retry) {
                findLast = false;

                // Restart from an item before the first item in the leaf (last item on the previous leaf).
                row0 = getRow(io, pageAddr, 0);
                shift = -1;

                lastPageId = pageId; // Track leafs to detect a loop over the first leaf in the tree.
            }

            return true;
        }

        /** {@inheritDoc} */
        @Override boolean notFound(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            if (lvl != 0)
                return false;

            if (io.getCount(pageAddr) == 0) {
                // it's an empty tree
                retry = false;

                return true;
            }

            if (idx == 0 && lastPageId == pageId) {
                // not found
                retry = false;
                row0 = null;

                return true;
            }
            else {
                for (int i = idx; i >= 0; i--) {
                    if (c.apply(BPlusTree.this, io, pageAddr, i)) {
                        retry = false;
                        row0 = getRow(io, pageAddr, i);

                        break;
                    }
                }
            }

            if (retry) {
                // Restart from an item before the first item in the leaf (last item on the previous leaf).
                row0 = getRow(io, pageAddr, 0);

                lastPageId = pageId; // Track leafs to detect a loop over the first leaf in the tree.
            }

            return true;
        }

        /**
         * @return Last item in the tree.
         * @throws IgniteCheckedException If failure.
         */
        public T find() throws IgniteCheckedException {
            while (retry) {
                row = row0;

                doFind(this);
            }

            return row0;
        }
    }

    /**
     * Put operation.
     */
    public final class Put extends Update {
        /** Right child page ID for split row. */
        long rightId;

        /** Replaced row if any. */
        T oldRow;

        /**
         * Bottom level for insertion (insert can't go deeper). Will be incremented on split on each level.
         */
        short btmLvl;

        /** */
        final boolean needOld;

        /**
         * @param row Row.
         * @param needOld {@code True} If need return old value.
         */
        private Put(T row, boolean needOld) {
            super(row);

            this.needOld = needOld;
        }

        /** {@inheritDoc} */
        @Override boolean notFound(BPlusIO<L> io, long pageAddr, int idx, int lvl) {
            assert btmLvl >= 0 : btmLvl;
            assert lvl >= btmLvl : lvl;

            return lvl == btmLvl;
        }

        /** {@inheritDoc} */
        @Override protected Result finishOrLockTail(long pageId, long page, long backId, long fwdId, int lvl)
            throws IgniteCheckedException {
            if (btmLvl == lvl) {
                // Insert for the split.
                return tryInsert(pageId, page, fwdId, lvl);
            }

            // Finish inner replace.
            Result res = finishTail();

            // Add this page to the tail if inner replace has not happened.
            if (res == NOT_FOUND) {
                // Set forward id to check the triangle invariant under the write-lock.
                fwdId(fwdId);

                res = write(pageId, page, lockTailExact, this, lvl, RETRY, statisticsHolder());
            }

            // Release tail if retry is required.
            if (res == RETRY)
                releaseTail();

            return res;
        }

        /** {@inheritDoc} */
        @Override protected Result finishTail() throws IgniteCheckedException {
            // An inner node is required for replacement.
            if (tail.lvl == 0)
                return NOT_FOUND;

            int idx = insertionPoint(tail);

            // Missing row means that current page must be added to tail.
            if (idx < 0) {
                idx = fix(idx);

                BPlusInnerIO<L> io = (BPlusInnerIO<L>)tail.io;

                // Release tail in case of broken triangle invariant in locked pages.
                if (io.getLeft(tail.buf, idx) != tail.down.pageId) {
                    releaseTail();

                    return RETRY;
                }

                return NOT_FOUND;
            }

            assert oldRow == null : "The old row must be set only once.";

            // Insertion index is found. Replace must be performed in both tail top and tail bottom.
            replaceRowInPage(tail.io, tail.pageId, tail.page, tail.buf, idx);

            // Unlock everything until the leaf, there's no need to hold these locks anymore.
            while (tail.lvl != 0) {
                writeUnlockAndClose(tail.pageId, tail.page, tail.buf, null);

                tail = tail.down;
            }

            // Read old row from the leaf to reduce contention in inner pages.
            oldRow = needOld ? getRow(tail.io, tail.buf, tail.idx) : (T)Boolean.TRUE;

            // Replace row in the leaf.
            replaceRowInPage(tail.io, tail.pageId, tail.page, tail.buf, tail.idx);

            finish();

            return FOUND;
        }

        /**
         * Tail page is kept locked after split until insert to the upper level will not be finished. It is needed
         * because split row will be "in flight" and if we'll release tail, remove on split row may fail.
         *
         * @param tailId Tail page ID.
         * @param tailPage Tail page pointer.
         * @param tailPageAddr Tail page address
         * @param io Tail page IO.
         * @param lvl Tail page level.
         */
        private void setTailForSplit(long tailId, long tailPage, long tailPageAddr, BPlusIO<L> io, int lvl) {
            assert tailId != 0L && tailPage != 0L && tailPageAddr != 0L;

            // Old tail must be unlocked.
            releaseTail();

            addTail(tailId, tailPage, tailPageAddr, io, lvl, Tail.EXACT);
        }

        /**
         * Finish put.
         */
        private void finish() {
            row = null;
            rightId = 0;

            releaseTail();
        }

        /** {@inheritDoc} */
        @Override boolean isFinished() {
            return row == null;
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param io IO.
         * @param idx Index.
         * @param lvl Level.
         * @return Move up row.
         * @throws IgniteCheckedException If failed.
         */
        private L insert(long pageId, long page, long pageAddr, BPlusIO<L> io, int idx, int lvl)
            throws IgniteCheckedException {
            int maxCnt = io.getMaxCount(pageAddr, pageSize());
            int cnt = io.getCount(pageAddr);

            if (cnt == maxCnt) // Need to split page.
                return insertWithSplit(pageId, page, pageAddr, io, idx, lvl);

            insertSimple(pageId, page, pageAddr, io, idx, null);

            return null;
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param io IO.
         * @param idx Index.
         * @param walPlc Full page WAL record policy.
         * @throws IgniteCheckedException If failed.
         */
        private void insertSimple(long pageId, long page, long pageAddr, BPlusIO<L> io, int idx, Boolean walPlc)
            throws IgniteCheckedException {
            boolean needWal = needWalDeltaRecord(pageId, page, walPlc);

            byte[] rowBytes = io.insert(pageAddr, idx, row, null, rightId, needWal);

            if (needWal)
                wal.log(new InsertRecord<>(grpId, pageId, io, idx, rowBytes, rightId));
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param io IO.
         * @param idx Index.
         * @param lvl Level.
         * @return Move up row.
         * @throws IgniteCheckedException If failed.
         */
        private L insertWithSplit(long pageId, long page, long pageAddr, BPlusIO<L> io, int idx, int lvl)
            throws IgniteCheckedException {
            long fwdId = allocatePage(null);
            long fwdPage = acquirePage(fwdId);

            try {
                // Need to check this before the actual split, because after the split we will have new forward page here.
                boolean hadFwd = io.getForward(pageAddr) != 0;

                long fwdPageAddr = writeLock(fwdId, fwdPage); // Initial write, no need to check for concurrent modification.

                assert fwdPageAddr != 0L;

                // TODO GG-11640 log a correct forward page record.
                final Boolean fwdPageWalPlc = Boolean.TRUE;

                try {
                    boolean midShift = splitPage(pageId, page, pageAddr, io, fwdId, fwdPageAddr, idx);

                    // Do insert.
                    int cnt = io.getCount(pageAddr);

                    if (idx < cnt || (idx == cnt && !midShift)) { // Insert into back page.
                        insertSimple(pageId, page, pageAddr, io, idx, null);

                        // Fix leftmost child of forward page, because newly inserted row will go up.
                        if (idx == cnt && !io.isLeaf()) {
                            inner(io).setLeft(fwdPageAddr, 0, rightId);

                            // Rare case, we can afford separate WAL record to avoid complexity.
                            if (needWalDeltaRecord(fwdId, fwdPage, fwdPageWalPlc))
                                wal.log(new FixLeftmostChildRecord(grpId, fwdId, rightId));
                        }
                    }
                    else // Insert into newly allocated forward page.
                        insertSimple(fwdId, fwdPage, fwdPageAddr, io, idx - cnt, fwdPageWalPlc);

                    // Do move up.
                    cnt = io.getCount(pageAddr);

                    // Last item from backward row goes up.
                    L moveUpRow = io.getLookupRow(BPlusTree.this, pageAddr, cnt - 1);

                    if (!io.isLeaf()) { // Leaf pages must contain all the links, inner pages remove moveUpLink.
                        io.setCount(pageAddr, cnt - 1);

                        if (needWalDeltaRecord(pageId, page, null)) // Rare case, we can afford separate WAL record to avoid complexity.
                            wal.log(new FixCountRecord(grpId, pageId, cnt - 1));
                    }

                    if (!hadFwd && lvl == getRootLevel()) { // We are splitting root.
                        long newRootId = allocatePage(null);
                        long newRootPage = acquirePage(newRootId);

                        try {
                            if (io.isLeaf())
                                io = latestInnerIO();

                            long newRootAddr = writeLock(newRootId, newRootPage); // Initial write.

                            assert newRootAddr != 0L;

                            // Never write full new root page, because it is known to be new.
                            final Boolean newRootPageWalPlc = Boolean.FALSE;

                            try {
                                boolean needWal = needWalDeltaRecord(newRootId, newRootPage, newRootPageWalPlc);

                                byte[] moveUpRowBytes = inner(io).initNewRoot(newRootAddr,
                                    newRootId,
                                    pageId,
                                    moveUpRow,
                                    null,
                                    fwdId,
                                    pageSize(),
                                    needWal,
                                    metrics);

                                if (needWal)
                                    wal.log(new NewRootInitRecord<>(grpId, newRootId, newRootId,
                                        inner(io), pageId, moveUpRowBytes, fwdId));
                            }
                            finally {
                                writeUnlock(newRootId, newRootPage, newRootAddr, newRootPageWalPlc, true);
                            }
                        }
                        finally {
                            releasePage(newRootId, newRootPage);
                        }

                        Bool res = write(metaPageId, addRoot, newRootId, lvl + 1, FALSE, statisticsHolder());

                        assert res == TRUE : res;

                        return null; // We've just moved link up to root, nothing to return here.
                    }

                    // Regular split.
                    return moveUpRow;
                }
                finally {
                    writeUnlock(fwdId, fwdPage, fwdPageAddr, fwdPageWalPlc, true);
                }
            }
            finally {
                releasePage(fwdId, fwdPage);
            }
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param fwdId Forward ID.
         * @param lvl Level.
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        private Result tryInsert(long pageId, long page, long fwdId, int lvl) throws IgniteCheckedException {
            // Init args.
            this.pageId = pageId;
            this.fwdId = fwdId;

            return write(pageId, page, insert, this, lvl, RETRY, statisticsHolder());
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param fwdId Forward ID.
         * @param lvl Level.
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        public Result tryReplace(long pageId, long page, long fwdId, int lvl) throws IgniteCheckedException {
            // Init args.
            this.pageId = pageId;
            this.fwdId = fwdId;

            return write(pageId, page, replace, this, lvl, RETRY, statisticsHolder());
        }

        /**
         * Replaces a row in the page with a new one.
         *
         * @param io Page IO for the page.
         * @param pageId Page id.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param idx Replacement index.
         */
        public void replaceRowInPage(BPlusIO<L> io, long pageId, long page, long pageAddr, int idx) throws IgniteCheckedException {
            boolean needWal = needWalDeltaRecord(pageId, page, null);

            byte[] newRowBytes = io.store(pageAddr, idx, row, null, needWal);

            if (needWal)
                wal.log(new ReplaceRecord<>(grpId, pageId, io, newRowBytes, idx));
        }

        /** {@inheritDoc} */
        @Override void checkLockRetry() throws IgniteCheckedException {
            // Non-null tail means that lock on the tail page is still being held, and we can't fail with exception.
            if (tail == null)
                super.checkLockRetry();
        }
    }

    /**
     * Invoke operation.
     */
    public final class Invoke extends Get {
        /** */
        Object x;

        /** */
        InvokeClosure<T> clo;

        /** */
        Bool closureInvoked = FALSE;

        /** */
        T foundRow;

        /** */
        Update op;

        /**
         * @param row Row.
         * @param x Implementation specific argument.
         * @param clo Closure.
         */
        private Invoke(L row, Object x, final InvokeClosure<T> clo) {
            super(row, false);

            assert clo != null;

            this.clo = clo;
            this.x = x;
        }

        /** {@inheritDoc} */
        @Override void pageId(long pageId) {
            this.pageId = pageId;

            if (op != null)
                op.pageId = pageId;
        }

        /** {@inheritDoc} */
        @Override void fwdId(long fwdId) {
            this.fwdId = fwdId;

            if (op != null)
                op.fwdId = fwdId;
        }

        /** {@inheritDoc} */
        @Override void backId(long backId) {
            this.backId = backId;

            if (op != null)
                op.backId = backId;
        }

        /** {@inheritDoc} */
        @Override void restartFromRoot(long rootId, int rootLvl, long rmvId) {
            super.restartFromRoot(rootId, rootLvl, rmvId);

            if (op != null)
                op.restartFromRoot(rootId, rootLvl, rmvId);
        }

        /** {@inheritDoc} */
        @Override boolean found(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            // If the operation is initialized, then the closure has been called already.
            if (op != null)
                return op.found(io, pageAddr, idx, lvl);

            if (lvl == 0) {
                if (closureInvoked == FALSE) {
                    closureInvoked = READY;

                    foundRow = getRow(io, pageAddr, idx, x);
                }

                return true;
            }

            return false;
        }

        /** {@inheritDoc} */
        @Override boolean notFound(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            // If the operation is initialized, then the closure has been called already.
            if (op != null)
                return op.notFound(io, pageAddr, idx, lvl);

            if (lvl == 0) {
                if (closureInvoked == FALSE)
                    closureInvoked = READY;

                return true;
            }

            return false;
        }

        /**
         * @throws IgniteCheckedException If failed.
         */
        private void invokeClosure() throws IgniteCheckedException {
            if (closureInvoked != READY)
                return;

            closureInvoked = DONE;

            clo.call(foundRow);

            switch (clo.operationType()) {
                case PUT:
                    T newRow = clo.newRow();

                    assert newRow != null;

                    op = new Put(newRow, false);

                    break;

                case REMOVE:
                    assert foundRow != null;

                    op = new Remove(row, false);

                    break;

                case NOOP:
                case IN_PLACE:
                    return;

                default:
                    throw new IllegalStateException();
            }

            op.copyFrom(this);

            op.invoke = this;
        }

        /** {@inheritDoc} */
        @Override public boolean canRelease(long pageId, int lvl) {
            if (pageId == 0L)
                return false;

            if (op == null)
                return true;

            return op.canRelease(pageId, lvl);
        }

        /**
         * @return {@code true} If it is a {@link Put} operation internally.
         */
        private boolean isPut() {
            return op != null && op.getClass() == Put.class;
        }

        /**
         * @return {@code true} If it is a {@link Remove} operation internally.
         */
        private boolean isRemove() {
            return op != null && op.getClass() == Remove.class;
        }

        /**
         * @param pageId Page ID.
         * @param lvl Level.
         * @return {@code true} If it is a {@link Remove} and the page is in tail.
         */
        private boolean isTail(long pageId, int lvl) {
            return op != null && op.isTail(pageId, lvl);
        }

        /**
         */
        private void levelExit() {
            if (isRemove())
                ((Remove)op).page = 0L;
        }

        /**
         * Release all the resources by the end of operation.
         * @throws IgniteCheckedException if failed.
         */
        private void releaseAll() throws IgniteCheckedException {
            if (isRemove())
                ((Remove)op).releaseAll();
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param fwdId Forward ID.
         * @param lvl Level.
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        private Result onNotFound(long pageId, long page, long fwdId, int lvl)
            throws IgniteCheckedException {
            if (op == null)
                return NOT_FOUND;

            if (isRemove()) {
                assert lvl == 0;

                return ((Remove)op).finish(NOT_FOUND);
            }

            return ((Put)op).tryInsert(pageId, page, fwdId, lvl);
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param backId Back page ID.
         * @param fwdId Forward ID.
         * @param lvl Level.
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        private Result onFound(long pageId, long page, long backId, long fwdId, int lvl)
            throws IgniteCheckedException {
            if (op == null)
                return FOUND;

            if (isRemove())
                return ((Remove)op).tryRemoveFromLeaf(pageId, page, backId, fwdId, lvl);

            return ((Put)op).tryReplace(pageId, page, fwdId, lvl);
        }

        /**
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        private Result tryFinish() throws IgniteCheckedException {
            assert op != null; // Must be guarded by isFinished.

            Result res = op.finishTail();

            if (res == NOT_FOUND)
                res = RETRY;

            if (res == RETRY && isPut())
                op.releaseTail();

            assert res == FOUND || res == RETRY : res;

            return res;
        }

        /** {@inheritDoc} */
        @Override boolean isFinished() {
            if (closureInvoked != DONE)
                return false;

            if (op == null)
                return true;

            return op.isFinished();
        }
    }

    /**
     * Update operation. Has basic operations for {@link Tail} support.
     */
    private abstract class Update extends Get {
        /** We may need to lock part of the tree branch from the bottom to up for multiple levels. */
        Tail<L> tail;

        /**
         * @param row Row.
         */
        private Update(L row) {
            super(row, false);
        }

        /**
         * Method that's invoked when operation goes up from the recursion and {@link #isFinished()} returns false.
         * Either finishes the operation or locks the page for further processing on another level.
         * <p/>
         * Returns {@link Result#FOUND} if operation has finished and {@link #isFinished()} returns {@code true} now.
         * <p/>
         * Returns {@link Result#RETRY} if operation should be retried.
         * <p/>
         * Returns {@link Result#NOT_FOUND} if operation has added the page to tail, meaning that operation can't be
         * finished on current level.
         *
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param backId Back page ID.
         * @param fwdId Forward ID.
         * @param lvl Level.
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        protected abstract Result finishOrLockTail(long pageId, long page, long backId, long fwdId, int lvl)
            throws IgniteCheckedException;

        /**
         * Process tail and finish. Same as {@link #finishOrLockTail(long, long, long, long, int)} but doesn't add the
         * page to the tail.
         *
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        protected abstract Result finishTail() throws IgniteCheckedException;

        /**
         * Release pages for all locked levels at the tail.
         */
        protected final void releaseTail() {
            doReleaseTail(tail);

            tail = null;
        }

        /**
         * @param rootLvl Actual root level.
         * @return {@code true} If tail level is correct.
         */
        protected final boolean checkTailLevel(int rootLvl) {
            return tail == null || tail.lvl < rootLvl;
        }

        /**
         * @param t Tail.
         */
        protected final void doReleaseTail(Tail<L> t) {
            while (t != null) {
                writeUnlockAndClose(t.pageId, t.page, t.buf, t.walPlc);

                Tail<L> s = t.sibling;

                if (s != null)
                    writeUnlockAndClose(s.pageId, s.page, s.buf, s.walPlc);

                t = t.down;
            }
        }

        /** {@inheritDoc} */
        @Override public final boolean canRelease(long pageId, int lvl) {
            return pageId != 0L && !isTail(pageId, lvl);
        }

        /**
         * @param pageId Page ID.
         * @param lvl Level.
         * @return {@code true} If the given page is in tail.
         */
        protected final boolean isTail(long pageId, int lvl) {
            Tail<L> t = tail;

            while (t != null) {
                if (t.lvl < lvl)
                    return false;

                if (t.lvl == lvl) {
                    if (t.pageId == pageId)
                        return true;

                    t = t.sibling;

                    return t != null && t.pageId == pageId;
                }

                t = t.down;
            }

            return false;
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param io IO.
         * @param lvl Level.
         * @param type Type.
         * @return Added tail.
         */
        protected final Tail<L> addTail(long pageId, long page, long pageAddr, BPlusIO<L> io, int lvl, byte type) {
            final Tail<L> t = new Tail<>(pageId, page, pageAddr, io, type, lvl);

            if (tail == null)
                tail = t;
            else if (tail.lvl == lvl) { // Add on the same level.
                assert tail.sibling == null; // Only two siblings on a single level.

                if (type == Tail.EXACT) {
                    assert tail.type != Tail.EXACT;

                    if (tail.down != null) { // Take down from sibling, EXACT must own down link.
                        t.down = tail.down;
                        tail.down = null;
                    }

                    t.sibling = tail;
                    tail = t;
                }
                else {
                    assert tail.type == Tail.EXACT : tail.type;

                    tail.sibling = t;
                }
            }
            else { // Add on top of existing level.
                assert tail.lvl == lvl - 1 : "tail=" + tail + ", lvl=" + lvl;

                t.down = tail;
                tail = t;
            }

            return t;
        }

        /**
         * @param tail Tail to start with.
         * @param lvl Level.
         * @return Tail of {@link Tail#EXACT} type at the given level.
         */
        protected final Tail<L> getTail(Tail<L> tail, int lvl) {
            assert tail != null;
            assert lvl >= 0 && lvl <= tail.lvl : lvl;

            Tail<L> t = tail;

            while (t.lvl != lvl)
                t = t.down;

            assert t.type == Tail.EXACT : t.type; // All the down links must be of EXACT type.

            return t;
        }

        /**
         * @param tail Tail.
         * @return Insertion point. May be negative.
         * @throws IgniteCheckedException If failed.
         */
        protected final int insertionPoint(Tail<L> tail) throws IgniteCheckedException {
            assert tail.type == Tail.EXACT : tail.type;

            if (tail.idx == Short.MIN_VALUE) {
                int idx = findInsertionPoint(tail.lvl, tail.io, tail.buf, 0, tail.getCount(), row, 0);

                assert checkIndex(idx) : idx;

                tail.idx = (short)idx;
            }

            return tail.idx;
        }

        /**
         * @param keys If we have to show keys.
         * @return Tail as a String.
         * @throws IgniteCheckedException If failed.
         */
        protected final String printTail(boolean keys) throws IgniteCheckedException {
            SB sb = new SB("");

            Tail<L> t = tail;

            while (t != null) {
                sb.a(t.lvl).a(": ").a(printPage(t.io, t.buf, keys));

                Tail<L> d = t.down;

                t = t.sibling;

                if (t != null)
                    sb.a(" -> ").a(t.type == Tail.FORWARD ? "F" : "B").a(' ').a(printPage(t.io, t.buf, keys));

                sb.a('\n');

                t = d;
            }

            return sb.toString();
        }
    }

    /**
     * Remove operation.
     */
    public class Remove extends Update implements ReuseBag {
        /** */
        boolean needReplaceInner;

        /** */
        Bool needMergeEmptyBranch = FALSE;

        /** Removed row. */
        T rmvd;

        /** Current page absolute pointer. */
        long page;

        /** */
        Object freePages;

        /** */
        final boolean needOld;

        /** */
        final Object x;

        /** */
        final PageHandler<Remove, Result> rmvFromLeafHnd;

        /**
         * @param row Row.
         * @param needOld {@code True} If need return old value.
         */
        private Remove(L row, boolean needOld) {
            this(row, needOld, null, rmvFromLeaf);
        }

        /**
         * @param row Row.
         * @param needOld {@code True} If need return old value.
         * @param x Implementation specific argument.
         * @param rmvFromLeaf Remove from leaf page handler.
         */
        private Remove(L row, boolean needOld, Object x, PageHandler<Remove, Result> rmvFromLeaf) {
            super(row);

            this.needOld = needOld;
            this.x = x;

            rmvFromLeafHnd = rmvFromLeaf;
        }

        /** {@inheritDoc} */
        @Override public long pollFreePage() {
            if (freePages == null)
                return 0L;

            if (freePages.getClass() == GridLongList.class) {
                GridLongList list = ((GridLongList)freePages);

                return list.isEmpty() ? 0L : list.remove();
            }

            long res = (long)freePages;

            freePages = null;

            return res;
        }

        /** {@inheritDoc} */
        @Override public void addFreePage(long pageId) {
            assert pageId != 0L;

            if (freePages == null)
                freePages = pageId;
            else {
                GridLongList list;

                if (freePages.getClass() == GridLongList.class)
                    list = (GridLongList)freePages;
                else {
                    list = new GridLongList(4);

                    list.add((Long)freePages);
                    freePages = list;
                }

                list.add(pageId);
            }
        }

        /** {@inheritDoc} */
        @Override public boolean isEmpty() {
            if (freePages == null)
                return true;

            if (freePages.getClass() == GridLongList.class) {
                GridLongList list = ((GridLongList)freePages);

                return list.isEmpty();
            }

            return false;
        }

        /** {@inheritDoc} */
        @Override boolean notFound(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            if (lvl == 0) {
                assert tail == null;

                return true;
            }

            return false;
        }

        /**
         * @return Flag indicating that values are removed using an interval
         * (i.e. {@link #row} specifies the start of the interval, not an exact match).
         */
        protected boolean ceil() {
            return false;
        }

        /**
         * Finish the operation.
         */
        protected Result finish(Result res) {
            assert tail == null;

            row = null;

            return res;
        }

        /**
         * @throws IgniteCheckedException If failed.
         * @return Tail to release if an empty branch was not merged.
         */
        private Tail<L> mergeEmptyBranch() throws IgniteCheckedException {
            assert needMergeEmptyBranch == TRUE : needMergeEmptyBranch;

            Tail<L> t = tail;

            // Find empty branch beginning.
            for (Tail<L> t0 = t.down; t0.lvl != 0; t0 = t0.down) {
                assert t0.type == Tail.EXACT : t0.type;

                if (t0.getCount() != 0)
                    t = t0; // Here we correctly handle empty windows in the middle of branch.
            }

            int cnt = t.getCount();
            int idx = fix(insertionPoint(t));

            assert cnt > 0 : cnt;

            if (idx == cnt)
                idx--;

            // If at the join point we will not be able to merge, we need to retry.
            if (!checkChildren(t, t.getLeftChild(), t.getRightChild(), idx))
                return t;

            // Make the branch really empty before the merge.
            removeDataRowFromLeafTail(t);

            while (t.lvl != 0) { // If we've found empty branch, merge it top-down.
                boolean res = merge(t);

                // All the merges must succeed because we have only empty pages from one side.
                assert res : needMergeEmptyBranch + "\n" + printTail(true);

                if (needMergeEmptyBranch == TRUE)
                    needMergeEmptyBranch = READY; // Need to mark that we've already done the first (top) merge.

                t = t.down;
            }

            return null; // Succeeded.
        }

        /**
         * @param t Tail.
         * @throws IgniteCheckedException If failed.
         */
        private void mergeBottomUp(Tail<L> t) throws IgniteCheckedException {
            assert needMergeEmptyBranch == FALSE || needMergeEmptyBranch == DONE : needMergeEmptyBranch;

            if (t.down == null || t.down.sibling == null) {
                // Do remove if we did not yet.
                if (t.lvl == 0 && !isRemoved())
                    removeDataRowFromLeafTail(t);

                return; // Nothing to merge.
            }

            mergeBottomUp(t.down);
            merge(t);
        }

        /**
         * @return {@code true} If found.
         * @throws IgniteCheckedException If failed.
         */
        private boolean isInnerKeyInTail() throws IgniteCheckedException {
            assert tail.lvl > 0 : tail.lvl;

            return insertionPoint(tail) >= 0;
        }

        /**
         * @return {@code true} If already removed from leaf.
         */
        protected boolean isRemoved() {
            return rmvd != null;
        }

        /**
         * @param t Tail to release.
         * @return {@code true} If we need to retry or {@code false} to exit.
         */
        protected boolean releaseForRetry(Tail<L> t) {
            // Try to simply release all first.
            if (t.lvl <= 1) {
                // We've just locked leaf and did not do the remove, can safely release all and retry.
                assert !isRemoved() : "removed";

                // These fields will be setup again on remove from leaf.
                needReplaceInner = false;
                needMergeEmptyBranch = FALSE;

                releaseTail();

                return true;
            }

            // Release all up to the given tail with its direct children.
            if (t.down != null) {
                Tail<L> newTail = t.down.down;

                if (newTail != null) {
                    t.down.down = null;

                    releaseTail();

                    tail = newTail;

                    return true;
                }
            }

            // Here we wanted to do a regular merge after all the important operations,
            // so we can leave this invalid tail as is. We have no other choice here
            // because our tail is not long enough for retry. Exiting.
            assert isRemoved() && !needReplaceInner && needMergeEmptyBranch != TRUE
                    : "isRemoved=" + isRemoved() + ", needReplaceInner=" + needReplaceInner
                    + ", needMergeEmptyBranch=" + needMergeEmptyBranch;

            return false;
        }

        /** {@inheritDoc} */
        @Override protected Result finishTail() throws IgniteCheckedException {
            assert !isFinished() && tail.type == Tail.EXACT && tail.lvl >= 0 && needMergeEmptyBranch != READY
                    : "isFinished=" + isFinished() + ", tail=" + tail + ", needMergeEmptyBranch=" + needMergeEmptyBranch;

            if (tail.lvl == 0) {
                // At the bottom level we can't have a tail without a sibling, it means we have higher levels.
                assert tail.sibling != null : tail;

                return NOT_FOUND; // Lock upper level, we are at the bottom now.
            }
            else {
                // We may lock wrong triangle because of concurrent operations.
                if (!validateTail()) {
                    if (releaseForRetry(tail))
                        return RETRY;

                    // It was a regular merge, leave as is and exit.
                }
                else {
                    // Try to find inner key on inner level.
                    if (needReplaceInner && !isInnerKeyInTail()) {
                        // Since we setup needReplaceInner in leaf page write lock and do not release it,
                        // we should not be able to miss the inner key. Even if concurrent merge
                        // happened the inner key must still exist.
                        return NOT_FOUND; // Lock the whole branch up to the inner key.
                    }

                    // Try to merge an empty branch.
                    if (needMergeEmptyBranch == TRUE) {
                        // We can't merge empty branch if tail is a routing page.
                        if (tail.getCount() == 0)
                            return NOT_FOUND; // Lock the whole branch up to the first non-routing.

                        // Top-down merge for empty branch. The actual row remove will happen here if everything is ok.
                        Tail<L> t = mergeEmptyBranch();

                        if (t != null) {
                            // We were not able to merge empty branch, need to release and retry.
                            boolean ok = releaseForRetry(t);

                            assert ok; // Here we must always retry because it is not a regular merge.

                            return RETRY;
                        }

                        needMergeEmptyBranch = DONE;
                    }

                    // The actual row remove may happen here as well.
                    mergeBottomUp(tail);

                    if (needReplaceInner) {
                        replaceInner(); // Replace inner key with new max key for the left subtree.

                        needReplaceInner = false;
                    }

                    // Loop is needed to prevent the rare case when, after parallel remove of keys, empty root remains.
                    // B+tree after removes key: [empty_root] - [empty_inner_node] - [5] ==>
                    // B+tree after cutting empty root: [5]
                    while (tail.getCount() == 0 && tail.lvl != 0 && getRootLevel() == tail.lvl) {
                        // Free root if it became empty after merge.

                        cutRoot(tail.lvl);
                        freePage(tail.pageId, tail.page, tail.buf, tail.walPlc, true);

                        tail = tail.down;

                        assert tail.sibling == null : tail;

                        // Exit: we are done.
                    }

                    if (tail.sibling != null &&
                        tail.getCount() + tail.sibling.getCount() < tail.io.getMaxCount(tail.buf, pageSize())) {
                        // Release everything lower than tail, we've already merged this path.
                        doReleaseTail(tail.down);
                        tail.down = null;

                        return NOT_FOUND; // Lock and merge one level more.
                    }

                    // We don't want to merge anything more, exiting.
                }
            }

            // If we've found nothing in the tree, we should not do any modifications or take tail locks.
            assert isRemoved();

            releaseTail();

            return finish(FOUND);
        }

        /**
         * @param t Tail.
         * @throws IgniteCheckedException If failed.
         */
        private void removeDataRowFromLeafTail(Tail<L> t) throws IgniteCheckedException {
            assert !isRemoved();

            Tail<L> leaf = getTail(t, 0);

            removeDataRowFromLeaf(leaf.pageId, leaf.page, leaf.buf, leaf.walPlc, leaf.io, leaf.getCount(), insertionPoint(leaf));
        }

        /**
         * @param leafId Leaf page ID.
         * @param leafPage Leaf page pointer.
         * @param backId Back page ID.
         * @param fwdId Forward ID.
         * @return Result code.
         * @throws IgniteCheckedException If failed.
         */
        private Result removeFromLeaf(long leafId, long leafPage, long backId, long fwdId) throws IgniteCheckedException {
            // Init parameters.
            pageId = leafId;
            page = leafPage;
            this.backId = backId;
            this.fwdId = fwdId;

            // Usually this will be true, so in most cases we should not lock any extra pages.
            if (backId == 0)
                return doRemoveFromLeaf();

            // Lock back page before the remove, we'll need it for merges.
            long backPage = acquirePage(backId);

            try {
                return write(backId, backPage, lockBackAndRmvFromLeaf, this, 0, RETRY, statisticsHolder());
            }
            finally {
                if (canRelease(backId, 0))
                    releasePage(backId, backPage);
            }
        }

        /**
         * @return Result code.
         * @throws IgniteCheckedException If failed.
         */
        protected Result doRemoveFromLeaf() throws IgniteCheckedException {
            assert page != 0L;

            return write(pageId, page, rmvFromLeafHnd, this, 0, RETRY, statisticsHolder());
        }

        /**
         * @param lvl Level.
         * @return Result code.
         * @throws IgniteCheckedException If failed.
         */
        private Result doLockTail(int lvl) throws IgniteCheckedException {
            assert page != 0L;

            return write(pageId, page, lockTail, this, lvl, RETRY, statisticsHolder());
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param backId Back page ID.
         * @param fwdId Expected forward page ID.
         * @param lvl Level.
         * @return Result code.
         * @throws IgniteCheckedException If failed.
         */
        private Result lockTail(long pageId, long page, long backId, long fwdId, int lvl)
            throws IgniteCheckedException {
            assert tail != null;

            // Init parameters for the handlers.
            this.pageId = pageId;
            this.page = page;
            this.fwdId = fwdId;
            this.backId = backId;

            if (backId == 0) // Back page ID is provided only when the last move was to the right.
                return doLockTail(lvl);

            long backPage = acquirePage(backId);

            try {
                return write(backId, backPage, lockBackAndTail, this, lvl, RETRY, statisticsHolder());
            }
            finally {
                if (canRelease(backId, lvl))
                    releasePage(backId, backPage);
            }
        }

        /**
         * @param lvl Level.
         * @return Result code.
         * @throws IgniteCheckedException If failed.
         */
        protected Result lockForward(int lvl) throws IgniteCheckedException {
            assert fwdId != 0 : fwdId;
            assert backId == 0 : backId;

            long fwdId = this.fwdId;
            long fwdPage = acquirePage(fwdId);

            try {
                return write(fwdId, fwdPage, lockTailForward, this, lvl, RETRY, statisticsHolder());
            }
            finally {
                // If we were not able to lock forward page as tail, release the page.
                if (canRelease(fwdId, lvl))
                    releasePage(fwdId, fwdPage);
            }
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param walPlc Full page WAL record policy.
         * @param io IO.
         * @param cnt Count.
         * @param idx Index to remove.
         * @throws IgniteCheckedException If failed.
         */
        protected void removeDataRowFromLeaf(
            long pageId,
            long page,
            long pageAddr,
            Boolean walPlc,
            BPlusIO<L> io,
            int cnt,
            int idx
        ) throws IgniteCheckedException {
            assert idx >= 0 && idx < cnt : idx;
            assert io.isLeaf() : "inner";
            assert !isRemoved() : "already removed";

            // Detach the row.
            rmvd = needOld ? getRow(io, pageAddr, idx, x) : (T)Boolean.TRUE;

            doRemove(pageId, page, pageAddr, walPlc, io, cnt, idx);

            assert isRemoved();
        }

        /**
         *
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param walPlc Full page WAL record policy.
         * @param io IO.
         *
         * @param cnt Count.
         * @param idx Index to remove.
         * @throws IgniteCheckedException If failed.
         */
        protected void doRemove(long pageId, long page, long pageAddr, Boolean walPlc, BPlusIO<L> io, int cnt,
            int idx)
            throws IgniteCheckedException {
            assert cnt > 0 : cnt;
            assert idx >= 0 && idx < cnt : idx + " " + cnt;

            io.remove(pageAddr, idx, cnt);

            if (needWalDeltaRecord(pageId, page, walPlc))
                wal.log(new RemoveRecord(grpId, pageId, idx, cnt));
        }

        /**
         * @return {@code true} If the currently locked tail is valid.
         * @throws IgniteCheckedException If failed.
         */
        private boolean validateTail() throws IgniteCheckedException {
            Tail<L> t = tail;

            if (t.down == null) {
                // It is just a regular merge in progress.
                assert needMergeEmptyBranch != TRUE && !needReplaceInner
                        : "needMergeEmptyBranch=" + needMergeEmptyBranch + ", needReplaceInner" + needReplaceInner;

                return true;
            }

            Tail<L> left = t.getLeftChild();
            Tail<L> right = t.getRightChild();

            assert left.pageId != right.pageId;

            int cnt = t.getCount();

            if (cnt != 0) {
                int idx = fix(insertionPoint(t));

                if (idx == cnt)
                    idx--;

                // The locked left and right pages allowed to be children of the tail.
                if (isChild(t, left, idx, cnt, false) && isChild(t, right, idx, cnt, true))
                    return true;
            }

            // Otherwise they must correctly reside with respect to tail sibling.
            Tail<L> s = t.sibling;

            if (s == null)
                return false;

            // It must be the rightmost element.
            int idx = cnt == 0 ? 0 : cnt - 1;

            if (s.type == Tail.FORWARD)
                return isChild(t, left, idx, cnt, true) &&
                    isChild(s, right, 0, 0, false);

            assert s.type == Tail.BACK;

            if (!isChild(t, right, 0, 0, false))
                return false;

            cnt = s.getCount();
            idx = cnt == 0 ? 0 : cnt - 1;

            return isChild(s, left, idx, cnt, true);
        }

        /**
         * @param prnt Parent.
         * @param child Child.
         * @param idx Index.
         * @param cnt Count.
         * @param right Right or left.
         * @return {@code true} If they are really parent and child.
         */
        private boolean isChild(Tail<L> prnt, Tail<L> child, int idx, int cnt, boolean right) {
            if (right && cnt != 0)
                idx++;

            return inner(prnt.io).getLeft(prnt.buf, idx) == child.pageId;
        }

        /**
         * @param prnt Parent.
         * @param left Left.
         * @param right Right.
         * @param idx Index.
         * @return {@code true} If children are correct.
         */
        private boolean checkChildren(Tail<L> prnt, Tail<L> left, Tail<L> right, int idx) {
            assert idx >= 0 && idx < prnt.getCount() : idx;

            return inner(prnt.io).getLeft(prnt.buf, idx) == left.pageId &&
                inner(prnt.io).getRight(prnt.buf, idx) == right.pageId;
        }

        /**
         * @param prnt Parent tail.
         * @param left Left child tail.
         * @param right Right child tail.
         * @return {@code true} If merged successfully.
         * @throws IgniteCheckedException If failed.
         */
        private boolean doMerge(Tail<L> prnt, Tail<L> left, Tail<L> right)
            throws IgniteCheckedException {
            assert right.io == left.io; // Otherwise incompatible.
            assert left.io.getForward(left.buf) == right.pageId;

            int prntCnt = prnt.getCount();
            int prntIdx = fix(insertionPoint(prnt));

            // Fix index for the right move: remove the last item.
            if (prntIdx == prntCnt)
                prntIdx--;

            // The only case when the siblings can have different parents is when we are merging
            // top-down an empty branch and we already merged the join point with non-empty branch.
            // This happens because when merging empty page we do not update parent link to a lower
            // empty page in the branch since it will be dropped anyways.
            if (needMergeEmptyBranch == READY)
                assert left.getCount() == 0 || right.getCount() == 0; // Empty branch check.
            else if (!checkChildren(prnt, left, right, prntIdx))
                return false;

            boolean emptyBranch = needMergeEmptyBranch == TRUE || needMergeEmptyBranch == READY;

            if (!left.io.merge(prnt.io, prnt.buf, prntIdx, left.buf, right.buf, emptyBranch, pageSize()))
                return false;

            // Invalidate indexes after successful merge.
            prnt.idx = Short.MIN_VALUE;
            left.idx = Short.MIN_VALUE;

            // TODO GG-11640 log a correct merge record.
            left.walPlc = Boolean.TRUE;

            // Remove split key from parent. If we are merging empty branch then remove only on the top iteration.
            if (needMergeEmptyBranch != READY)
                doRemove(prnt.pageId, prnt.page, prnt.buf, prnt.walPlc, prnt.io, prntCnt, prntIdx);

            // Forward page is now empty and has no links, can free and release it right away.
            freePage(right.pageId, right.page, right.buf, right.walPlc, true);

            return true;
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param walPlc Full page WAL record policy.
         * @param release Release write lock and release page.
         * @throws IgniteCheckedException If failed.
         */
        private void freePage(long pageId, long page, long pageAddr, Boolean walPlc, boolean release)
            throws IgniteCheckedException {

            long effectivePageId = PageIdUtils.effectivePageId(pageId);

            long recycled = recyclePage(pageId, page, pageAddr, walPlc);

            if (effectivePageId != PageIdUtils.effectivePageId(pageId))
                throw new IllegalStateException("Effective page ID must stay the same.");

            if (release)
                writeUnlockAndClose(pageId, page, pageAddr, walPlc);

            addFreePage(recycled);
        }

        /**
         * @param lvl Expected root level.
         * @throws IgniteCheckedException If failed.
         */
        private void cutRoot(int lvl) throws IgniteCheckedException {
            Bool res = write(metaPageId, cutRoot, lvl, FALSE, statisticsHolder());

            assert res == TRUE : res;
        }

        /**
         * @throws IgniteCheckedException If failed.
         */
        private void reuseFreePages() throws IgniteCheckedException {
            // If we have a bag, then it will be processed at the upper level.
            if (reuseList != null && freePages != null)
                reuseList.addForRecycle(this);
        }

        /**
         * @throws IgniteCheckedException If failed.
         */
        private void replaceInner() throws IgniteCheckedException {
            assert needReplaceInner;

            int innerIdx;

            Tail<L> inner = tail;

            for (;;) { // Find inner key to replace.
                assert inner.type == Tail.EXACT : inner.type;
                assert inner.lvl > 0 : "leaf " + tail.lvl;

                innerIdx = insertionPoint(inner);

                if (innerIdx >= 0)
                    break; // Successfully found the inner key.

                // We did not find the inner key to replace.
                if (inner.lvl == 1)
                    return; // After leaf merge inner page lost inner key, nothing to do here.

                // Go level down.
                inner = inner.down;
            }

            Tail<L> leaf = getTail(inner, 0);

            int leafCnt = leaf.getCount();

            assert leafCnt > 0 : leafCnt; // Leaf must be merged at this point already if it was empty.

            int leafIdx = leafCnt - 1; // Last leaf item.

            // We increment remove ID in write lock on inner page, thus it is guaranteed that
            // any successor, who already passed the inner page, will get greater value at leaf
            // than he had read at the beginning of the operation and will retry operation from root.
            long rmvId = globalRmvId.incrementAndGet();

            // Update inner key with the new biggest key of left subtree.
            inner.io.store(inner.buf, innerIdx, leaf.io, leaf.buf, leafIdx);
            inner.io.setRemoveId(inner.buf, rmvId);

            // TODO GG-11640 log a correct inner replace record.
            inner.walPlc = Boolean.TRUE;

            // Update remove ID for the leaf page.
            leaf.io.setRemoveId(leaf.buf, rmvId);

            if (needWalDeltaRecord(leaf.pageId, leaf.page, leaf.walPlc))
                wal.log(new FixRemoveId(grpId, leaf.pageId, rmvId));
        }

        /**
         * @param prnt Parent for merge.
         * @return {@code true} If merged, {@code false} if not (because of insufficient space or empty parent).
         * @throws IgniteCheckedException If failed.
         */
        private boolean merge(Tail<L> prnt) throws IgniteCheckedException {
            // If we are merging empty branch this is acceptable because even if we merge
            // two routing pages, one of them is effectively dropped in this merge, so just
            // keep a single routing page.
            if (prnt.getCount() == 0 && needMergeEmptyBranch != READY)
                return false; // Parent is an empty routing page, child forward page will have another parent.

            Tail<L> left = prnt.getLeftChild();
            Tail<L> right = prnt.getRightChild();

            if (!doMerge(prnt, left, right))
                return false;

            // left from BACK becomes EXACT.
            if (left.type == Tail.BACK) {
                assert left.sibling == null;

                left.down = right.down;
                left.type = Tail.EXACT;
                prnt.down = left;
            }
            else { // left is already EXACT.
                assert left.type == Tail.EXACT : left.type;
                assert left.sibling != null;

                left.sibling = null;
            }

            return true;
        }

        /** {@inheritDoc} */
        @Override boolean isFinished() {
            return row == null;
        }

        /**
         * @throws IgniteCheckedException If failed.
         */
        private void releaseAll() throws IgniteCheckedException {
            releaseTail();
            reuseFreePages();
        }

        /** {@inheritDoc} */
        @Override protected Result finishOrLockTail(long pageId, long page, long backId, long fwdId, int lvl)
            throws IgniteCheckedException {
            Result res = finishTail();

            if (res == NOT_FOUND)
                res = lockTail(pageId, page, backId, fwdId, lvl);

            return res;
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param backId Back page ID.
         * @param fwdId Forward ID.
         * @param lvl Level.
         * @return Result.
         * @throws IgniteCheckedException If failed.
         */
        private Result tryRemoveFromLeaf(long pageId, long page, long backId, long fwdId, int lvl)
            throws IgniteCheckedException {
            // We must be at the bottom here, just need to remove row from the current page.
            assert lvl == 0 : lvl;

            Result res = removeFromLeaf(pageId, page, backId, fwdId);

            if (res == FOUND && tail == null) // Finish if we don't need to do any merges.
                return finish(res);

            return res;
        }
    }

    /**
     * Tail for remove.
     */
    private static final class Tail<L> {
        /** */
        static final byte BACK = 0;

        /** */
        static final byte EXACT = 1;

        /** */
        static final byte FORWARD = 2;

        /** */
        private final long pageId;

        /** */
        private final long page;

        /** */
        private final long buf;

        /** */
        private Boolean walPlc;

        /** */
        private final BPlusIO<L> io;

        /** */
        private byte type;

        /** */
        private final int lvl;

        /** */
        private short idx = Short.MIN_VALUE;

        /** Only {@link #EXACT} tail can have either {@link #BACK} or {@link #FORWARD} sibling.*/
        private Tail<L> sibling;

        /** Only {@link #EXACT} tail can point to {@link #EXACT} tail of lower level. */
        private Tail<L> down;

        /**
         * @param pageId Page ID.
         * @param page Page absolute pointer.
         * @param buf Buffer.
         * @param io IO.
         * @param type Type.
         * @param lvl Level.
         */
        private Tail(long pageId, long page, long buf, BPlusIO<L> io, byte type, int lvl) {
            assert type == BACK || type == EXACT || type == FORWARD : type;
            assert lvl >= 0 && lvl <= Byte.MAX_VALUE : lvl;
            assert pageId != 0L;
            assert page != 0L;
            assert buf != 0L;

            this.pageId = pageId;
            this.page = page;
            this.buf = buf;
            this.io = io;
            this.type = type;
            this.lvl = (byte)lvl;
        }

        /**
         * @return Count.
         */
        private int getCount() {
            return io.getCount(buf);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return new SB("Tail[").a("pageId=").appendHex(pageId).a(", cnt= ").a(getCount())
                .a(", lvl=" + lvl).a(", sibling=").a(sibling).a("]").toString();
        }

        /**
         * @return Left child.
         */
        private Tail<L> getLeftChild() {
            Tail<L> s = down.sibling;

            return s.type == Tail.BACK ? s : down;
        }

        /**
         * @return Right child.
         */
        private Tail<L> getRightChild() {
            Tail<L> s = down.sibling;

            return s.type == Tail.FORWARD ? s : down;
        }
    }

    /**
     * @param io IO.
     * @param buf Buffer.
     * @param low Start index.
     * @param cnt Row count.
     * @param row Lookup row.
     * @param shift Shift if equal.
     * @return Insertion point as in {@link Arrays#binarySearch(Object[], Object, Comparator)}.
     * @throws IgniteCheckedException If failed.
     */
    private int findInsertionPoint(int lvl, BPlusIO<L> io, long buf, int low, int cnt, L row, int shift)
        throws IgniteCheckedException {
        assert row != null;

        if (sequentialWriteOptsEnabled) {
            assert io.getForward(buf) == 0L;

            return -cnt - 1;
        }

        int high = cnt - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;

            int cmp = compare(lvl, io, buf, mid, row);

            if (cmp == 0)
                cmp = -shift; // We need to fix the case when search row matches multiple data rows.

            //noinspection Duplicates
            if (cmp < 0)
                low = mid + 1;
            else if (cmp > 0)
                high = mid - 1;
            else
                return mid; // Found.
        }

        return -(low + 1);  // Not found.
    }

    /**
     * @param pageAddr Page address.
     * @return IO.
     */
    private BPlusIO<L> io(long pageAddr) {
        assert pageAddr != 0;

        int type = PageIO.getType(pageAddr);
        int ver = PageIO.getVersion(pageAddr);

        if (innerIos.getType() == type)
            return innerIos.forVersion(ver);

        if (leafIos.getType() == type)
            return leafIos.forVersion(ver);

        throw new IllegalStateException("Unknown page type: " + type + " pageId: " + U.hexLong(PageIO.getPageId(pageAddr)));
    }

    /**
     * @param io IO.
     * @return Inner page IO.
     */
    private static <L> BPlusInnerIO<L> inner(BPlusIO<L> io) {
        assert !io.isLeaf();

        return (BPlusInnerIO<L>)io;
    }

    /**
     * @return Latest version of inner page IO.
     */
    public final BPlusInnerIO<L> latestInnerIO() {
        return innerIos.latest();
    }

    /**
     * @return Latest version of leaf page IO.
     */
    public final BPlusLeafIO<L> latestLeafIO() {
        return leafIos.latest();
    }

    /**
     * @param io IO.
     * @param pageAddr Page address.
     * @param idx Index of row in the given buffer.
     * @param row Lookup row.
     * @return Comparison result as in {@link Comparator#compare(Object, Object)}.
     * @throws IgniteCheckedException If failed.
     */
    protected abstract int compare(BPlusIO<L> io, long pageAddr, int idx, L row) throws IgniteCheckedException;

    /**
     * @param lvl Level.
     * @param io IO.
     * @param pageAddr Page address.
     * @param idx Index of row in the given buffer.
     * @param row Lookup row.
     * @return Comparison result as in {@link Comparator#compare(Object, Object)}.
     * @throws IgniteCheckedException If failed.
     */
    protected int compare(int lvl, BPlusIO<L> io, long pageAddr, int idx, L row) throws IgniteCheckedException {
        return compare(io, pageAddr, idx, row);
    }

    /**
     * Get a full detached data row.
     *
     * @param io IO.
     * @param pageAddr Page address.
     * @param idx Index.
     * @return Full detached data row.
     * @throws IgniteCheckedException If failed.
     */
    public final T getRow(BPlusIO<L> io, long pageAddr, int idx) throws IgniteCheckedException {
        return getRow(io, pageAddr, idx, null);
    }

    /**
     * Get data row. Can be called on inner page only if {@link #canGetRowFromInner} is {@code true}.
     *
     * @param io IO.
     * @param pageAddr Page address.
     * @param idx Index.
     * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
     * @return Data row.
     * @throws IgniteCheckedException If failed.
     */
    public abstract T getRow(BPlusIO<L> io, long pageAddr, int idx, Object x) throws IgniteCheckedException;

    /**
     *
     */
    private abstract class AbstractForwardCursor {
        /** */
        long nextPageId;

        /** */
        L lowerBound;

        /** Handle multiple equal rows on lower side. */
        private int lowerShift;

        /** */
        final L upperBound;

        /** Handle multiple equal rows on upper side. */
        private final int upperShift;

        /** Cached value for retrieving diagnosting info in case of failure. */
        public GetCursor getCursor;

        /**
         * @param lowerBound Lower bound.
         * @param upperBound Upper bound.
         * @param lowIncl {@code true} if lower bound is inclusive.
         * @param upIncl {@code true} if upper bound is inclusive.
         */
        AbstractForwardCursor(L lowerBound, L upperBound, boolean lowIncl, boolean upIncl) {
            this.lowerBound = lowerBound;
            this.upperBound = upperBound;

            lowerShift = lowIncl ? -1 : 1;
            upperShift = upIncl ? 1 : -1;
        }

        /**
         *
         */
        abstract void init0();

        /**
         * @param pageAddr Page address.
         * @param io IO.
         * @param startIdx Start index.
         * @param cnt Number of rows in the buffer.
         * @return {@code true} If we were able to fetch rows from this page.
         * @throws IgniteCheckedException If failed.
         */
        abstract boolean fillFromBuffer0(long pageAddr, BPlusIO<L> io, int startIdx, int cnt)
            throws IgniteCheckedException;

        /**
         * @return {@code True} If we have rows to return after reading the next page.
         * @throws IgniteCheckedException If failed.
         */
        abstract boolean reinitialize0() throws IgniteCheckedException;

        /**
         * @param readDone {@code True} if traversed all rows.
         */
        abstract void onNotFound(boolean readDone);

        /**
         * @param pageAddr Page address.
         * @param io IO.
         * @param startIdx Start index.
         * @throws IgniteCheckedException If failed.
         */
        final void init(long pageAddr, BPlusIO<L> io, int startIdx) throws IgniteCheckedException {
            nextPageId = 0;

            init0();

            int cnt = io.getCount(pageAddr);

            // If we see an empty page here, it means that it is an empty tree.
            if (cnt == 0) {
                assert io.getForward(pageAddr) == 0L;

                onNotFound(true);
            }
            else if (!fillFromBuffer(pageAddr, io, startIdx, cnt))
                onNotFound(false);
        }

        /**
         * @param pageAddr Page address.
         * @param io IO.
         * @param cnt Count.
         * @return Adjusted to lower bound start index.
         * @throws IgniteCheckedException If failed.
         */
        final int findLowerBound(long pageAddr, BPlusIO<L> io, int cnt) throws IgniteCheckedException {
            assert io.isLeaf();

            // Compare with the first row on the page.
            int cmp = compare(0, io, pageAddr, 0, lowerBound);

            if (cmp < 0 || (cmp == 0 && lowerShift == 1)) {
                int idx = findInsertionPoint(0, io, pageAddr, 0, cnt, lowerBound, lowerShift);

                assert idx < 0;

                return fix(idx);
            }

            return 0;
        }

        /**
         * @param pageAddr Page address.
         * @param io IO.
         * @param low Start index.
         * @param cnt Number of rows in the buffer.
         * @return Corrected number of rows with respect to upper bound.
         * @throws IgniteCheckedException If failed.
         */
        final int findUpperBound(long pageAddr, BPlusIO<L> io, int low, int cnt) throws IgniteCheckedException {
            assert io.isLeaf();

            // Compare with the last row on the page.
            int cmp = compare(0, io, pageAddr, cnt - 1, upperBound);

            if (cmp > 0 || (cmp == 0 && upperShift == -1)) {
                int idx = findInsertionPoint(0, io, pageAddr, low, cnt, upperBound, upperShift);

                assert idx < 0;

                cnt = fix(idx);

                nextPageId = 0; // The End.
            }

            return cnt;
        }

        /**
         * @param pageAddr Page address.
         * @param io IO.
         * @param startIdx Start index.
         * @param cnt Number of rows in the buffer.
         * @return {@code true} If we were able to fetch rows from this page.
         * @throws IgniteCheckedException If failed.
         */
        private boolean fillFromBuffer(long pageAddr, BPlusIO<L> io, int startIdx, int cnt)
            throws IgniteCheckedException {
            assert io.isLeaf() : io;
            assert cnt != 0 : cnt; // We can not see empty pages (empty tree handled in init).
            assert startIdx >= 0 || startIdx == -1 : startIdx;
            assert cnt >= startIdx;

            checkDestroyed();

            nextPageId = io.getForward(pageAddr);

            return fillFromBuffer0(pageAddr, io, startIdx, cnt);
        }

        /**
         * @throws IgniteCheckedException If failed.
         */
        final void find() throws IgniteCheckedException {
            assert lowerBound != null;

            doFind(getCursor = new GetCursor(lowerBound, lowerShift, this));
        }

        /**
         * @throws IgniteCheckedException If failed.
         * @return {@code True} If we have rows to return after reading the next page.
         */
        private boolean reinitialize() throws IgniteCheckedException {
            // If initially we had no lower bound, then we have to have non-null lastRow argument here
            // (if the tree is empty we never call this method), otherwise we always fallback
            // to the previous lower bound.
            find();

            return reinitialize0();
        }

        /**
         * @param lastRow Last read row (to be used as new lower bound).
         * @return {@code true} If we have rows to return after reading the next page.
         * @throws IgniteCheckedException If failed.
         */
        final boolean nextPage(L lastRow) throws IgniteCheckedException {
            checkDestroyed();

            updateLowerBound(lastRow);

            for (;;) {
                if (nextPageId == 0) {
                    onNotFound(true);

                    return false; // Done.
                }

                long pageId = nextPageId;
                long page = acquirePage(pageId);
                try {
                    long pageAddr = readLock(pageId, page); // Doing explicit null check.

                    // If concurrent merge occurred we have to reinitialize cursor from the last returned row.
                    if (pageAddr == 0L)
                        break;

                    try {
                        BPlusIO<L> io = io(pageAddr);

                        if (fillFromBuffer(pageAddr, io, -1, io.getCount(pageAddr)))
                            return true;

                        // Continue fetching forward.
                    }
                    finally {
                        readUnlock(pageId, page, pageAddr);
                    }
                }
                catch (CorruptedDataStructureException e) {
                    throw e;
                }
                catch (RuntimeException | AssertionError e) {
                    throw corruptedTreeException("Runtime failure on cursor iteration", e, grpId, pageId);
                }
                finally {
                    releasePage(pageId, page);
                }
            }

            // Reinitialize when `next` is released.
            return reinitialize();
        }

        /**
         * @param lower New exact lower bound.
         */
        private void updateLowerBound(L lower) {
            if (lower != null) {
                lowerShift = 1; // Now we have the full row an need to avoid duplicates.
                lowerBound = lower; // Move the lower bound forward for further concurrent merge retries.
            }
        }
    }

    /**
     * Forward cursor.
     */
    private final class ForwardCursor extends AbstractForwardCursor implements GridCursor<T> {
        /** */
        final Object x;

        /** */
        private T[] rows = (T[])EMPTY;

        /** */
        private int row = -1;

        /** */
        private final TreeRowClosure<L, T> c;

        /** */
        private final TreeRowFactory<L, T> rowFactory;

        /**
         * Lower unbound cursor.
         *
         * @param upperBound Upper bound.
         * @param upIncl {@code true} if upper bound is inclusive.
         * @param c Filter closure.
         * @param rowFactory Row factory or (@code null} for default factory.
         * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
         */
        ForwardCursor(L upperBound, boolean upIncl, TreeRowClosure<L, T> c, TreeRowFactory<L, T> rowFactory, Object x) {
            this(null, upperBound, true, upIncl, c, rowFactory, x);
        }

        /**
         * @param lowerBound Lower bound.
         * @param upperBound Upper bound.
         * @param lowIncl {@code true} if lower bound is inclusive.
         * @param upIncl {@code true} if upper bound is inclusive.
         * @param c Filter closure.
         * @param rowFactory Row factory or (@code null} for default factory.
         * @param x Implementation specific argument, {@code null} always means that we need to return full detached data row.
         */
        ForwardCursor(
            L lowerBound,
            L upperBound,
            boolean lowIncl,
            boolean upIncl,
            TreeRowClosure<L, T> c,
            TreeRowFactory<L, T> rowFactory,
            Object x
        ) {
            super(lowerBound, upperBound, lowIncl, upIncl);

            this.c = c;
            this.rowFactory = rowFactory;
            this.x = x;
        }

        /** {@inheritDoc} */
        @Override boolean fillFromBuffer0(long pageAddr, BPlusIO<L> io, int startIdx, int cnt) throws IgniteCheckedException {
            if (startIdx == -1) {
                if (lowerBound != null)
                    startIdx = findLowerBound(pageAddr, io, cnt);
                else
                    startIdx = 0;
            }

            if (upperBound != null && cnt != startIdx)
                cnt = findUpperBound(pageAddr, io, startIdx, cnt);

            int cnt0 = cnt - startIdx;

            if (cnt0 == 0)
                return false;

            if (rows == EMPTY)
                rows = (T[])new Object[cnt0];

            int resCnt = 0;

            for (int idx = startIdx; idx < cnt; idx++) {
                if (c == null || c.apply(BPlusTree.this, io, pageAddr, idx)) {
                    T row = null;

                    if (rowFactory != null)
                        row = rowFactory.create(BPlusTree.this, io, pageAddr, idx);
                    else {
                        if (c != null)
                            row = c.lastRow();

                        if (row == null)
                            row = getRow(io, pageAddr, idx, x);
                    }

                    rows = GridArrays.set(rows, resCnt++, row);
                }
            }

            if (resCnt == 0) {
                rows = (T[])EMPTY;

                return false;
            }

            GridArrays.clearTail(rows, resCnt);

            return true;
        }

        /** {@inheritDoc} */
        @Override boolean reinitialize0() throws IgniteCheckedException {
            return next();
        }

        /** {@inheritDoc} */
        @Override void onNotFound(boolean readDone) {
            if (readDone)
                rows = null;
            else {
                if (rows != EMPTY) {
                    assert rows.length > 0; // Otherwise it makes no sense to create an array.

                    // Fake clear.
                    rows[0] = null;
                }
            }
        }

        /** {@inheritDoc} */
        @Override void init0() {
            row = -1;
        }

        /** {@inheritDoc} */
        @Override public boolean next() throws IgniteCheckedException {
            if (rows == null)
                return false;

            if (++row < rows.length && rows[row] != null) {
                clearLastRow(); // Allow to GC the last returned row.

                return true;
            }

            T lastRow = clearLastRow();

            row = 0;

            return nextPage(lastRow);
        }

        /**
         * @return Cleared last row.
         */
        private T clearLastRow() {
            if (row == 0)
                return null;

            int last = row - 1;

            T r = rows[last];

            assert r != null;

            rows[last] = null;

            return r;
        }

        /** {@inheritDoc} */
        @Override public T get() {
            T r = rows[row];

            assert r != null;

            return r;
        }
    }

    /**
     * Page handler for basic {@link Get} operation.
     */
    private abstract class GetPageHandler<G extends Get> extends PageHandler<G, Result> {
        /** {@inheritDoc} */
        @Override public Result run(int cacheId, long pageId, long page, long pageAddr, PageIO iox, Boolean walPlc,
            G g, int lvl, IoStatisticsHolder statHolder) throws IgniteCheckedException {
            assert PageIO.getPageId(pageAddr) == pageId;

            // If we've passed the check for correct page ID, we can safely cast.
            BPlusIO<L> io = (BPlusIO<L>)iox;

            // In case of intersection with inner replace in remove operation
            // we need to restart our operation from the tree root.
            if (lvl == 0 && g.rmvId < io.getRemoveId(pageAddr))
                return RETRY_ROOT;

            return run0(pageId, page, pageAddr, io, g, lvl);
        }

        /**
         * @param pageId Page ID.
         * @param page Page pointer.
         * @param pageAddr Page address.
         * @param io IO.
         * @param g Operation.
         * @param lvl Level.
         * @return Result code.
         * @throws IgniteCheckedException If failed.
         */
        protected abstract Result run0(long pageId, long page, long pageAddr, BPlusIO<L> io, G g, int lvl)
            throws IgniteCheckedException;

        /** {@inheritDoc} */
        @Override public boolean releaseAfterWrite(int cacheId, long pageId, long page, long pageAddr, G g, int lvl) {
            return g.canRelease(pageId, lvl);
        }
    }

    /**
     *
     */
    private static class TreeMetaData {
        /** */
        final int rootLvl;

        /** */
        final long rootId;

        /**
         * @param rootLvl Root level.
         * @param rootId Root page ID.
         */
        TreeMetaData(int rootLvl, long rootId) {
            this.rootLvl = rootLvl;
            this.rootId = rootId;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(TreeMetaData.class, this);
        }
    }

    /**
     * Operation result.
     */
    public enum Result {
        /** */
        GO_DOWN,

        /** */
        GO_DOWN_X,

        /** */
        FOUND,

        /** */
        NOT_FOUND,

        /** */
        RETRY,

        /** */
        RETRY_ROOT
    }

    /**
     * Four state boolean.
     */
    enum Bool {
        /** */
        FALSE,

        /** */
        TRUE,

        /** */
        READY,

        /** */
        DONE
    }

    /**
     * A generic visitor-style interface for performing filtering/modifications/miscellaneous operations on the tree.
     */
    public interface TreeRowClosure<L, T extends L> {
        /**
         * Performs inspection or operation on a specified row and returns true if this row is
         * required or matches or /operation successful (depending on the context).
         *
         * @param tree The tree.
         * @param io Th tree IO object.
         * @param pageAddr The page address.
         * @param idx The item index.
         * @return {@code True} if the item passes the predicate.
         * @throws IgniteCheckedException If failed.
         */
        public boolean apply(BPlusTree<L, T> tree, BPlusIO<L> io, long pageAddr, int idx)
            throws IgniteCheckedException;

        /**
         * Optional operation to provide access to the last analyzed row.
         * Can be used to optimize chained filters and omit several tree reads.
         *
         * @return Last row that was analyzed or {@code null}.
         */
        public default T lastRow() {
            return null;
        }
    }

    /**
     * Row factory from page memory.
     */
    public interface TreeRowFactory<L, T extends L> {
        /**
         * Creates row.
         *
         * @param tree The tree.
         * @param io The tree IO object.
         * @param pageAddr The page address.
         * @param idx The item index.
         * @return Created index row.
         * @throws IgniteCheckedException If failed.
         */
        public T create(BPlusTree<L, T> tree, BPlusIO<L> io, long pageAddr, int idx)
            throws IgniteCheckedException;
    }

    /**
     * @return Return number of retries.
     */
    protected int getLockRetries() {
        return LOCK_RETRIES;
    }

    /**
     * @param pageId Page ID.
     * @return Page absolute pointer.
     * @throws IgniteCheckedException If failed.
     */
    protected final long acquirePage(long pageId) throws IgniteCheckedException {
        return acquirePage(pageId, statisticsHolder());
    }

    /**
     * @param pageId Page ID.
     * @param h Handler.
     * @param arg Argument.
     * @param intArg Argument of type {@code int}.
     * @param lockFailed Result in case of lock failure due to page recycling.
     * @return Handler result.
     * @throws IgniteCheckedException If failed.
     */
    protected final <X, R> R read(
        long pageId,
        PageHandler<X, R> h,
        X arg,
        int intArg,
        R lockFailed) throws IgniteCheckedException {
        return read(pageId, h, arg, intArg, lockFailed, statisticsHolder());
    }

    /**
     * @param pageId Page ID.
     * @param page Page pointer.
     * @param h Handler.
     * @param arg Argument.
     * @param intArg Argument of type {@code int}.
     * @param lockFailed Result in case of lock failure due to page recycling.
     * @return Handler result.
     * @throws IgniteCheckedException If failed.
     */
    protected final <X, R> R read(
        long pageId,
        long page,
        PageHandler<X, R> h,
        X arg,
        int intArg,
        R lockFailed) throws IgniteCheckedException {
        return read(pageId, page, h, arg, intArg, lockFailed, statisticsHolder());
    }

    /**
     * @return Statistics holder to track IO operations.
     */
    protected IoStatisticsHolder statisticsHolder() {
        return IoStatisticsHolderNoOp.INSTANCE;
    }

    /**
     * PageIds converter with empty check.
     *
     * @param empty Flag for empty array result.
     * @param pages Pages supplier.
     * @return Array of page ids.
     */
    private long[] pages(boolean empty, Supplier<long[]> pages) {
        return empty ? GridLongList.EMPTY_ARRAY : pages.get();
    }

    /**
     * Construct the exception and invoke failure processor.
     *
     * @param msg Message.
     * @param cause Cause.
     * @param grpId Group id.
     * @param pageIds Pages ids.
     * @return New CorruptedTreeException instance.
     */
    protected CorruptedTreeException corruptedTreeException(String msg, Throwable cause, int grpId, long... pageIds) {
        CorruptedTreeException e = new CorruptedTreeException(msg, cause, grpName, grpId, pageIds);

        processFailure(FailureType.CRITICAL_ERROR, e);

        return e;
    }

    /**
     * Processes failure with failure processor.
     *
     * @param failureType Failure type.
     * @param e Exception.
     */
    protected void processFailure(FailureType failureType, Throwable e) {
        if (failureProcessor != null && !suspendFailureDiagnostic.get())
            failureProcessor.process(new FailureContext(failureType, e));
    }

    /**
     * Returns meta page id.
     *
     * @return Meta page id.
     */
    public long getMetaPageId() {
        return metaPageId;
    }

    /**
     * Create an error message when reaching the maximum
     * number of repetitions to capture a lock in the B+Tree.
     *
     * @param op Operation name, for example: GET, PUT.
     * @return Error message.
     */
    protected String lockRetryErrorMessage(String op) {
        return "Maximum number of retries " +
            getLockRetries() + " reached for " + op + " operation " +
            "(the tree may be corrupted). Increase " + IGNITE_BPLUS_TREE_LOCK_RETRIES + " system property " +
            "if you regularly see this message (current value is " + getLockRetries() + "). " +
            getClass().getSimpleName() + " [grpName=" + grpName + ", treeName=" + name() + ", metaPageId=" +
            U.hexLong(metaPageId) + "].";
    }

    /**
     * The operation of deleting a range of values.
     * <p>
     * Performs the removal of several elements from the leaf at once.
     */
    protected class RemoveRange extends Remove {
        /** Upper bound. */
        private final L upper;

        /** Lower bound. */
        private final L lower;

        /** List of removed rows. */
        private final List<L> removedRows;

        /** The number of remaining rows to remove ({@code -1}, if the limit hasn't been specified). */
        private int remaining;

        /** Flag indicating that no more rows were found from the specified range. */
        private boolean completed;

        /** The index of the highest row found on the page from the specified range. */
        private int highIdx;

        /**
         * @param lower Lower bound (inclusive).
         * @param upper Upper bound (inclusive).
         * @param needOld {@code True} If need return old value.
         * @param x Implementation specific argument, {@code null} always means that we need a full detached data row.
         * @param limit Limit of processed entries by single call, {@code 0} or negative value for no limit.
         */
        protected RemoveRange(L lower, L upper, boolean needOld, Object x, int limit) {
            super(lower, needOld, x, rmvRangeFromLeaf);

            this.lower = lower;
            this.upper = upper;

            remaining = limit <= 0 ? -1 : limit;
            removedRows = needOld ? new ArrayList<>() : null;
        }

        /**
         * @return {@code True} if operation is completed.
         */
        private boolean isDone() {
            return completed || remaining == 0;
        }

        /** {@inheritDoc} */
        @Override boolean notFound(BPlusIO<L> io, long pageAddr, int idx, int lvl) throws IgniteCheckedException {
            if (lvl != 0)
                return false;

            assert !completed;
            assert tail == null;

            // If the lower bound is higher than the rightmost item, or if this item is outside the given range,
            // then the search is completed - there are no items from the given range.
            if (idx == io.getCount(pageAddr) || compare(io, pageAddr, idx, upper) > 0)
                completed = true;

            return true;
        }

        /** {@inheritDoc} */
        @Override protected boolean ceil() {
            return !completed;
        }

        /** {@inheritDoc} */
        @Override protected void removeDataRowFromLeaf(
            long pageId,
            long page,
            long pageAddr,
            Boolean walPlc,
            BPlusIO<L> io,
            int cnt,
            int idx
        ) throws IgniteCheckedException {
            assert io.isLeaf() : "inner";
            assert !isRemoved() : "already removed";
            assert remaining > 0 || remaining == -1 : remaining;

            // It's just a marker that we finished with this leaf-page.
            rmvd = (T)Boolean.TRUE;

            // We had an exact match of the upper bound on this page or the upper bound is lower than the last item.
            if (highIdx >= 0 || (highIdx = fix(highIdx)) < cnt - 1)
                completed = true;

            assert idx >= 0 && idx <= highIdx && highIdx < cnt : "low=" + idx + ", high=" + highIdx + ", cnt=" + cnt;

            // Delete from right to left to reduce the number of items moved during the delete operation.
            for (int i = highIdx; i >= idx; i--) {
                if (needOld)
                    removedRows.add(getRow(io, pageAddr, i, x));

                doRemove(pageId, page, pageAddr, walPlc, io, cnt - highIdx + i, i);

                if (remaining != -1)
                    --remaining;
            }

            if (needOld) {
                // Reverse the order of added elements.
                int len = highIdx - idx + 1;
                int off = removedRows.size() - len;

                for (int i = off, j = removedRows.size() - 1; i < j; i++, j--)
                    removedRows.set(i, removedRows.set(j, removedRows.get(i)));
            }

            assert isRemoved();
        }

        /** {@inheritDoc} */
        @Override protected boolean releaseForRetry(Tail<L> t) {
            // Reset search row to lower bound.
            if (t.lvl <= 1 && needReplaceInner)
                row = lower;

            return super.releaseForRetry(t);
        }

        /** {@inheritDoc} */
        @Override protected Result finish(Result res) {
            if (isDone())
                return super.finish(res);

            assert tail == null;

            // Continue operation - restart from the root.
            row = lower;
            needReplaceInner = false;
            needMergeEmptyBranch = FALSE;
            rmvd = null;

            // Reset retries counter.
            lockRetriesCnt = getLockRetries();

            return RETRY;
        }
    }
}
