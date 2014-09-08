/*
 *  Copyright 2014 Brian S O'Neill
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.cojen.tupl;

import java.io.Closeable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Page cache which uses direct buffers and very few Java objects, eliminating garbage
 * collection overhead. Caller should scramble page ids to reduce hash collisions.
 *
 * @author Brian S O'Neill
 */
class DirectPageCache extends Latch implements PageCache {
    /*
      Node format:

      long: pageId
      int:  lessRecentPtr
      int:  moreRecentPtr
      int:  chainNextPtr
    */

    private static final int PAGE_ID_FIELD         = 0;
    private static final int LESS_RECENT_PTR_FIELD = 2;
    private static final int MORE_RECENT_PTR_FIELD = 3;
    private static final int CHAIN_NEXT_PTR_FIELD  = 4;

    private static final int NODE_SIZE_IN_INTS = CHAIN_NEXT_PTR_FIELD + 1;

    private final long mZeroId;
    private final int[] mHashTable;

    private ByteBuffer mNodesByteBuffer;
    private IntBuffer mNodes;
    private ByteBuffer mData;

    private int mLeastRecentPtr;
    private int mMostRecentPtr;

    /**
     * @param capacity capacity in bytes
     * @param zeroId identifier used for page zero (might be scrambled)
     */
    DirectPageCache(int capacity, int pageSize, long zeroId) {
        int entryCount = Math.max(2, capacity / (24 + pageSize));

        mZeroId = zeroId;
        mHashTable = new int[entryCount];

        acquireExclusive();

        mNodesByteBuffer = ByteBuffer
            .allocateDirect(entryCount * (NODE_SIZE_IN_INTS * 4))
            .order(ByteOrder.nativeOrder());
        mNodes = mNodesByteBuffer.asIntBuffer();
        mData = ByteBuffer.allocateDirect(entryCount * pageSize);

        // Initalize the nodes, all linked together.
        int ptr = 0;
        for (; ptr < entryCount * NODE_SIZE_IN_INTS; ptr += NODE_SIZE_IN_INTS) {
            setPageId(mNodes, ptr + PAGE_ID_FIELD, zeroId);
            mNodes.put(ptr + LESS_RECENT_PTR_FIELD, ptr - NODE_SIZE_IN_INTS);
            mNodes.put(ptr + MORE_RECENT_PTR_FIELD, ptr + NODE_SIZE_IN_INTS);
            mNodes.put(ptr + CHAIN_NEXT_PTR_FIELD, -1);
        }

        mLeastRecentPtr = 0;
        mMostRecentPtr = ptr - NODE_SIZE_IN_INTS;

        for (int i=0; i<mHashTable.length; i++) {
            mHashTable[i] = -1;
        }

        releaseExclusive();
    }

    @Override
    public void add(final long pageId, final byte[] page) {
        acquireExclusive();

        final IntBuffer nodes = mNodes;
        if (nodes == null) {
            // Closed.
            releaseExclusive();
            return;
        }

        // Select the least recent entry and move to most recent.
        final int ptr = mLeastRecentPtr;
        mLeastRecentPtr = nodes.get(ptr + MORE_RECENT_PTR_FIELD);
        nodes.put(mMostRecentPtr + MORE_RECENT_PTR_FIELD, ptr);
        nodes.put(ptr + LESS_RECENT_PTR_FIELD, mMostRecentPtr);
        mMostRecentPtr = ptr;

        // Copy page into the data buffer.
        mData.position((ptr / NODE_SIZE_IN_INTS) * page.length);
        mData.put(page);

        // Evict old entry from hashtable.
        final int[] hashTable = mHashTable;
        {
            final long evictedPageId = getPageId(nodes, ptr);
            final int index = hash(evictedPageId) % hashTable.length;

            int entryPtr = hashTable[index];
            if (entryPtr >= 0) {
                int prevPtr = -1;
                while (true) {
                    final int chainNextPtr = nodes.get(entryPtr + CHAIN_NEXT_PTR_FIELD);
                    if (getPageId(nodes, entryPtr) == evictedPageId) {
                        if (prevPtr < 0) {
                            hashTable[index] = chainNextPtr;
                        } else {
                            nodes.put(prevPtr + CHAIN_NEXT_PTR_FIELD, chainNextPtr);
                        }
                    }

                    if (chainNextPtr < 0) {
                        break;
                    }

                    prevPtr = entryPtr;
                    entryPtr = chainNextPtr;
                }
            }
        }

        // Add new entry into the hashtable.
        final int index = hash(pageId) % hashTable.length;
        nodes.put(ptr + CHAIN_NEXT_PTR_FIELD, hashTable[index]);
        hashTable[index] = ptr;
        setPageId(nodes, ptr, pageId);

        releaseExclusive();
    }

