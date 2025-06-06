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

package org.apache.ignite.internal.util;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.NoSuchElementException;
import org.apache.ignite.internal.IgniteCodeGeneratingFail;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.SB;
import org.apache.ignite.plugin.extensions.communication.Message;
import org.apache.ignite.plugin.extensions.communication.MessageReader;
import org.apache.ignite.plugin.extensions.communication.MessageWriter;

/**
 * Minimal list API to work with primitive longs. This list exists
 * to avoid boxing/unboxing when using standard list from Java.
 */
@IgniteCodeGeneratingFail
public class GridLongList implements Message, Externalizable {
    /** */
    private static final long serialVersionUID = 0L;

    /** Empty array. */
    public static final long[] EMPTY_ARRAY = new long[0];

    /** */
    private long[] arr;

    /** */
    private int idx;

    /**
     *
     */
    public GridLongList() {
        // No-op.
    }

    /**
     * @param size Size.
     */
    public GridLongList(int size) {
        arr = new long[size];
        // idx = 0
    }

    /**
     * @param arr Array.
     */
    public GridLongList(long[] arr) {
        this.arr = arr;

        idx = arr.length;
    }

    /**
     * @param vals Values.
     * @return List from values.
     */
    public static GridLongList asList(long... vals) {
        if (F.isEmpty(vals))
            return new GridLongList();

        return new GridLongList(vals);
    }