    @Override
    public boolean remove(final long pageId, final byte[] page) {
        acquireExclusive();

        final IntBuffer nodes = mNodes;
        if (nodes == null) {
            // Closed.
            releaseExclusive();
            return false;
        }

        final int[] hashTable = mHashTable;
        final int index = hash(pageId) % hashTable.length;

        int ptr = hashTable[index];
        if (ptr >= 0) {
            int prevPtr = -1;
            while (true) {
                final int chainNextPtr = nodes.get(ptr + CHAIN_NEXT_PTR_FIELD);
                if (getPageId(nodes, ptr) == pageId) {
                    // Found it.

                    // Copy data buffer into the page.
                    mData.position((ptr / NODE_SIZE_IN_INTS) * page.length);
                    mData.get(page);

                    if (ptr != mLeastRecentPtr) {
                        // Move to least recent.
                        int lessPtr = nodes.get(ptr + LESS_RECENT_PTR_FIELD);
                        if (ptr == mMostRecentPtr) {
                            mMostRecentPtr = lessPtr;
                        } else {
                            int morePtr = nodes.get(ptr + MORE_RECENT_PTR_FIELD);
                            nodes.put(lessPtr + MORE_RECENT_PTR_FIELD, morePtr);
                            nodes.put(morePtr + LESS_RECENT_PTR_FIELD, lessPtr);
                        }
                        nodes.put(mLeastRecentPtr + LESS_RECENT_PTR_FIELD, ptr);
                        nodes.put(ptr + MORE_RECENT_PTR_FIELD, mLeastRecentPtr);
                        mLeastRecentPtr = ptr;
                    }

                    // Remove from hashtable.
                    if (prevPtr < 0) {
                        hashTable[index] = chainNextPtr;
                    } else {
                        nodes.put(prevPtr + CHAIN_NEXT_PTR_FIELD, chainNextPtr);
                    }

                    // Zero the page id.
                    setPageId(nodes, ptr, mZeroId);

                    releaseExclusive();
                    return true;
                }

                if (chainNextPtr < 0) {
                    break;
                }

                prevPtr = ptr;
                ptr = chainNextPtr;
            }
        }

        releaseExclusive();
        return false;
    }

    @Override
    public int capacity() {
        return mHashTable.length;
    }

    @Override
    public void close() {
        acquireExclusive();

        if (mNodes == null) {
            return;
        }

        try {
            Utils.delete(mNodesByteBuffer);
            Utils.delete(mData);
        } finally {
            mNodes = null;
            mData = null;
        }

        releaseExclusive();
    }

    private static long getPageId(IntBuffer nodes, int iptr) {
        return (nodes.get(iptr + PAGE_ID_FIELD) & 0xffffffffL)
            | (((long) nodes.get(iptr + (PAGE_ID_FIELD + 1))) << 32);
    }

    private static void setPageId(IntBuffer nodes, int iptr, long pageId) {
        nodes.put(iptr + PAGE_ID_FIELD, (int) pageId);
        nodes.put(iptr + (PAGE_ID_FIELD + 1), (int) (pageId >>> 32));
    }

    private static int hash(long pageId) {
        return (((int) pageId) ^ ((int) (pageId >>> 32))) & 0x7fffffff;
    }
}