    /**
     * @param arr Array.
     * @param size Size.
     */
    private GridLongList(long[] arr, int size) {
        this.arr = arr;
        idx = size;
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (!(o instanceof GridLongList))
            return false;

        GridLongList that = (GridLongList)o;

        if (idx != that.idx)
            return false;

        if (idx == 0 || arr == that.arr)
            return true;

        for (int i = 0; i < idx; i++) {
            if (arr[i] != that.arr[i])
                return false;
        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        int res = 1;

        for (int i = 0; i < idx; i++) {
            long element = arr[i];
            int elementHash = (int)(element ^ (element >>> 32));
            res = 31 * res + elementHash;
        }

        return res;
    }

    /**
     * @param l List to add all elements of.
     */
    public void addAll(GridLongList l) {
        assert l != null;

        if (l.isEmpty())
            return;

        if (arr == null)
            arr = new long[4];

        int len = arr.length;

        while (len < idx + l.size())
            len <<= 1;

        arr = Arrays.copyOf(arr, len);

        System.arraycopy(l.arr, 0, arr, idx, l.size());

        idx += l.size();
    }

    /**
     * Add element to this array.
     * @param x Value.
     */
    public void add(long x) {
        if (arr == null)
            arr = new long[4];
        else if (arr.length == idx)
            arr = Arrays.copyOf(arr, Math.max(1, arr.length) << 1);

        arr[idx++] = x;
    }

    /**
     * Clears the list.
     */
    public void clear() {
        idx = 0;
    }

    /**
     * Removes and returns the last element of the list. Complementary method to {@link #add(long)} for stack like usage.
     *
     * @return Removed element.
     * @throws NoSuchElementException If the list is empty.
     */
    public long remove() throws NoSuchElementException {
        if (idx == 0)
            throw new NoSuchElementException();

        return arr[--idx];
    }

    /**
     * Returns (possibly reordered) copy of this list, excluding all elements of given list.
     *
     * @param l List of elements to remove.
     * @return New list without all elements from {@code l}.
     */
    public GridLongList copyWithout(GridLongList l) {
        assert l != null;

        if (idx == 0)
            return new GridLongList();

        if (l.idx == 0)
            return new GridLongList(Arrays.copyOf(arr, idx));

        long[] newArr = Arrays.copyOf(arr, idx);
        int newIdx = idx;

        for (int i = 0; i < l.size(); i++) {
            long rmVal = l.get(i);

            for (int j = 0; j < newIdx; j++) {
                if (newArr[j] == rmVal) {

                    while (newIdx > 0 && newArr[newIdx - 1] == rmVal)
                        newIdx--;

                    if (newIdx > 0) {
                        newArr[j] = newArr[newIdx - 1];
                        newIdx--;
                    }
                }
            }
        }

        return new GridLongList(newArr, newIdx);
    }

    /**
     * @param i Index.
     * @return Value.
     */
    public long get(int i) {
        assert i < idx;

        return arr[i];
    }

    /**
     * @return Size.
     */
    public int size() {
        return idx;
    }

    /**
     * @return {@code True} if this list has no elements.
     */
    public boolean isEmpty() {
        return idx == 0;
    }

    /**
     * @param l Element to find.
     * @return {@code True} if found.
     */
    public boolean contains(long l) {
        for (int i = 0; i < idx; i++) {
            if (arr[i] == l)
                return true;
        }

        return false;
    }

    /**
     * @param size New size.
     * @param last If {@code true} the last elements will be removed, otherwise the first.
     */
    public void truncate(int size, boolean last) {
        assert size >= 0 && size <= idx;

        if (size == idx)
            return;

        if (!last && size != 0)
            System.arraycopy(arr, idx - size, arr, 0, size);

        idx = size;
    }

    /**
     * Removes element by given index.
     *
     * @param i Index.
     * @return Removed value.
     */
    public long removeIndex(int i) {
        assert i < idx : i;

        long res = arr[i];

        if (i == idx - 1) { // Last element.
            idx = i;
        }
        else {
            System.arraycopy(arr, i + 1, arr, i, idx - i - 1);
            idx--;
        }

        return res;
    }

    /**
     * Removes value from this list.
     *
     * @param startIdx Index to begin search with.
     * @param val Value.
     * @return Index of removed value if the value was found and removed or {@code -1} otherwise.
     */
    public int removeValue(int startIdx, long val) {
        assert startIdx >= 0;

        for (int i = startIdx; i < idx; i++) {
            if (arr[i] == val) {
                removeIndex(i);

                return i;
            }
        }

        return -1;
    }

    /**
     * @return Array copy.
     */
    public long[] array() {
        if (arr == null)
            return EMPTY_ARRAY;

        long[] res = new long[idx];

        System.arraycopy(arr, 0, res, 0, idx);

        return res;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeInt(idx);

        for (int i = 0; i < idx; i++)
            out.writeLong(arr[i]);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException {
        idx = in.readInt();

        arr = new long[idx];

        for (int i = 0; i < idx; i++)
            arr[i] = in.readLong();
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        SB b = new SB("[");

        for (int i = 0; i < idx; i++) {
            if (i != 0)
                b.a(',');

            b.a(arr[i]);
        }

        b.a(']');

        return S.toString(GridLongList.class, this, "arr", b);
    }

    /**
     * Sorts this list.
     * Use {@code copy().sort()} if you need a defensive copy.
     *
     * @return {@code this} For chaining.
     */
    public GridLongList sort() {
        if (idx > 1)
            Arrays.sort(arr, 0, idx);

        return this;
    }

    /** {@inheritDoc} */
    @Override public void onAckReceived() {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public boolean writeTo(ByteBuffer buf, MessageWriter writer) {
        writer.setBuffer(buf);

        if (!writer.isHeaderWritten()) {
            if (!writer.writeHeader(directType()))
                return false;

            writer.onHeaderWritten();
        }

        switch (writer.state()) {
            case 0:
                if (!writer.writeLongArray(arr, idx))
                    return false;

                writer.incrementState();

            case 1:
                if (!writer.writeInt(idx))
                    return false;

                writer.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public boolean readFrom(ByteBuffer buf, MessageReader reader) {
        reader.setBuffer(buf);

        switch (reader.state()) {
            case 0:
                arr = reader.readLongArray();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

            case 1:
                idx = reader.readInt();

                if (!reader.isLastRead())
                    return false;

                reader.incrementState();

        }

        return true;
    }

    /** {@inheritDoc} */
    @Override public short directType() {
        return 85;
    }
}
