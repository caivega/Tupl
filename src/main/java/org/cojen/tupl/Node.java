/*
 *  Copyright 2011-2013 Brian S O'Neill
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

import java.io.IOException;

import static org.cojen.tupl.PageOps.*;

import static org.cojen.tupl.Utils.EMPTY_BYTES;
import static org.cojen.tupl.Utils.closeOnFailure;
import static org.cojen.tupl.Utils.compareUnsigned;
import static org.cojen.tupl.Utils.rethrow;

/**
 * Node within a B-tree, undo log, or a large value fragment.
 *
 * @author Brian S O'Neill
 */
@SuppressWarnings("serial")
final class Node extends Latch implements DatabaseAccess {
    // Note: Changing these values affects how the Database class handles the
    // commit flag. It only needs to flip bit 0 to switch dirty states.
    static final byte
        CACHED_CLEAN     = 0, // 0b0000
        CACHED_DIRTY_0   = 2, // 0b0010
        CACHED_DIRTY_1   = 3; // 0b0011

    /*
      Node type encoding strategy:

      bits 7..4: major type   0010 (fragment), 0100 (undo log),
                              0110 (internal), 0111 (bottom internal), 1000 (leaf)
      bits 3..1: sub type     for leaf: x0x (normal)
                              for internal: x1x (6 byte child pointer + 2 byte count), x0x (unused)
                              for both: bit 1 is set if low extremity, bit 3 for high extremity
      bit  0:    endianness   0 (little), 1 (big)

      TN == Tree Node

      Note that leaf type is always negative. If type encoding changes, the isLeaf and
      isInternal methods might need to be updated.

     */

    static final byte
        TYPE_NONE     = 0,
        TYPE_FRAGMENT = (byte) 0x20, // 0b0010_000_0
        TYPE_UNDO_LOG = (byte) 0x40, // 0b0100_000_0
        TYPE_TN_IN    = (byte) 0x64, // 0b0110_010_0
        TYPE_TN_BIN   = (byte) 0x74, // 0b0111_010_0
        TYPE_TN_LEAF  = (byte) 0x80; // 0b1000_000_0

    static final byte LOW_EXTREMITY = 0x02, HIGH_EXTREMITY = 0x08;

    // Tree node header size.
    static final int TN_HEADER_SIZE = 12;

    static final int STUB_ID = 1;

    static final int ENTRY_FRAGMENTED = 0x40;

    // Usage list this node belongs to.
    final NodeUsageList mUsageList;

    // Links within usage list, guarded by NodeUsageList.
    Node mMoreUsed; // points to more recently used node
    Node mLessUsed; // points to less recently used node

    // Links within dirty list, guarded by PageAllocator.
    Node mNextDirty;
    Node mPrevDirty;

    /*
      Nodes define the contents of Trees and UndoLogs. All node types start
      with a two byte header.

      +----------------------------------------+
      | byte:   node type                      |  header
      | byte:   reserved (must be 0)           |
      -                                        -

      There are two types of tree nodes, having a similar structure and
      supporting a maximum page size of 65536 bytes. The ushort type is an
      unsigned byte pair, and the ulong type is eight bytes. All multibyte
      types are little endian encoded.

      +----------------------------------------+
      | byte:   node type                      |  header
      | byte:   reserved (must be 0)           |
      | ushort: garbage in segments            |
      | ushort: pointer to left segment tail   |
      | ushort: pointer to right segment tail  |
      | ushort: pointer to search vector start |
      | ushort: pointer to search vector end   |
      +----------------------------------------+
      | left segment                           |
      -                                        -
      |                                        |
      +----------------------------------------+
      | free space                             | <-- left segment tail (exclusive)
      -                                        -
      |                                        |
      +----------------------------------------+
      | search vector                          | <-- search vector start (inclusive)
      -                                        -
      |                                        | <-- search vector end (inclusive)
      +----------------------------------------+
      | free space                             |
      -                                        -
      |                                        | <-- right segment tail (exclusive)
      +----------------------------------------+
      | right segment                          |
      -                                        -
      |                                        |
      +----------------------------------------+

      The left and right segments are used for allocating variable sized entries, and the
      tail points to the next allocation. Allocations are made toward the search vector
      such that the free space before and after the search vector remain the roughly the
      same. The search vector may move if a contiguous allocation is not possible on
      either side.

      The search vector is used for performing a binary search against keys. The keys are
      variable length and are stored anywhere in the left and right segments. The search
      vector itself must point to keys in the correct order, supporting binary search. The
      search vector is also required to be aligned to an even address, contain fixed size
      entries, and it never has holes. Adding or removing entries from the search vector
      requires entries to be shifted. The shift operation can be performed from either
      side, but the smaller shift is always chosen as a performance optimization.
      
      Garbage refers to the amount of unused bytes within the left and right allocation
      segments. Garbage accumulates when entries are deleted and updated from the
      segments. Segments are not immediately shifted because the search vector would also
      need to be repaired. A compaction operation reclaims garbage by rebuilding the
      segments and search vector. A copying garbage collection algorithm is used for this.

      The compaction implementation allocates all surviving entries in the left segment,
      leaving an empty right segment. There is no requirement that the segments be
      balanced -- this only applies to the free space surrounding the search vector.

      Leaf nodes support variable length keys and values, encoded as a pair, within the
      segments. Entries in the search vector are ushort pointers into the segments. No
      distinction is made between the segments because the pointers are absolute.

      Entries start with a one byte key header:

      0b0xxx_xxxx: key is 1..128 bytes
      0b1fxx_xxxx: key is 0..16383 bytes

      For keys 1..128 bytes in length, the length is defined as (header + 1). For
      keys 0..16383 bytes in length, a second header byte is used. The second byte is
      unsigned, and the length is defined as (((header & 0x3f) << 8) | header2). The key
      contents immediately follow the header byte(s).

      When the 'f' bit is zero, the entry is a normal key. Very large keys are stored in a
      fragmented fashion, which is also used by large values. The encoding format is defined by
      Database.fragment.

      The value follows the key, and its header encodes the entry length:

      0b0xxx_xxxx: value is 0..127 bytes
      0b1f0x_xxxx: value/entry is 1..8192 bytes
      0b1f10_xxxx: value/entry is 1..1048576 bytes
      0b1111_1111: ghost value (null)

      When the 'f' bit is zero, the entry is a normal value. Otherwise, it is a
      fragmented value, defined by Database.fragment.

      For entries 1..8192 bytes in length, a second header byte is used. The
      length is then defined as ((((h0 & 0x1f) << 8) | h1) + 1). For larger
      entries, the length is ((((h0 & 0x0f) << 16) | (h1 << 8) | h2) + 1).
      Node limit is currently 65536 bytes, which limits maximum entry length.

      The "values" for internal nodes are actually identifiers for child nodes. The number
      of child nodes is always one more than the number of keys. For this reason, the
      key-value format used by leaf nodes cannot be applied to internal nodes. Also, the
      identifiers are always a fixed length, ulong type.

      Child node identifiers are encoded immediately following the search vector. Free space
      management must account for this, treating it as an extension to the search vector.

     */

    // Raw contents of node.
    /*P*/ byte[] mPage;

    // Id is often read without acquiring latch, although in most cases, it
    // doesn't need to be volatile. This is because a double check with the
    // latch held is always performed. So-called double-checked locking doesn't
    // work with object initialization, but it's fine with primitive types.
    // When nodes are evicted, the write operation must complete before the id
    // is re-assigned. For this reason, the id is volatile. A memory barrier
    // between the write and re-assignment should work too.
    volatile long mId;

    byte mCachedState;

    /*P*/ // {
    // Entries from header, available as fields for quick access.
    private byte mType;
    private int mGarbage;
    private int mLeftSegTail;
    private int mRightSegTail;
    private int mSearchVecStart;
    private int mSearchVecEnd;
    /*P*/ // }

    // Next in NodeMap collision chain or lower node in UndoLog.
    Node mNodeChainNext;

    // Linked stack of TreeCursorFrames bound to this Node.
    transient TreeCursorFrame mLastCursorFrame;

    // Set by a partially completed split.
    transient Split mSplit;

    Node(NodeUsageList usageList, int pageSize) {
        this(usageList, p_calloc(pageSize));
    }

    private Node(NodeUsageList usageList, /*P*/ byte[] page) {
        mUsageList = usageList;
        mPage = page;
    }

    /**
     * Must be called when object is no longer referenced.
     */
    void delete() {
        acquireExclusive();
        /*P*/ byte[] page = mPage;
        if (page != p_emptyTreePage()) {
            p_delete(page);
            closeRoot();
        }
        releaseExclusive();
    }

    @Override
    public Database getDatabase() {
        return mUsageList.mDatabase;
    }

    void asEmptyRoot() {
        mId = 0;
        mCachedState = CACHED_CLEAN;
        type((byte) (TYPE_TN_LEAF | LOW_EXTREMITY | HIGH_EXTREMITY));
        clearEntries();
    }

    void asTrimmedRoot() {
        type((byte) (TYPE_TN_LEAF | LOW_EXTREMITY | HIGH_EXTREMITY));
        clearEntries();
    }

    /**
     * Close the root node when closing a tree.
     */
    void closeRoot() {
        // Prevent node from being marked dirty.
        mId = STUB_ID;
        mCachedState = CACHED_CLEAN;
        mPage = p_emptyTreePage();
        readFields();
    }

    Node cloneNode() {
        Node newNode = new Node(mUsageList, mPage);
        newNode.mId = mId;
        newNode.mCachedState = mCachedState;
        /*P*/ // {
        newNode.type(type());
        newNode.garbage(garbage());
        newNode.leftSegTail(leftSegTail());
        newNode.rightSegTail(rightSegTail());
        newNode.searchVecStart(searchVecStart());
        newNode.searchVecEnd(searchVecEnd());
        /*P*/ // }
        return newNode;
    }

    private void clearEntries() {
        garbage(0);
        leftSegTail(TN_HEADER_SIZE);
        int pageSize = p_length(mPage);
        rightSegTail(pageSize - 1);
        // Search vector location must be even.
        searchVecStart((TN_HEADER_SIZE + ((pageSize - TN_HEADER_SIZE) >> 1)) & ~1);
        searchVecEnd(searchVecStart() - 2); // inclusive
    }

    /**
     * Indicate that a non-root node is most recently used. Root node is not managed in usage
     * list and cannot be evicted. Caller must hold any latch on node. Latch is never released
     * by this method, even if an exception is thrown.
     */
    void used() {
        mUsageList.used(this);
    }

    /**
     * Indicate that node is least recently used, allowing it to be recycled immediately
     * without evicting another node. Node must be latched by caller, which is always released
     * by this method.
     */
    void unused() {
        mUsageList.unused(this);
    }

    /**
     * Allow a Node which was allocated as unevictable to be evictable, starting off as the
     * most recently used.
     */
    void makeEvictable() {
        mUsageList.makeEvictable(this);
    }

    /**
     * Allow a Node which was allocated as unevictable to be evictable, as the least recently
     * used.
     */
    void makeEvictableNow() {
        mUsageList.makeEvictableNow(this);
    }

    /**
     * Allow a Node which was allocated as evictable to be unevictable.
     */
    void makeUnevictable() {
        mUsageList.makeUnevictable(this);
    }

    /**
     * Search for a value, starting from the root node.
     *
     * @param node root node
     * @param key search key
     * @return copy of value or null if not found
     */
    static byte[] search(Node node, Tree tree, byte[] key) throws IOException {
        node.acquireShared();

        // Note: No need to check if root has split, since root splits are always completed
        // before releasing the root latch. Also, Database.used is not invoked for the root
        // node. Root node is not managed in usage list, because it cannot be evicted.

        if (!node.isLeaf()) {
            // Shared latch held on parent. Is null for root or if exclusive latch is held on
            // active node.
            Latch parentLatch = null;
            boolean exclusiveHeld = false;

            loop: while (true) {
                int childPos;
                try {
                    childPos = internalPos(node.binarySearch(key));
                } catch (Throwable e) {
                    node.release(exclusiveHeld);
                    if (parentLatch != null) {
                        parentLatch.releaseShared();
                    }
                    throw e;
                }

                long childId = node.retrieveChildRefId(childPos);
                Node childNode = tree.mDatabase.nodeMapGet(childId);

                childCheck: if (childNode != null) {
                    latchChild: if (!childNode.tryAcquireShared()) {
                        if (!exclusiveHeld) {
                            childNode.acquireShared();
                            break latchChild;
                        }
                        // If exclusive latch is held, then this node was just loaded. If child
                        // node cannot be immediately latched, it might have been evicted out
                        // of order. This can create a deadlock with a thread that may hold the
                        // exclusive latch and is now trying to latch this node.
                        if (childId != childNode.mId) {
                            break childCheck;
                        }
                        if (!childNode.tryAcquireShared()) {
                            // Be safe and start over with a Cursor. It doesn't have the same
                            // deadlock potential, because it prevents visited nodes from being
                            // evicted.
                            node.releaseExclusive();
                            return searchWithCursor(tree, key);
                        }
                    }

                    // Need to check again in case evict snuck in.
                    if (childId != childNode.mId) {
                        childNode.releaseShared();
                        break childCheck;
                    }

                    if (!exclusiveHeld && parentLatch != null) {
                        parentLatch.releaseShared();
                    }

                    if (childNode.mSplit != null) {
                        childNode = childNode.mSplit.selectNodeShared(childNode, key);
                    }

                    if (childNode.isLeaf()) {
                        node.release(exclusiveHeld);
                        childNode.used();
                        node = childNode;
                        break loop;
                    } else {
                        // Keep shared latch on this parent node, in case sub search
                        // needs to upgrade its shared latch.
                        if (exclusiveHeld) {
                            node.downgrade();
                            exclusiveHeld = false;
                        }
                        childNode.used();
                        parentLatch = node;
                        node = childNode;
                        continue;
                    }
                } // end childCheck

                // Child needs to be loaded.

                tryLoadChild: {
                    if (!exclusiveHeld) {
                        if (!node.tryUpgrade()) {
                            break tryLoadChild;
                        }
                        exclusiveHeld = true;
                        if (parentLatch != null) {
                            parentLatch.releaseShared();
                            parentLatch = null;
                        }
                    }

                    // Succeeded in obtaining an exclusive latch, so now load the child.

                    node = node.loadChild(tree.mDatabase, childId, true);

                    if (node.isLeaf()) {
                        node.downgrade();
                        break loop;
                    }

                    // Keep exclusive latch on internal child, because it will most likely need
                    // to load its own child nodes to continue the search. This eliminates the
                    // latch upgrade step.

                    continue;
                }

                // Release shared latch, re-acquire exclusive latch, and start over.

                long id = node.mId;
                node.releaseShared();
                node.acquireExclusive();

                if (node.mId != id && node != tree.mRoot) {
                    // Node got evicted or dirtied when latch was released. To be
                    // safe, the search must be retried from the root.
                    node.releaseExclusive();
                    if (parentLatch != null) {
                        parentLatch.releaseShared();
                    }
                    // Retry with a cursor, which is reliable, but slower.
                    return searchWithCursor(tree, key);
                }

                exclusiveHeld = true;

                if (parentLatch != null) {
                    parentLatch.releaseShared();
                    parentLatch = null;
                }

                if (node.mSplit != null) {
                    // Node might have split while shared latch was not held.
                    node = node.mSplit.selectNodeExclusive(node, key);
                }

                if (node == tree.mRoot) {
                    // This is the root node, and so no parent latch exists. It's possible that
                    // a delete slipped in when the latch was released, and that the root is
                    // now a leaf.
                    if (node.isLeaf()) {
                        node.downgrade();
                        break loop;
                    }
                }
            } // end loop
        }

        // Sub search into leaf with shared latch held.

        // Same code as binarySearch, but instead of returning the position, it directly copies
        // the value if found. This avoids having to decode the found value location twice.

        try {
            final /*P*/ byte[] page = node.mPage;
            final int keyLen = key.length;
            int lowPos = node.searchVecStart();
            int highPos = node.searchVecEnd();

            int lowMatch = 0;
            int highMatch = 0;

            outer: while (lowPos <= highPos) {
                int midPos = ((lowPos + highPos) >> 1) & ~1;

                int compareLoc, compareLen, i;
                compare: {
                    compareLoc = p_ushortGetLE(page, midPos);
                    compareLen = p_byteGet(page, compareLoc++);
                    if (compareLen >= 0) {
                        compareLen++;
                    } else {
                        int header = compareLen;
                        compareLen = ((compareLen & 0x3f) << 8) | p_ubyteGet(page, compareLoc++);

                        if ((header & ENTRY_FRAGMENTED) != 0) {
                            // Note: An optimized version wouldn't need to copy the whole key.
                            byte[] compareKey = tree.mDatabase.reconstructKey
                                (page, compareLoc, compareLen);

                            int fullCompareLen = compareKey.length;

                            int minLen = Math.min(fullCompareLen, keyLen);
                            i = Math.min(lowMatch, highMatch);
                            for (; i<minLen; i++) {
                                byte cb = compareKey[i];
                                byte kb = key[i];
                                if (cb != kb) {
                                    if ((cb & 0xff) < (kb & 0xff)) {
                                        lowPos = midPos + 2;
                                        lowMatch = i;
                                    } else {
                                        highPos = midPos - 2;
                                        highMatch = i;
                                    }
                                    continue outer;
                                }
                            }

                            // Update compareLen and compareLoc for use by the code after the
                            // current scope. The compareLoc is completely bogus at this point,
                            // but is corrected when the value is retrieved below.
                            compareLoc += compareLen - fullCompareLen;
                            compareLen = fullCompareLen;

                            break compare;
                        }
                    }

                    int minLen = Math.min(compareLen, keyLen);
                    i = Math.min(lowMatch, highMatch);
                    for (; i<minLen; i++) {
                        byte cb = p_byteGet(page, compareLoc + i);
                        byte kb = key[i];
                        if (cb != kb) {
                            if ((cb & 0xff) < (kb & 0xff)) {
                                lowPos = midPos + 2;
                                lowMatch = i;
                            } else {
                                highPos = midPos - 2;
                                highMatch = i;
                            }
                            continue outer;
                        }
                    }
                }

                if (compareLen < keyLen) {
                    lowPos = midPos + 2;
                    lowMatch = i;
                } else if (compareLen > keyLen) {
                    highPos = midPos - 2;
                    highMatch = i;
                } else {
                    return retrieveLeafValueAtLoc(node, page, compareLoc + compareLen);
                }
            }

            return null;
        } finally {
            node.releaseShared();
        }
    }

    /**
     * With this parent node held exclusively, loads child with exclusive latch
     * held. Caller must ensure that child is not already loaded. If an
     * exception is thrown, parent and child latches are always released.
     *
     * @param releaseParent when true, release this node latch always; when false, release only
     * if an exception is thrown
     */
    Node loadChild(Database db, long childId, boolean releaseParent) throws IOException {
        Node childNode;
        try {
            childNode = db.allocLatchedNode(childId);
            childNode.mId = childId;
        } catch (Throwable e) {
            releaseExclusive();
            throw e;
        }

        db.nodeMapPut(childNode);

        // Release parent latch before child has been loaded. Any threads
        // which wish to access the same child will block until this thread
        // has finished loading the child and released its exclusive latch.
        if (releaseParent) {
            releaseExclusive();
        }

        // FIXME: Don't hold latch during load. Instead, use an object for
        // holding state, and include a "loading" state. As other threads see
        // this state, they replace the state object with a linked stack of
        // parked threads. When the load is finished, all waiting threads are
        // unparked. Without this change, latch blockage can reach the root.

        try {
            childNode.read(db, childId);
        } catch (Throwable e) {
            // Another thread might access child and see that it is invalid because
            // id is zero. It will assume it got evicted and will load child again.
            db.nodeMapRemove(childNode);
            childNode.mId = 0;
            childNode.type(TYPE_NONE);
            childNode.releaseExclusive();

            if (!releaseParent) {
                // Obey the method contract and release latch due to exception.
                releaseExclusive();
            }

            throw e;
        }

        return childNode;
    }

    private static byte[] searchWithCursor(Tree tree, byte[] key) throws IOException {
        TreeCursor cursor = new TreeCursor(tree, Transaction.BOGUS);
        try {
            cursor.find(key);
            byte[] value = cursor.value();
            cursor.reset();
            return value;
        } catch (Throwable e) {
            throw closeOnFailure(cursor, e);
        }
    }

    /**
     * With this parent node held exclusively, attempts to return child with exclusive latch
     * held. If an exception is thrown, parent and child latches are always released. This
     * method is intended to be called for rebalance operations.
     *
     * @return null or child node, never split
     */
    private Node tryLatchChildNotSplit(int childPos) throws IOException {
        final long childId = retrieveChildRefId(childPos);
        final Database db = getDatabase();
        Node childNode = db.nodeMapGet(childId);

        if (childNode != null) {
            if (!childNode.tryAcquireExclusive()) {
                return null;
            }
            // Need to check again in case evict snuck in.
            if (childId != childNode.mId) {
                childNode.releaseExclusive();
            } else if (childNode.mSplit == null) {
                // Return without updating LRU position. Node contents were not user requested.
                return childNode;
            } else {
                childNode.releaseExclusive();
                return null;
            }
        }

        return loadChild(db, childId, false);
    }

    /**
     * Caller must hold exclusive root latch and it must verify that root has split. Caller
     * must also exclusively hold stub latch, if applicable. This required for safely unbinding
     * parent cursor frames which refer to the old root node.
     */
    void finishSplitRoot() throws IOException {
        // Create a child node and copy this root node state into it. Then update this
        // root node to point to new and split child nodes. New root is always an internal node.

        Database db = mUsageList.mDatabase;
        Node child = db.allocDirtyNode();
        db.nodeMapPut(child);

        /*P*/ byte[] newPage = child.mPage;
        child.mPage = mPage;
        /*P*/ // {
        child.type(type());
        child.garbage(garbage());
        child.leftSegTail(leftSegTail());
        child.rightSegTail(rightSegTail());
        child.searchVecStart(searchVecStart());
        child.searchVecEnd(searchVecEnd());
        /*P*/ // }
        child.mLastCursorFrame = mLastCursorFrame;

        // Fix child node cursor frame bindings.
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            frame.mNode = child;
            frame = frame.mPrevCousin;
        }

        final Split split = mSplit;
        final Node sibling = rebindSplitFrames(split);
        mSplit = null;

        Node left, right;
        if (split.mSplitRight) {
            left = child;
            right = sibling;
        } else {
            left = sibling;
            right = child;
        }

        int leftSegTail = split.copySplitKeyToParent(newPage, TN_HEADER_SIZE);

        // Create new single-element search vector. Center it using the same formula as the
        // compactInternal method.
        final int searchVecStart = p_length(newPage) -
            (((p_length(newPage) - leftSegTail + (2 + 8 + 8)) >> 1) & ~1);
        p_shortPutLE(newPage, searchVecStart, TN_HEADER_SIZE);
        p_longPutLE(newPage, searchVecStart + 2, left.mId);
        p_longPutLE(newPage, searchVecStart + 2 + 8, right.mId);

        byte newType = isLeaf() ? (byte) (TYPE_TN_BIN | LOW_EXTREMITY | HIGH_EXTREMITY)
            : (byte) (TYPE_TN_IN | LOW_EXTREMITY | HIGH_EXTREMITY);

        mPage = newPage;
        /*P*/ // {
        type(newType);
        garbage(0);
        /*P*/ // |
        /*P*/ // p_intPutLE(newPage, 0, newType & 0xff); // set type, reserved byte, and garbage
        /*P*/ // }
        leftSegTail(leftSegTail);
        rightSegTail(p_length(newPage) - 1);
        searchVecStart(searchVecStart);
        searchVecEnd(searchVecStart);
        mLastCursorFrame = null;

        // Add a parent cursor frame for all left and right node cursors.
        addParentFrames(left, 0);
        addParentFrames(right, 2);

        child.releaseExclusive();
        sibling.releaseExclusive();

        // Split complete, so allow new node to be evictable.
        sibling.makeEvictable();
    }

    private void addParentFrames(Node child, int pos) {
        for (TreeCursorFrame frame = child.mLastCursorFrame; frame != null; ) {
            TreeCursorFrame parentFrame = frame.mParentFrame;
            if (parentFrame == null) {
                parentFrame = new TreeCursorFrame();
            } else {
                parentFrame.unbind();
            }
            parentFrame.bind(this, pos);
            frame.mParentFrame = parentFrame;
            frame = frame.mPrevCousin;
        }
    }

    /**
     * Caller must hold exclusive latch. Latch is never released by this method, even if
     * an exception is thrown.
     */
    void read(Database db, long id) throws IOException {
        mCachedState = db.readNodePage(id, mPage);
        mId = id;
        try {
            readFields();
        } catch (IllegalStateException e) {
            throw new CorruptDatabaseException(e.getMessage());
        }
    }

    private void readFields() throws IllegalStateException {
        /*P*/ byte[] page = mPage;

        byte type = p_byteGet(page, 0);

        /*P*/ // {
        type(type);

        // For undo log node, this is top entry pointer.
        garbage(p_ushortGetLE(page, 2));
        /*P*/ // }

        if (type != TYPE_UNDO_LOG) {
            /*P*/ // {
            leftSegTail(p_ushortGetLE(page, 4));
            rightSegTail(p_ushortGetLE(page, 6));
            searchVecStart(p_ushortGetLE(page, 8));
            searchVecEnd(p_ushortGetLE(page, 10));
            /*P*/ // }
            type &= ~(LOW_EXTREMITY | HIGH_EXTREMITY);
            if (type >= 0 && type != TYPE_TN_IN && type != TYPE_TN_BIN) {
                throw new IllegalStateException("Unknown node type: " + type + ", id: " + mId);
            }
        }

        if (p_byteGet(page, 1) != 0) {
            throw new IllegalStateException
                ("Illegal reserved byte in node: " + p_byteGet(page, 1));
        }
    }

    /**
     * Caller must hold any latch, which is not released, even if an exception is thrown.
     */
    void write(PageDb db) throws WriteFailureException {
        /*P*/ byte[] page = prepareWrite();
        try {
            db.writePage(mId, page);
        } catch (IOException e) {
            throw new WriteFailureException(e);
        }
    }

    private /*P*/ byte[] prepareWrite() {
        if (mSplit != null) {
            throw new AssertionError("Cannot write partially split node");
        }

        /*P*/ byte[] page = mPage;

        /*P*/ // {
        if (type() != TYPE_FRAGMENT) {
            p_bytePut(page, 0, type());
            p_bytePut(page, 1, 0); // reserved

            // For undo log node, this is top entry pointer.
            p_shortPutLE(page, 2, garbage());

            if (type() != TYPE_UNDO_LOG) {
                p_shortPutLE(page, 4, leftSegTail());
                p_shortPutLE(page, 6, rightSegTail());
                p_shortPutLE(page, 8, searchVecStart());
                p_shortPutLE(page, 10, searchVecEnd());
            }
        }
        /*P*/ // }

        return page;
    }

    /**
     * Caller must hold exclusive latch on node. Latch is released by this
     * method when null is returned or if an exception is thrown. If another
     * node is returned, it is latched exclusively and original is released.
     *
     * @return original or another node to be evicted; null if cannot evict
     */
    static Node evict(Node node, Database db) throws IOException {
        if (node.type() != TYPE_UNDO_LOG) {
            return node.evictTreeNode(db);
        }

        while (true) {
            Node child = node.mNodeChainNext;
            if (child != null) {
                long childId = p_longGetLE(node.mPage, UndoLog.I_LOWER_NODE_ID);
                // Check id match before lock attempt, as a quick short
                // circuit if child has already been evicted.
                if (childId == child.mId) {
                    if (child.tryAcquireExclusive()) {
                        // Check again in case another evict snuck in.
                        if (childId == child.mId && child.mCachedState != CACHED_CLEAN) {
                            // Try evicting the child instead.
                            node.releaseExclusive();
                            node = child;
                            continue;
                        }
                        child.releaseExclusive();
                    } else {
                        // If latch cannot be acquired, assume child is still
                        // in use, and so the parent node should be kept.
                        node.releaseExclusive();
                        return null;
                    }
                }
            }
            node.doEvict(db);
            return node;
        }
    }

    private Node evictTreeNode(Database db) throws IOException {
        if (mLastCursorFrame != null || mSplit != null) {
            // Cannot evict if in use by a cursor or if splitting. The split
            // check is redundant, since a node cannot be in a split state
            // without a cursor registered against it.
            releaseExclusive();
            return null;
        }

        // Check if <= 0 (already evicted) or stub.
        if (mId > STUB_ID) {
            doEvict(db);
        }

        return this;
    }

    /**
     * Caller must hold exclusive latch on node. Latch is released by this
     * method when an exception is thrown.
     */
    void doEvict(Database db) throws IOException {
        try {
            long id = mId;

            PageDb pageDb = db.mPageDb;
            if (mCachedState == CACHED_CLEAN) {
                // Try to move to a secondary cache.
                pageDb.cachePage(id, mPage);
            } else {
                /*P*/ byte[] page = prepareWrite();
                /*P*/ byte[] newPage = pageDb.evictPage(id, page);
                if (newPage != page) {
                    mPage = newPage;
                }
                mCachedState = CACHED_CLEAN;
            }

            db.nodeMapRemove(this, Utils.hash(id));
            mId = 0;
            type(TYPE_NONE);
        } catch (Throwable e) {
            releaseExclusive();
            throw e;
        }
    }

    /**
     * Invalidate all cursors, starting from the root. Used when closing an index which still
     * has active cursors. Caller must hold exclusive latch on node.
     */
    void invalidateCursors() {
        invalidateCursors(createEmptyNode());
    }

    private void invalidateCursors(Node empty) {
        int pos = isLeaf() ? -1 : 0;

        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            frame.mNode = empty;
            frame.mNodePos = pos;
            frame = frame.mPrevCousin;
        }

        if (!isInternal()) {
            return;
        }

        Database db = mUsageList.mDatabase;

        empty = null;

        int childPtr = searchVecEnd() + 2;
        final int highestPtr = childPtr + (highestInternalPos() << 2);
        for (; childPtr <= highestPtr; childPtr += 8) {
            long childId = p_uint48GetLE(mPage, childPtr);
            Node child = db.nodeMapGet(childId);
            if (child != null) {
                child.acquireExclusive();
                if (childId == child.mId) {
                    if (empty == null) {
                        empty = createEmptyNode();
                    }
                    child.invalidateCursors(empty);
                }
                child.releaseExclusive();
            }
        }
    }

    private static Node createEmptyNode() {
        Node empty = new Node(null, p_emptyTreePage());
        empty.mId = STUB_ID;
        empty.mCachedState = CACHED_CLEAN;
        empty.readFields();
        return empty;
    }

    /**
     * Get the node type.
     */
    byte type() {
        /*P*/ // {
        return mType;
        /*P*/ // |
        /*P*/ // return p_byteGet(mPage, 0);
        /*P*/ // }
    }

    /**
     * Set the node type.
     */
    void type(byte type) {
        /*P*/ // {
        mType = type;
        /*P*/ // |
        /*P*/ // p_bytePut(mPage, 0, type);
        /*P*/ // }
    }

    /**
     * Get the node garbage size.
     */
    int garbage() {
        /*P*/ // {
        return mGarbage;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 2);
        /*P*/ // }
    }

    /**
     * Set the node garbage size.
     */
    void garbage(int garbage) {
        /*P*/ // {
        mGarbage = garbage;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 2, garbage);
        /*P*/ // }
    }

    /**
     * Get the undo log node top entry pointer. (same field as garbage)
     */
    int undoTop() {
        /*P*/ // {
        return mGarbage;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 2);
        /*P*/ // }
    }

    /**
     * Set the undo log node top entry pointer. (same field as garbage)
     */
    void undoTop(int top) {
        /*P*/ // {
        mGarbage = top;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 2, top);
        /*P*/ // }
    }

    /**
     * Get the left segment tail pointer.
     */
    private int leftSegTail() {
        /*P*/ // {
        return mLeftSegTail;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 4);
        /*P*/ // }
    }

    /**
     * Set the left segment tail pointer.
     */
    private void leftSegTail(int tail) {
        /*P*/ // {
        mLeftSegTail = tail;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 4, tail);
        /*P*/ // }
    }

    /**
     * Get the right segment tail pointer.
     */
    private int rightSegTail() {
        /*P*/ // {
        return mRightSegTail;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 6);
        /*P*/ // }
    }

    /**
     * Set the right segment tail pointer.
     */
    private void rightSegTail(int tail) {
        /*P*/ // {
        mRightSegTail = tail;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 6, tail);
        /*P*/ // }
    }

    /**
     * Get the search vector start pointer.
     */
    int searchVecStart() {
        /*P*/ // {
        return mSearchVecStart;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 8);
        /*P*/ // }
    }

    /**
     * Set the search vector start pointer.
     */
    private void searchVecStart(int start) {
        /*P*/ // {
        mSearchVecStart = start;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 8, start);
        /*P*/ // }
    }

    /**
     * Get the search vector end pointer.
     */
    private int searchVecEnd() {
        /*P*/ // {
        return mSearchVecEnd;
        /*P*/ // |
        /*P*/ // return p_ushortGetLE(mPage, 10);
        /*P*/ // }
    }

    /**
     * Set the search vector end pointer.
     */
    private void searchVecEnd(int end) {
        /*P*/ // {
        mSearchVecEnd = end;
        /*P*/ // |
        /*P*/ // p_shortPutLE(mPage, 10, end);
        /*P*/ // }
    }

    /**
     * Caller must hold any latch.
     */
    boolean isLeaf() {
        return type() < 0;
    }

    /**
     * Caller must hold any latch. Returns true if node is any kind of internal node.
     */
    boolean isInternal() {
        return (type() & 0xe0) == 0x60;
    }

    /**
     * Caller must hold any latch.
     */
    boolean isBottomInternal() {
        return (type() & 0xf0) == 0x70;
    }

    /**
     * Caller must hold any latch.
     */
    boolean isNonBottomInternal() {
        return (type() & 0xf0) == 0x60;
    }

    /**
     * Caller must hold any latch.
     *
     * @see #countNonGhostKeys
     */
    int numKeys() {
        return (searchVecEnd() - searchVecStart() + 2) >> 1;
    }

    /**
     * Caller must hold any latch.
     */
    boolean hasKeys() {
        return searchVecEnd() >= searchVecStart();
    }

    /**
     * Returns the highest possible key position, which is an even number. If
     * node has no keys, return value is negative. Caller must hold any latch.
     */
    int highestKeyPos() {
        return searchVecEnd() - searchVecStart();
    }

    /**
     * Returns highest leaf or internal position. Caller must hold any latch.
     */
    int highestPos() {
        int pos = searchVecEnd() - searchVecStart();
        if (!isLeaf()) {
            pos += 2;
        }
        return pos;
    }

    /**
     * Returns the highest possible leaf key position, which is an even
     * number. If leaf node is empty, return value is negative. Caller must
     * hold any latch.
     */
    int highestLeafPos() {
        return searchVecEnd() - searchVecStart();
    }

    /**
     * Returns the highest possible internal node position, which is an even
     * number. Highest position doesn't correspond to a valid key, but instead
     * a child node position. If internal node has no keys, node has one child
     * at position zero. Caller must hold any latch.
     */
    int highestInternalPos() {
        return searchVecEnd() - searchVecStart() + 2;
    }

    /**
     * Caller must hold any latch.
     */
    int availableBytes() {
        return isLeaf() ? availableLeafBytes() : availableInternalBytes();
    }

    /**
     * Caller must hold any latch.
     */
    int availableLeafBytes() {
        return garbage() + searchVecStart() - searchVecEnd()
            - leftSegTail() + rightSegTail() + (1 - 2);
    }

    /**
     * Caller must hold any latch.
     */
    int availableInternalBytes() {
        return garbage() + 5 * (searchVecStart() - searchVecEnd())
            - leftSegTail() + rightSegTail() + (1 - (5 * 2 + 8));
    }

    /**
     * Applicable only to leaf nodes. Caller must hold any latch.
     */
    int countNonGhostKeys() {
        final /*P*/ byte[] page = mPage;

        int count = 0;
        for (int i = searchVecStart(); i <= searchVecEnd(); i += 2) {
            int loc = p_ushortGetLE(page, i);
            if (p_byteGet(page, loc + keyLengthAtLoc(page, loc)) != -1) {
                count++;
            }
        }

        return count;
    }

    /**
     * Returns true if leaf is not split and underutilized. If so, it should be
     * merged with its neighbors, and possibly deleted. Caller must hold any latch.
     */
    boolean shouldLeafMerge() {
        return shouldMerge(availableLeafBytes());
    }

    /**
     * Returns true if non-leaf is not split and underutilized. If so, it should be
     * merged with its neighbors, and possibly deleted. Caller must hold any latch.
     */
    boolean shouldInternalMerge() {
        return shouldMerge(availableInternalBytes());
    }

    boolean shouldMerge(int availBytes) {
        return mSplit == null
            & (((type() & (LOW_EXTREMITY | HIGH_EXTREMITY)) == 0
                 & availBytes >= ((p_length(mPage) - TN_HEADER_SIZE) >> 1))
                | !hasKeys());
    }

    /**
     * @return 2-based insertion pos, which is negative if key not found
     */
    int binarySearch(byte[] key) throws IOException {
        final /*P*/ byte[] page = mPage;
        final int keyLen = key.length;
        int lowPos = searchVecStart();
        int highPos = searchVecEnd();

        int lowMatch = 0;
        int highMatch = 0;

        outer: while (lowPos <= highPos) {
            int midPos = ((lowPos + highPos) >> 1) & ~1;

            int compareLen, i;
            compare: {
                int compareLoc = p_ushortGetLE(page, midPos);
                compareLen = p_byteGet(page, compareLoc++);
                if (compareLen >= 0) {
                    compareLen++;
                } else {
                    int header = compareLen;
                    compareLen = ((compareLen & 0x3f) << 8) | p_ubyteGet(page, compareLoc++);

                    if ((header & ENTRY_FRAGMENTED) != 0) {
                        // Note: An optimized version wouldn't need to copy the whole key.
                        byte[] compareKey = getDatabase()
                            .reconstructKey(page, compareLoc, compareLen);
                        compareLen = compareKey.length;

                        int minLen = Math.min(compareLen, keyLen);
                        i = Math.min(lowMatch, highMatch);
                        for (; i<minLen; i++) {
                            byte cb = compareKey[i];
                            byte kb = key[i];
                            if (cb != kb) {
                                if ((cb & 0xff) < (kb & 0xff)) {
                                    lowPos = midPos + 2;
                                    lowMatch = i;
                                } else {
                                    highPos = midPos - 2;
                                    highMatch = i;
                                }
                                continue outer;
                            }
                        }

                        break compare;
                    }
                }

                int minLen = Math.min(compareLen, keyLen);
                i = Math.min(lowMatch, highMatch);
                for (; i<minLen; i++) {
                    byte cb = p_byteGet(page, compareLoc + i);
                    byte kb = key[i];
                    if (cb != kb) {
                        if ((cb & 0xff) < (kb & 0xff)) {
                            lowPos = midPos + 2;
                            lowMatch = i;
                        } else {
                            highPos = midPos - 2;
                            highMatch = i;
                        }
                        continue outer;
                    }
                }
            }

            if (compareLen < keyLen) {
                lowPos = midPos + 2;
                lowMatch = i;
            } else if (compareLen > keyLen) {
                highPos = midPos - 2;
                highMatch = i;
            } else {
                return midPos - searchVecStart();
            }
        }

        return ~(lowPos - searchVecStart());
    }

    /**
     * @param midPos 2-based starting position
     * @return 2-based insertion pos, which is negative if key not found
     */
    int binarySearch(byte[] key, int midPos) throws IOException {
        int lowPos = searchVecStart();
        int highPos = searchVecEnd();
        if (lowPos > highPos) {
            return -1;
        }
        midPos += lowPos;
        if (midPos > highPos) {
            midPos = highPos;
        }

        final /*P*/ byte[] page = mPage;
        final int keyLen = key.length;

        int lowMatch = 0;
        int highMatch = 0;

        while (true) {
            compare: {
                int compareLen, i;
                c2: {
                    int compareLoc = p_ushortGetLE(page, midPos);
                    compareLen = p_byteGet(page, compareLoc++);
                    if (compareLen >= 0) {
                        compareLen++;
                    } else {
                        int header = compareLen;
                        compareLen = ((compareLen & 0x3f) << 8) | p_ubyteGet(page, compareLoc++);

                        if ((header & ENTRY_FRAGMENTED) != 0) {
                            // Note: An optimized version wouldn't need to copy the whole key.
                            byte[] compareKey = getDatabase()
                                .reconstructKey(page, compareLoc, compareLen);
                            compareLen = compareKey.length;

                            int minLen = Math.min(compareLen, keyLen);
                            i = Math.min(lowMatch, highMatch);
                            for (; i<minLen; i++) {
                                byte cb = compareKey[i];
                                byte kb = key[i];
                                if (cb != kb) {
                                    if ((cb & 0xff) < (kb & 0xff)) {
                                        lowPos = midPos + 2;
                                        lowMatch = i;
                                    } else {
                                        highPos = midPos - 2;
                                        highMatch = i;
                                    }
                                    break compare;
                                }
                            }

                            break c2;
                        }
                    }

                    int minLen = Math.min(compareLen, keyLen);
                    i = Math.min(lowMatch, highMatch);
                    for (; i<minLen; i++) {
                        byte cb = p_byteGet(page, compareLoc + i);
                        byte kb = key[i];
                        if (cb != kb) {
                            if ((cb & 0xff) < (kb & 0xff)) {
                                lowPos = midPos + 2;
                                lowMatch = i;
                            } else {
                                highPos = midPos - 2;
                                highMatch = i;
                            }
                            break compare;
                        }
                    }
                }

                if (compareLen < keyLen) {
                    lowPos = midPos + 2;
                    lowMatch = i;
                } else if (compareLen > keyLen) {
                    highPos = midPos - 2;
                    highMatch = i;
                } else {
                    return midPos - searchVecStart();
                }
            }

            if (lowPos > highPos) {
                break;
            }

            midPos = ((lowPos + highPos) >> 1) & ~1;
        }

        return ~(lowPos - searchVecStart());
    }

    /**
     * Ensure binary search position is positive, for internal node.
     */
    static int internalPos(int pos) {
        return pos < 0 ? ~pos : (pos + 2);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    int compareKey(int pos, byte[] rightKey) throws IOException {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            if ((header & ENTRY_FRAGMENTED) != 0) {
                // Note: An optimized version wouldn't need to copy the whole key.
                byte[] leftKey = getDatabase().reconstructKey(page, loc, keyLen);
                return compareUnsigned(leftKey, 0, leftKey.length, rightKey, 0, rightKey.length);
            }
        }
        return p_compareKeysPageToArray(page, loc, keyLen, rightKey, 0, rightKey.length);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @param stats [0]: full length, [1]: number of pages (>0 if fragmented)
     */
    void retrieveKeyStats(int pos, long[] stats) throws IOException {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);

        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            if ((header & ENTRY_FRAGMENTED) != 0) {
                getDatabase().reconstruct(page, loc, keyLen, stats);
                return;
            }
        }

        stats[0] = keyLen;
        stats[1] = 0;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    byte[] retrieveKey(int pos) throws IOException {
        final /*P*/ byte[] page = mPage;
        return retrieveKeyAtLoc(this, page, p_ushortGetLE(page, searchVecStart() + pos));
    }

    /**
     * @param loc absolute location of entry
     */
    byte[] retrieveKeyAtLoc(final /*P*/ byte[] page, int loc) throws IOException {
        return retrieveKeyAtLoc(this, page, loc);
    }

    /**
     * @param loc absolute location of entry
     */
    static byte[] retrieveKeyAtLoc(DatabaseAccess dbAccess, final /*P*/ byte[] page, int loc)
        throws IOException
    {
        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            if ((header & ENTRY_FRAGMENTED) != 0) {
                return dbAccess.getDatabase().reconstructKey(page, loc, keyLen);
            }
        }
        byte[] key = new byte[keyLen];
        p_copyToArray(page, loc, key, 0, keyLen);
        return key;
    }

    /**
     * @param loc absolute location of entry
     * @param akeyRef [0] is set to the actual key
     * @return false if key is fragmented and actual doesn't match original
     */
    private boolean retrieveActualKeyAtLoc(final /*P*/ byte[] page, int loc,
                                           final byte[][] akeyRef)
        throws IOException
    {
        boolean result = true;

        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
            result = (header & ENTRY_FRAGMENTED) == 0;
        }
        byte[] akey = new byte[keyLen];
        p_copyToArray(page, loc, akey, 0, keyLen);
        akeyRef[0] = akey;

        return result;
    }

    /**
     * Copies the key at the given position based on a limit. If equal, the
     * limitKey instance is returned. If beyond the limit, null is returned.
     *
     * @param pos position as provided by binarySearch; must be positive
     * @param limitKey comparison key
     * @param limitMode positive for LE behavior, negative for GE behavior
     */
    byte[] retrieveKeyCmp(int pos, byte[] limitKey, int limitMode) throws IOException {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        int keyLen = p_byteGet(page, loc++);
        if (keyLen >= 0) {
            keyLen++;
        } else {
            int header = keyLen;
            keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);

            if ((header & ENTRY_FRAGMENTED) != 0) {
                byte[] key = getDatabase().reconstructKey(page, loc, keyLen);
                int cmp = compareUnsigned(key, limitKey);
                if (cmp == 0) {
                    return limitKey;
                } else {
                    return (cmp ^ limitMode) < 0 ? key : null;
                }
            }
        }

        int cmp = p_compareKeysPageToArray(page, loc, keyLen, limitKey, 0, limitKey.length);
        if (cmp == 0) {
            return limitKey;
        } else if ((cmp ^ limitMode) < 0) {
            byte[] key = new byte[keyLen];
            p_copyToArray(page, loc, key, 0, keyLen);
            return key;
        } else {
            return null;
        }
    }

    /**
     * Used by UndoLog for decoding entries. Only works for non-fragmented values.
     *
     * @param loc absolute location of entry
     */
    static byte[][] retrieveKeyValueAtLoc(DatabaseAccess dbAccess,
                                          final /*P*/ byte[] page, int loc)
        throws IOException
    {
        int header = p_byteGet(page, loc++);

        int keyLen;
        byte[] key;
        copyKey: {
            if (header >= 0) {
                keyLen = header + 1;
            } else {
                keyLen = ((header & 0x3f) << 8) | p_ubyteGet(page, loc++);
                if ((header & ENTRY_FRAGMENTED) != 0) {
                    key = dbAccess.getDatabase().reconstructKey(page, loc, keyLen);
                    break copyKey;
                }
            }
            key = new byte[keyLen];
            p_copyToArray(page, loc, key, 0, keyLen);
        }

        return new byte[][] {key, retrieveLeafValueAtLoc(null, page, loc + keyLen)};
    }

    /**
     * Returns a new key between the low key in this node and the given high key.
     *
     * @see Utils#midKey
     */
    private byte[] midKey(int lowPos, byte[] highKey) throws IOException {
        final /*P*/ byte[] lowPage = mPage;
        int lowLoc = p_ushortGetLE(lowPage, searchVecStart() + lowPos);
        int lowKeyLen = p_byteGet(lowPage, lowLoc);
        if (lowKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            return Utils.midKey(retrieveKeyAtLoc(lowPage, lowLoc), highKey);
        } else {
            return p_midKeyLowPage(lowPage, lowLoc + 1, lowKeyLen + 1, highKey, 0, highKey.length);
        }
    }

    /**
     * Returns a new key between the given low key and the high key in this node.
     *
     * @see Utils#midKey
     */
    private byte[] midKey(byte[] lowKey, int highPos) throws IOException {
        final /*P*/ byte[] highPage = mPage;
        int highLoc = p_ushortGetLE(highPage, searchVecStart() + highPos);
        int highKeyLen = p_byteGet(highPage, highLoc);
        if (highKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            return Utils.midKey(lowKey, retrieveKeyAtLoc(highPage, highLoc));
        } else {
            return p_midKeyHighPage(lowKey, 0, lowKey.length,
                                    highPage, highLoc + 1, highKeyLen + 1);
        }
    }

    /**
     * Returns a new key between the low key in this node and the high key of another node.
     *
     * @see Utils#midKey
     */
    byte[] midKey(int lowPos, Node highNode, int highPos) throws IOException {
        final /*P*/ byte[] lowPage = mPage;
        int lowLoc = p_ushortGetLE(lowPage, searchVecStart() + lowPos);
        int lowKeyLen = p_byteGet(lowPage, lowLoc);
        if (lowKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            return highNode.midKey(retrieveKeyAtLoc(lowPage, lowLoc), highPos);
        }

        lowLoc++;
        lowKeyLen++;

        final /*P*/ byte[] highPage = highNode.mPage;
        int highLoc = p_ushortGetLE(highPage, highNode.searchVecStart() + highPos);
        int highKeyLen = p_byteGet(highPage, highLoc);
        if (highKeyLen < 0) {
            // Note: An optimized version wouldn't need to copy the whole key.
            byte[] highKey = retrieveKeyAtLoc(highPage, highLoc);
            return p_midKeyLowPage(lowPage, lowLoc, lowKeyLen, highKey, 0, highKey.length);
        }

        return p_midKeyLowHighPage(lowPage, lowLoc, lowKeyLen,
                                   highPage, highLoc + 1, highKeyLen + 1);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @return Cursor.NOT_LOADED if value exists, null if ghost
     */
    byte[] hasLeafValue(int pos) {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);
        return p_byteGet(page, loc) == -1 ? null : Cursor.NOT_LOADED;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @param stats [0]: full length, [1]: number of pages (>0 if fragmented)
     */
    void retrieveLeafValueStats(int pos, long[] stats) throws IOException {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);

        final int header = p_byteGet(page, loc++);

        int len;
        if (header >= 0) {
            len = header;
        } else {
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
            } else {
                // ghost
                stats[0] = 0;
                stats[1] = 0;
                return;
            }
            if ((header & ENTRY_FRAGMENTED) != 0) {
                getDatabase().reconstruct(page, loc, len, stats);
                return;
            }
        }

        stats[0] = len;
        stats[1] = 0;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @return null if ghost
     */
    byte[] retrieveLeafValue(int pos) throws IOException {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);
        return retrieveLeafValueAtLoc(this, page, loc);
    }

    private static byte[] retrieveLeafValueAtLoc(DatabaseAccess dbAccess,
                                                 /*P*/ byte[] page, int loc)
        throws IOException
    {
        final int header = p_byteGet(page, loc++);
        if (header == 0) {
            return EMPTY_BYTES;
        }

        int len;
        if (header >= 0) {
            len = header;
        } else {
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
            } else {
                // ghost
                return null;
            }
            if ((header & ENTRY_FRAGMENTED) != 0) {
                return dbAccess.getDatabase().reconstruct(page, loc, len);
            }
        }

        byte[] value = new byte[len];
        p_copyToArray(page, loc, value, 0, len);
        return value;
    }

    /**
     * Sets the cursor key and value references. If mode is key-only, then set value is
     * Cursor.NOT_LOADED for a value which exists, null if ghost.
     *
     * @param pos position as provided by binarySearch; must be positive
     * @param cursor key and value are updated
     */
    void retrieveLeafEntry(int pos, TreeCursor cursor) throws IOException {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        int header = p_byteGet(page, loc++);

        int keyLen;
        byte[] key;
        copyKey: {
            if (header >= 0) {
                keyLen = header + 1;
            } else {
                keyLen = ((header & 0x3f) << 8) | p_ubyteGet(page, loc++);
                if ((header & ENTRY_FRAGMENTED) != 0) {
                    key = getDatabase().reconstructKey(page, loc, keyLen);
                    break copyKey;
                }
            }
            key = new byte[keyLen];
            p_copyToArray(page, loc, key, 0, keyLen);
        }

        loc += keyLen;
        cursor.mKey = key;

        byte[] value;
        if (cursor.mKeyOnly) {
            value = p_byteGet(page, loc) == -1 ? null : Cursor.NOT_LOADED;
        } else {
            value = retrieveLeafValueAtLoc(this, page, loc);
        }

        cursor.mValue = value;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    boolean isFragmentedLeafValue(int pos) {
        final /*P*/ byte[] page = mPage;
        int loc = p_ushortGetLE(page, searchVecStart() + pos);
        loc += keyLengthAtLoc(page, loc);
        int header = p_byteGet(page, loc);
        return ((header & 0xc0) >= 0xc0) & (header < -1);
    }

    /**
     * Transactionally delete a leaf entry, replacing the value with a
     * ghost. When read back, it is interpreted as null. Ghosts are used by
     * transactional deletes, to ensure that they are not visible by cursors in
     * other transactions. They need to acquire a lock first. When the original
     * transaction commits, it deletes all the ghosted entries it created.
     *
     * <p>Caller must hold commit lock and exclusive latch on node.
     *
     * @param pos position as provided by binarySearch; must be positive
     */
    void txnDeleteLeafEntry(Transaction txn, Tree tree, byte[] key, int keyHash, int pos)
        throws IOException
    {
        final /*P*/ byte[] page = mPage;
        final int entryLoc = p_ushortGetLE(page, searchVecStart() + pos);
        int loc = entryLoc;

        // Skip the key.
        loc += keyLengthAtLoc(page, loc);

        // Read value header.
        final int valueHeaderLoc = loc;
        int header = p_byteGet(page, loc++);

        doUndo: {
            // Note: Similar to leafEntryLengthAtLoc.
            if (header >= 0) {
                // Short value. Move loc to just past end of value.
                loc += header;
            } else {
                // Medium value. Move loc to just past end of value.
                if ((header & 0x20) == 0) {
                    loc += 2 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc));
                } else if (header != -1) {
                    loc += 3 + (((header & 0x0f) << 16)
                                | (p_ubyteGet(page, loc) << 8) | p_ubyteGet(page, loc + 1));
                } else {
                    // Already a ghost, so nothing to undo.
                    break doUndo;
                }

                if ((header & ENTRY_FRAGMENTED) != 0) {
                    int valueStartLoc = valueHeaderLoc + 2 + ((header & 0x20) >> 5);
                    tree.mDatabase.fragmentedTrash().add
                        (txn, tree.mId, page,
                         entryLoc, valueHeaderLoc - entryLoc,  // keyStart, keyLen
                         valueStartLoc, loc - valueStartLoc);  // valueStart, valueLen
                    break doUndo;
                }
            }

            // Copy whole entry into undo log.
            txn.pushUndoStore(tree.mId, UndoLog.OP_UNDELETE, page, entryLoc, loc - entryLoc);
        }

        // Ghost will be deleted later when locks are released.
        tree.mLockManager.ghosted(tree, key, keyHash);

        // Replace value with ghost.
        p_bytePut(page, valueHeaderLoc, -1);
        garbage(garbage() + loc - valueHeaderLoc - 1);

        if (txn.mDurabilityMode != DurabilityMode.NO_REDO) {
            txn.redoStore(tree.mId, key, null);
        }
    }

    /**
     * Copies existing entry to undo log prior to it being updated. Fragmented
     * values are added to the trash and the fragmented bit is cleared. Caller
     * must hold commit lock and exlusive latch on node.
     *
     * @param pos position as provided by binarySearch; must be positive
     */
    void txnPreUpdateLeafEntry(Transaction txn, Tree tree, byte[] key, int pos)
        throws IOException
    {
        final /*P*/ byte[] page = mPage;
        final int entryLoc = p_ushortGetLE(page, searchVecStart() + pos);
        int loc = entryLoc;

        // Skip the key.
        loc += keyLengthAtLoc(page, loc);

        // Read value header.
        final int valueHeaderLoc = loc;
        int header = p_byteGet(page, loc++);

        examineEntry: {
            // Note: Similar to leafEntryLengthAtLoc.
            if (header >= 0) {
                // Short value. Move loc to just past end of value.
                loc += header;
                break examineEntry;
            } else {
                // Medium value. Move loc to just past end of value.
                if ((header & 0x20) == 0) {
                    loc += 2 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc));
                } else if (header != -1) {
                    loc += 3 + (((header & 0x0f) << 16)
                                | (p_ubyteGet(page, loc) << 8) | p_ubyteGet(page, loc + 1));
                } else {
                    // Already a ghost, so nothing to undo.
                    break examineEntry;
                }

                if ((header & ENTRY_FRAGMENTED) != 0) {
                    int valueStartLoc = valueHeaderLoc + 2 + ((header & 0x20) >> 5);
                    tree.mDatabase.fragmentedTrash().add
                        (txn, tree.mId, page,
                         entryLoc, valueHeaderLoc - entryLoc,  // keyStart, keyLen
                         valueStartLoc, loc - valueStartLoc);  // valueStart, valueLen
                    // Clearing the fragmented bit prevents the update from
                    // double-deleting the fragments, and it also allows the
                    // old entry slot to be re-used.
                    p_bytePut(page, valueHeaderLoc, header & ~ENTRY_FRAGMENTED);
                    return;
                }
            }
        }

        // Copy whole entry into undo log.
        txn.pushUndoStore(tree.mId, UndoLog.OP_UNUPDATE, page, entryLoc, loc - entryLoc);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    long retrieveChildRefId(int pos) {
        return p_uint48GetLE(mPage, searchVecEnd() + 2 + (pos << 2));
    }

    /**
     * Retrieves the count of entries for the child node at the given position, or negative if
     * unknown. Counts are only applicable to bottom internal nodes, and are invalidated when
     * the node is dirty.
     *
     * @param pos position as provided by binarySearch; must be positive
     */
    int retrieveChildEntryCount(int pos) {
        return p_ushortGetLE(mPage, searchVecEnd() + (2 + 6) + (pos << 2)) - 1;
    }

    /**
     * Stores the count of entries for the child node at the given position. Counts are only
     * applicable to bottom internal nodes, and are invalidated when the node is dirty.
     *
     * @param pos position as provided by binarySearch; must be positive
     * @param count 0..65534
     * @see #countNonGhostKeys
     */
    void storeChildEntryCount(int pos, int count) {
        if (count < 65535) { // safety check
            p_shortPutLE(mPage, searchVecEnd() + (2 + 6) + (pos << 2), count + 1);
        }
    }

    /**
     * @return length of encoded entry at given location
     */
    static int leafEntryLengthAtLoc(/*P*/ byte[] page, final int entryLoc) {
        int loc = entryLoc + keyLengthAtLoc(page, entryLoc);
        int header = p_byteGet(page, loc++);
        if (header >= 0) {
            loc += header;
        } else {
            if ((header & 0x20) == 0) {
                loc += 2 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc));
            } else if (header != -1) {
                loc += 3 + (((header & 0x0f) << 16)
                            | (p_ubyteGet(page, loc) << 8) | p_ubyteGet(page, loc + 1));
            }
        }
        return loc - entryLoc;
    }

    /**
     * @return length of encoded key at given location, including the header
     */
    static int keyLengthAtLoc(/*P*/ byte[] page, final int keyLoc) {
        int header = p_byteGet(page, keyLoc);
        return (header >= 0 ? header
                : (((header & 0x3f) << 8) | p_ubyteGet(page, keyLoc + 1))) + 2;
    }

    /**
     * @param pos complement of position as provided by binarySearch; must be positive
     * @param okey original key
     */
    void insertLeafEntry(Tree tree, int pos, byte[] okey, byte[] value) throws IOException {
        byte[] akey = okey;
        int encodedKeyLen = calculateAllowedKeyLength(tree, okey);

        if (encodedKeyLen < 0) {
            // Key must be fragmented.
            akey = tree.fragmentKey(okey);
            encodedKeyLen = 2 + akey.length;
        }

        try {
            int encodedLen = encodedKeyLen + calculateLeafValueLength(value);

            int vfrag;
            if (encodedLen <= tree.mMaxEntrySize) {
                vfrag = 0;
            } else {
                Database db = tree.mDatabase;
                value = db.fragment(value, value.length,
                                    db.mMaxFragmentedEntrySize - encodedKeyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);
                vfrag = ENTRY_FRAGMENTED;
            }

            try {
                int entryLoc = createLeafEntry(tree, pos, encodedLen);

                if (entryLoc < 0) {
                    splitLeafAndCreateEntry(tree, okey, akey, vfrag, value, encodedLen, pos, true);
                } else {
                    copyToLeafEntry(okey, akey, vfrag, value, entryLoc);
                }
            } catch (Throwable e) {
                if (vfrag == ENTRY_FRAGMENTED) {
                    cleanupFragments(e, value);
                }
                throw e;
            }
        } catch (Throwable e) {
            if (okey != akey) {
                cleanupFragments(e, akey);
            }
            throw e;
        }
    }

    /**
     * @param pos complement of position as provided by binarySearch; must be positive
     * @param okey original key
     */
    void insertBlankLeafEntry(Tree tree, int pos, byte[] okey, long vlength) throws IOException {
        byte[] akey = okey;
        int encodedKeyLen = calculateAllowedKeyLength(tree, okey);

        if (encodedKeyLen < 0) {
            // Key must be fragmented.
            akey = tree.fragmentKey(okey);
            encodedKeyLen = 2 + akey.length;
        }

        try {
            long longEncodedLen = encodedKeyLen + calculateLeafValueLength(vlength);
            int encodedLen;

            int vfrag;
            byte[] value;
            if (longEncodedLen <= tree.mMaxEntrySize) {
                vfrag = 0;
                value = new byte[(int) vlength];
                encodedLen = (int) longEncodedLen;
            } else {
                Database db = tree.mDatabase;
                value = db.fragment(null, vlength, db.mMaxFragmentedEntrySize - encodedKeyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);
                vfrag = ENTRY_FRAGMENTED;
            }

            try {
                int entryLoc = createLeafEntry(tree, pos, encodedLen);

                if (entryLoc < 0) {
                    splitLeafAndCreateEntry(tree, okey, akey, vfrag, value, encodedLen, pos, true);
                } else {
                    copyToLeafEntry(okey, akey, vfrag, value, entryLoc);
                }
            } catch (Throwable e) {
                if (vfrag == ENTRY_FRAGMENTED) {
                    cleanupFragments(e, value);
                }
                throw e;
            }
        } catch (Throwable e) {
            if (okey != akey) {
                cleanupFragments(e, akey);
            }
            throw e;
        }
    }

    /**
     * @param pos complement of position as provided by binarySearch; must be positive
     * @param okey original key
     */
    void insertFragmentedLeafEntry(Tree tree, int pos, byte[] okey, byte[] value)
        throws IOException
    {
        byte[] akey = okey;
        int encodedKeyLen = calculateAllowedKeyLength(tree, okey);

        if (encodedKeyLen < 0) {
            // Key must be fragmented.
            akey = tree.fragmentKey(okey);
            encodedKeyLen = 2 + akey.length;
        }

        try {
            int encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);

            int entryLoc = createLeafEntry(tree, pos, encodedLen);

            if (entryLoc < 0) {
                splitLeafAndCreateEntry
                    (tree, okey, akey, ENTRY_FRAGMENTED, value, encodedLen, pos, true);
            } else {
                copyToLeafEntry(okey, akey, ENTRY_FRAGMENTED, value, entryLoc);
            }
        } catch (Throwable e) {
            if (okey != akey) {
                cleanupFragments(e, akey);
            }
            throw e;
        }
    }

    private void panic(Throwable cause) {
        try {
            getDatabase().close(cause);
        } catch (Throwable e) {
            // Ignore.
        }
    }

    private void cleanupFragments(Throwable cause, byte[] fragmented) {
        if (fragmented != null) {
            /*P*/ byte[] copy = p_transfer(fragmented);
            try {
                getDatabase().deleteFragments(copy, 0, p_length(copy));
            } catch (Throwable e) {
                cause.addSuppressed(e);
                panic(cause);
            } finally {
                p_delete(copy);
            }
        }
    }

    /**
     * @param pos complement of position as provided by binarySearch; must be positive
     * @return Location for newly allocated entry, already pointed to by search
     * vector, or negative if leaf must be split. Complement of negative value
     * is maximum space available.
     */
    int createLeafEntry(Tree tree, int pos, final int encodedLen) {
        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd - 1;

        final /*P*/ byte[] page = mPage;

        int entryLoc;
        alloc: {
            if (pos < ((searchVecEnd - searchVecStart + 2) >> 1)) {
                // Shift subset of search vector left or prepend.
                if ((leftSpace -= 2) >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    p_copy(page, searchVecStart, page, searchVecStart -= 2, pos);
                    pos += searchVecStart;
                    searchVecStart(searchVecStart);
                    break alloc;
                }
                // Need to make space, but restore leftSpace value first.
                leftSpace += 2;
            } else {
                // Shift subset of search vector right or append.
                if ((rightSpace -= 2) >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    pos += searchVecStart;
                    p_copy(page, pos, page, pos + 2, (searchVecEnd += 2) - pos);
                    searchVecEnd(searchVecEnd);
                    break alloc;
                }
                // Need to make space, but restore rightSpace value first.
                rightSpace += 2;
            }

            // Compute remaining space surrounding search vector after insert completes.
            int remaining = leftSpace + rightSpace - encodedLen - 2;

            if (garbage() > remaining) {
                compact: {
                    // Do full compaction and free up the garbage, or else node must be split.

                    if (garbage() + remaining < 0) {
                        // Node compaction won't make enough room, but attempt to rebalance
                        // before splitting.

                        TreeCursorFrame frame = mLastCursorFrame;
                        if (frame == null || (frame = frame.mParentFrame) == null) {
                            // No sibling nodes, so cannot rebalance.
                            break compact;
                        }

                        // "Randomly" choose left or right node first.
                        if ((mId & 1) == 0) {
                            int result = tryRebalanceLeafLeft
                                (tree, frame, pos, encodedLen, -remaining);
                            if (result == 0) {
                                // First rebalance attempt failed.
                                result = tryRebalanceLeafRight
                                    (tree, frame, pos, encodedLen, -remaining);
                                if (result == 0) {
                                    // Second rebalance attempt failed too, so split.
                                    break compact;
                                } else if (result > 0) {
                                    return result;
                                }
                            } else if (result > 0) {
                                return result;
                            } else {
                                pos += result;
                            }
                        } else {
                            int result = tryRebalanceLeafRight
                                (tree, frame, pos, encodedLen, -remaining);
                            if (result == 0) {
                                // First rebalance attempt failed.
                                result = tryRebalanceLeafLeft
                                    (tree, frame, pos, encodedLen, -remaining);
                                if (result == 0) {
                                    // Second rebalance attempt failed too, so split.
                                    break compact;
                                } else if (result > 0) {
                                    return result;
                                } else {
                                    pos += result;
                                }
                            } else if (result > 0) {
                                return result;
                            }
                        }
                    }

                    return compactLeaf(encodedLen, pos, true);
                }

                // Determine max possible entry size allowed, accounting too for entry pointer,
                // key length, and value length. Key and value length might only require only
                // require 1 byte fields, but be safe and choose the larger size of 2.
                int max = garbage() + leftSpace + rightSpace - (2 + 2 + 2);
                return max <= 0 ? -1 : ~max;
            }

            int vecLen = searchVecEnd - searchVecStart + 2;
            int newSearchVecStart;

            if (remaining > 0 || (rightSegTail() & 1) != 0) {
                // Re-center search vector, biased to the right, ensuring proper alignment.
                newSearchVecStart = (rightSegTail() - vecLen + (1 - 2) - (remaining >> 1)) & ~1;

                // Allocate entry from left segment.
                entryLoc = leftSegTail();
                leftSegTail(entryLoc + encodedLen);
            } else if ((leftSegTail() & 1) == 0) {
                // Move search vector left, ensuring proper alignment.
                newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                // Allocate entry from right segment.
                entryLoc = rightSegTail() - encodedLen + 1;
                rightSegTail(entryLoc - 1);
            } else {
                // Search vector is misaligned, so do full compaction.
                return compactLeaf(encodedLen, pos, true);
            }

            p_copies(page,
                     searchVecStart, newSearchVecStart, pos,
                     searchVecStart + pos, newSearchVecStart + pos + 2, vecLen - pos);

            pos += newSearchVecStart;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecStart + vecLen);
        }

        // Write pointer to new allocation.
        p_shortPutLE(page, pos, entryLoc);
        return entryLoc;
    }

    /**
     * Attempt to make room in this node by moving entries to the left sibling node. First
     * determines if moving entries to the left node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param pos position to insert into; this position cannot move left
     * @param insertLen encoded length of entry to insert
     * @param minAmount minimum amount of bytes to move to make room
     * @return 0 if try failed, or entry location of re-used slot, or negative 2-based position
     * decrement if no slot was found
     */
    private int tryRebalanceLeafLeft(Tree tree, TreeCursorFrame parentFrame,
                                     int pos, int insertLen, int minAmount)
    {
        final /*P*/ byte[] rightPage = mPage;

        int moveAmount = 0;
        final int lastSearchVecLoc;
        int insertLoc = 0;
        int insertSlack = Integer.MAX_VALUE;

        check: {
            int searchVecLoc = searchVecStart();
            int searchVecEnd = searchVecLoc + pos - 2;

            // Note that loop doesn't examine last entry. At least one must remain.
            for (; searchVecLoc < searchVecEnd; searchVecLoc += 2) {
                int entryLoc = p_ushortGetLE(rightPage, searchVecLoc);
                int encodedLen = leafEntryLengthAtLoc(rightPage, entryLoc);

                // Find best fitting slot for insert entry.
                int slack = encodedLen - insertLen;
                if (slack >= 0 && slack < insertSlack) {
                    insertLoc = entryLoc;
                    insertSlack = slack;
                }

                moveAmount += encodedLen + 2;
                if (moveAmount >= minAmount && insertLoc != 0) {
                    lastSearchVecLoc = searchVecLoc + 2; // +2 to be exclusive
                    break check;
                }
            }

            return 0;
        }

        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return 0;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos <= 0
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No left child or sanity checks failed.
            parent.releaseExclusive();
            return 0;
        }

        final Node left;
        try {
            left = parent.tryLatchChildNotSplit(childPos - 2);
        } catch (IOException e) {
            return 0;
        }

        if (left == null) {
            parent.releaseExclusive();
            return 0;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final byte[] newKey;
        final int newKeyLen;
        final /*P*/ byte[] parentPage;
        final int parentKeyLoc;
        final int parentKeyGrowth;

        check: {
            try {
                int leftAvail = left.availableLeafBytes();
                if (leftAvail >= moveAmount) {
                    // Parent search key will be updated, so verify that it has room.
                    int highPos = lastSearchVecLoc - searchVecStart();
                    newKey = midKey(highPos - 2, this, highPos);
                    // Only attempt rebalance if new key doesn't need to be fragmented.
                    newKeyLen = calculateAllowedKeyLength(tree, newKey);
                    if (newKeyLen > 0) {
                        parentPage = parent.mPage;
                        parentKeyLoc = p_ushortGetLE
                            (parentPage, parent.searchVecStart() + childPos - 2);
                        parentKeyGrowth = newKeyLen - keyLengthAtLoc(parentPage, parentKeyLoc);
                        if (parentKeyGrowth <= 0 ||
                            parentKeyGrowth <= parent.availableInternalBytes())
                        {
                            // Parent has room for the new search key, so proceed with rebalancing.
                            break check;
                        }
                    }
                }
            } catch (IOException e) {
                // Caused by failed read of a large key. Abort the rebalance attempt.
            }
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        try {
            if (tree.mDatabase.markDirty(tree, left)) {
                parent.updateChildRefId(childPos - 2, left.mId);
            }
        } catch (IOException e) {
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        // Update the parent key.
        if (parentKeyGrowth <= 0) {
            encodeNormalKey(newKey, parentPage, parentKeyLoc);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKey(childPos - 2, parentKeyGrowth, newKey, newKeyLen);
        }

        int garbageAccum = 0;
        int searchVecLoc = searchVecStart();
        final int lastPos = lastSearchVecLoc - searchVecLoc;

        for (; searchVecLoc < lastSearchVecLoc; searchVecLoc += 2) {
            int entryLoc = p_ushortGetLE(rightPage, searchVecLoc);
            int encodedLen = leafEntryLengthAtLoc(rightPage, entryLoc);
            int leftEntryLoc = left.createLeafEntry(tree, left.highestLeafPos() + 2, encodedLen);
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(rightPage, entryLoc, left.mPage, leftEntryLoc, encodedLen);
            garbageAccum += encodedLen;
        }

        garbage(garbage() + garbageAccum);
        searchVecStart(lastSearchVecLoc);

        // Fix cursor positions or move them to the left node.
        final int leftEndPos = left.highestLeafPos() + 2;
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            TreeCursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            int mask = framePos >> 31;
            int newPos = (framePos ^ mask) - lastPos;
            // This checks for nodes which should move and also includes not-found frames at
            // the low position. They might need to move just higher than the left node high
            // position, because the parent key has changed. A new search would position the
            // search there. Note that tryRebalanceLeafRight has an identical check, after
            // applying De Morgan's law. Because the chosen parent node is not strictly the
            // lowest from the right, a comparison must be made to the actual new parent node.
            if (newPos < 0 |
                ((newPos == 0 & mask != 0) && compareUnsigned(frame.mNotFoundKey, newKey) < 0))
            {
                frame.unbind();
                frame.bind(left, (leftEndPos + newPos) ^ mask);
                frame.mParentFrame.mNodePos -= 2;
            } else {
                frame.mNodePos = newPos ^ mask;
            }
            frame = prev;
        }

        left.releaseExclusive();
        parent.releaseExclusive();

        /* Not possible unless aggressive compaction is allowed.
        if (insertLoc == 0) {
            return -lastPos;
        }
        */

        // Expand search vector for inserted entry and write pointer to the re-used slot.
        garbage(garbage() - insertLen);
        pos -= lastPos;
        int searchVecStart = searchVecStart();
        p_copy(rightPage, searchVecStart, rightPage, searchVecStart -= 2, pos);
        searchVecStart(searchVecStart);
        p_shortPutLE(rightPage, searchVecStart + pos, insertLoc);
        return insertLoc;
    }

    /**
     * Attempt to make room in this node by moving entries to the right sibling node. First
     * determines if moving entries to the right node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param pos position to insert into; this position cannot move right
     * @param insertLen encoded length of entry to insert
     * @param minAmount minimum amount of bytes to move to make room
     * @return 0 if try failed, or entry location of re-used slot, or negative if no slot was found
     */
    private int tryRebalanceLeafRight(Tree tree, TreeCursorFrame parentFrame,
                                      int pos, int insertLen, int minAmount)
    {
        final /*P*/ byte[] leftPage = mPage;

        int moveAmount = 0;
        final int firstSearchVecLoc;
        int insertLoc = 0;
        int insertSlack = Integer.MAX_VALUE;

        check: {
            int searchVecStart = searchVecStart() + pos;
            int searchVecLoc = searchVecEnd();

            // Note that loop doesn't examine first entry. At least one must remain.
            for (; searchVecLoc > searchVecStart; searchVecLoc -= 2) {
                int entryLoc = p_ushortGetLE(leftPage, searchVecLoc);
                int encodedLen = leafEntryLengthAtLoc(leftPage, entryLoc);

                // Find best fitting slot for insert entry.
                int slack = encodedLen - insertLen;
                if (slack >= 0 && slack < insertSlack) {
                    insertLoc = entryLoc;
                    insertSlack = slack;
                }

                moveAmount += encodedLen + 2;
                if (moveAmount >= minAmount && insertLoc != 0) {
                    firstSearchVecLoc = searchVecLoc;
                    break check;
                }
            }

            return 0;
        }

        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return 0;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos >= parent.highestInternalPos()
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No right child or sanity checks failed.
            parent.releaseExclusive();
            return 0;
        }

        final Node right;
        try {
            right = parent.tryLatchChildNotSplit(childPos + 2);
        } catch (IOException e) {
            return 0;
        }

        if (right == null) {
            parent.releaseExclusive();
            return 0;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final byte[] newKey;
        final int newKeyLen;
        final /*P*/ byte[] parentPage;
        final int parentKeyLoc;
        final int parentKeyGrowth;

        check: {
            try {
                int rightAvail = right.availableLeafBytes();
                if (rightAvail >= moveAmount) {
                    // Parent search key will be updated, so verify that it has room.
                    int highPos = firstSearchVecLoc - searchVecStart();
                    newKey = midKey(highPos - 2, this, highPos);
                    // Only attempt rebalance if new key doesn't need to be fragmented.
                    newKeyLen = calculateAllowedKeyLength(tree, newKey);
                    if (newKeyLen > 0) {
                        parentPage = parent.mPage;
                        parentKeyLoc = p_ushortGetLE
                            (parentPage, parent.searchVecStart() + childPos);
                        parentKeyGrowth = newKeyLen - keyLengthAtLoc(parentPage, parentKeyLoc);
                        if (parentKeyGrowth <= 0 ||
                            parentKeyGrowth <= parent.availableInternalBytes())
                        {
                            // Parent has room for the new search key, so proceed with rebalancing.
                            break check;
                        }
                    }
                }
            } catch (IOException e) {
                // Caused by failed read of a large key. Abort the rebalance attempt.
            }
            right.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        try {
            if (tree.mDatabase.markDirty(tree, right)) {
                parent.updateChildRefId(childPos + 2, right.mId);
            }
        } catch (IOException e) {
            right.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        // Update the parent key.
        if (parentKeyGrowth <= 0) {
            encodeNormalKey(newKey, parentPage, parentKeyLoc);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKey(childPos, parentKeyGrowth, newKey, newKeyLen);
        }

        int garbageAccum = 0;
        int searchVecLoc = searchVecEnd();
        final int moved = searchVecLoc - firstSearchVecLoc + 2;

        for (; searchVecLoc >= firstSearchVecLoc; searchVecLoc -= 2) {
            int entryLoc = p_ushortGetLE(leftPage, searchVecLoc);
            int encodedLen = leafEntryLengthAtLoc(leftPage, entryLoc);
            int rightEntryLoc = right.createLeafEntry(tree, 0, encodedLen);
            // Note: Must access right page each time, since compaction can replace it.
            p_copy(leftPage, entryLoc, right.mPage, rightEntryLoc, encodedLen);
            garbageAccum += encodedLen;
        }

        garbage(garbage() + garbageAccum);
        searchVecEnd(firstSearchVecLoc - 2);

        // Fix cursor positions in the right node.
        for (TreeCursorFrame frame = right.mLastCursorFrame; frame != null; ) {
            int framePos = frame.mNodePos;
            int mask = framePos >> 31;
            frame.mNodePos = ((framePos ^ mask) + moved) ^ mask;
            frame = frame.mPrevCousin;
        }

        // Move affected cursor frames to the right node.
        final int leftEndPos = firstSearchVecLoc - searchVecStart();
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            TreeCursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            int mask = framePos >> 31;
            int newPos = (framePos ^ mask) - leftEndPos;
            // This checks for nodes which should move, but it excludes not-found frames at the
            // high position. They might otherwise move to position zero of the right node, but
            // the parent key has changed. A new search would position the frame just beyond
            // the high position of the left node, which is where it is now. Note that
            // tryRebalanceLeafLeft has an identical check, after applying De Morgan's law.
            // Because the chosen parent node is not strictly the lowest from the right, a
            // comparison must be made to the actual new parent node.
            if (newPos >= 0 &
                ((newPos != 0 | mask == 0) || compareUnsigned(frame.mNotFoundKey, newKey) >= 0))
            {
                frame.unbind();
                frame.bind(right, newPos ^ mask);
                frame.mParentFrame.mNodePos += 2;
            }
            frame = prev;
        }

        right.releaseExclusive();
        parent.releaseExclusive();

        /* Not possible unless aggressive compaction is allowed.
        if (insertLoc == 0) {
            return -1;
        }
        */

        // Expand search vector for inserted entry and write pointer to the re-used slot.
        garbage(garbage() - insertLen);
        pos += searchVecStart();
        int newSearchVecEnd = searchVecEnd() + 2;
        p_copy(leftPage, pos, leftPage, pos + 2, newSearchVecEnd - pos);
        searchVecEnd(newSearchVecEnd);
        p_shortPutLE(leftPage, pos, insertLoc);
        return insertLoc;
    }

    /**
     * Insert into an internal node following a child node split. This parent node and child
     * node must have an exclusive latch held. Parent and child latch are always released, even
     * if an exception is thrown.
     *
     * @param keyPos position to insert split key
     * @param splitChild child node which split
     */
    void insertSplitChildRef(Tree tree, int keyPos, Node splitChild)
        throws IOException
    {
        final Split split = splitChild.mSplit;
        final Node newChild = splitChild.rebindSplitFrames(split);
        try {
            splitChild.mSplit = null;

            //final Node leftChild;
            final Node rightChild;
            int newChildPos = keyPos >> 1;
            if (split.mSplitRight) {
                //leftChild = splitChild;
                rightChild = newChild;
                newChildPos++;
            } else {
                //leftChild = newChild;
                rightChild = splitChild;
            }

            // Positions of frames higher than split key need to be incremented.
            for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
                int framePos = frame.mNodePos;
                if (framePos > keyPos) {
                    frame.mNodePos = framePos + 2;
                }
                frame = frame.mPrevCousin;
            }

            // Positions of frames equal to split key are in the split itself. Only
            // frames for the right split need to be incremented.
            for (TreeCursorFrame childFrame = rightChild.mLastCursorFrame; childFrame != null; ) {
                TreeCursorFrame frame = childFrame.mParentFrame;
                if (frame.mNode != this) {
                    throw new AssertionError("Invalid cursor frame parent: " + frame.mNode
                                             + ", " + this + ", " + newChild);
                }
                frame.mNodePos += 2;
                childFrame = childFrame.mPrevCousin;
            }

            // Note: Invocation of createInternalEntry may cause splitInternal to be called,
            // which in turn might throw a recoverable exception. State changes can be undone
            // by decrementing the incremented frame positions, and then by undoing the
            // rebindSplitFrames call. However, this would create an orphaned child node.
            // Panicking the database is the safest option.

            InResult result = new InResult();
            try {
                createInternalEntry
                    (result, tree, keyPos, split.splitKeyEncodedLength(), newChildPos << 3, true);
            } catch (Throwable e) {
                panic(e);
                throw e;
            }

            // Write new child id.
            p_longPutLE(result.mPage, result.mNewChildLoc, newChild.mId);

            int entryLoc = result.mEntryLoc;
            if (entryLoc < 0) {
                // If loc is negative, then node was split and new key was chosen to be promoted.
                // It must be written into the new split.
                mSplit.setKey(split);
            } else {
                // Write key entry itself.
                split.copySplitKeyToParent(result.mPage, entryLoc);
            }
        } catch (Throwable e) {
            splitChild.releaseExclusive();
            newChild.releaseExclusive();
            releaseExclusive();
            throw e;
        }
        
        splitChild.releaseExclusive();
        newChild.releaseExclusive();

        try {
            // Split complete, so allow new node to be evictable.
            newChild.makeEvictable();
        } catch (Throwable e) {
            releaseExclusive();
            throw e;
        }
    }

    /**
     * Insert into an internal node following a child node split. This parent
     * node and child node must have an exclusive latch held. Child latch is
     * released, unless an exception is thrown.
     *
     * @param result return result stored here; if node was split, key and entry loc is -1 if
     * new key was promoted to parent
     * @param keyPos 2-based position
     * @param newChildPos 8-based position
     * @param allowSplit true if this internal node can be split as a side-effect
     * @throws AssertionError if entry must be split to make room but split is not allowed
     */
    private void createInternalEntry(InResult result,
                                     Tree tree, int keyPos, int encodedLen,
                                     int newChildPos, boolean allowSplit)
        throws IOException
    {
        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd
            - ((searchVecEnd - searchVecStart) << 2) - 17;

        /*P*/ byte[] page = mPage;

        int entryLoc;
        alloc: {
            // Need to make room for one new search vector entry (2 bytes) and one new child
            // id entry (8 bytes). Determine which shift operations minimize movement.
            if (newChildPos < ((3 * (searchVecEnd - searchVecStart + 2) + keyPos + 8) >> 1)) {
                // Attempt to shift search vector left by 10, shift child ids left by 8.

                if ((leftSpace -= 10) >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    p_copy(page, searchVecStart, page, searchVecStart - 10, keyPos);
                    p_copy(page, searchVecStart + keyPos,
                           page, searchVecStart + keyPos - 8,
                           searchVecEnd - searchVecStart + 2 - keyPos + newChildPos);
                    searchVecStart(searchVecStart -= 10);
                    keyPos += searchVecStart;
                    searchVecEnd(searchVecEnd -= 8);
                    newChildPos += searchVecEnd + 2;
                    break alloc;
                }

                // Need to make space, but restore leftSpace value first.
                leftSpace += 10;
            } else {
                // Attempt to shift search vector left by 2, shift child ids right by 8.

                leftSpace -= 2;
                rightSpace -= 8;

                if (leftSpace >= 0 && rightSpace >= 0 &&
                    (entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0)
                {
                    p_copy(page, searchVecStart, page, searchVecStart -= 2, keyPos);
                    searchVecStart(searchVecStart);
                    keyPos += searchVecStart;
                    p_copy(page, searchVecEnd + newChildPos + 2,
                           page, searchVecEnd + newChildPos + (2 + 8),
                           ((searchVecEnd - searchVecStart) << 2) + 8 - newChildPos);
                    newChildPos += searchVecEnd + 2;
                    break alloc;
                }

                // Need to make space, but restore space values first.
                leftSpace += 2;
                rightSpace += 8;
            }

            // Compute remaining space surrounding search vector after insert completes.
            int remaining = leftSpace + rightSpace - encodedLen - 10;

            if (garbage() > remaining) {
                compact: {
                    // Do full compaction and free up the garbage, or else node must be split.

                    if ((garbage() + remaining) < 0) {
                        // Node compaction won't make enough room, but attempt to rebalance
                        // before splitting.

                        TreeCursorFrame frame = mLastCursorFrame;
                        if (frame == null || (frame = frame.mParentFrame) == null) {
                            // No sibling nodes, so cannot rebalance.
                            break compact;
                        }
                        
                        // "Randomly" choose left or right node first.
                        if ((mId & 1) == 0) {
                            int adjust = tryRebalanceInternalLeft(tree, frame, keyPos, -remaining);
                            if (adjust == 0) {
                                // First rebalance attempt failed.
                                if (!tryRebalanceInternalRight(tree, frame, keyPos, -remaining)) {
                                    // Second rebalance attempt failed too, so split.
                                    break compact;
                                }
                            } else {
                                keyPos -= adjust;
                                newChildPos -= (adjust << 2);
                            }
                        } else if (!tryRebalanceInternalRight(tree, frame, keyPos, -remaining)) {
                            // First rebalance attempt failed.
                            int adjust = tryRebalanceInternalLeft(tree, frame, keyPos, -remaining);
                            if (adjust == 0) {
                                // Second rebalance attempt failed too, so split.
                                break compact;
                            } else {
                                keyPos -= adjust;
                                newChildPos -= (adjust << 2);
                            }
                        }
                    }

                    compactInternal(result, encodedLen, keyPos, newChildPos);
                    return;
                }

                // Node is full, so split it.

                if (!allowSplit) {
                    throw new AssertionError("Split not allowed");
                }

                // No side-effects if an IOException is thrown here.
                splitInternal(result, tree, encodedLen, keyPos, newChildPos);
                return;
            }

            int vecLen = searchVecEnd - searchVecStart + 2;
            int childIdsLen = (vecLen << 2) + 8;
            int newSearchVecStart;

            if (remaining > 0 || (rightSegTail() & 1) != 0) {
                // Re-center search vector, biased to the right, ensuring proper alignment.
                newSearchVecStart =
                    (rightSegTail() - vecLen - childIdsLen + (1 - 10) - (remaining >> 1)) & ~1;

                // Allocate entry from left segment.
                entryLoc = leftSegTail();
                leftSegTail(entryLoc + encodedLen);
            } else if ((leftSegTail() & 1) == 0) {
                // Move search vector left, ensuring proper alignment.
                newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                // Allocate entry from right segment.
                entryLoc = rightSegTail() - encodedLen + 1;
                rightSegTail(entryLoc - 1);
            } else {
                // Search vector is misaligned, so do full compaction.
                compactInternal(result, encodedLen, keyPos, newChildPos);
                return;
            }

            int newSearchVecEnd = newSearchVecStart + vecLen;

            p_copies(page,
                     // Move search vector up to new key position.
                     searchVecStart, newSearchVecStart, keyPos,

                     // Move search vector after new key position, to new child id position.
                     searchVecStart + keyPos,
                     newSearchVecStart + keyPos + 2,
                     vecLen - keyPos + newChildPos,

                     // Move search vector after new child id position.
                     searchVecEnd + 2 + newChildPos,
                     newSearchVecEnd + 10 + newChildPos,
                     childIdsLen - newChildPos);

            keyPos += newSearchVecStart;
            newChildPos += newSearchVecEnd + 2;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecEnd);
        }

        // Write pointer to key entry.
        p_shortPutLE(page, keyPos, entryLoc);

        result.mPage = page;
        result.mNewChildLoc = newChildPos;
        result.mEntryLoc = entryLoc;
    }

    /**
     * Attempt to make room in this node by moving entries to the left sibling node. First
     * determines if moving entries to the left node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param keyPos position to insert into; this position cannot move left
     * @param minAmount minimum amount of bytes to move to make room
     * @return 2-based position increment; 0 if try failed
     */
    private int tryRebalanceInternalLeft(Tree tree, TreeCursorFrame parentFrame,
                                         int keyPos, int minAmount)
    {
        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return 0;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos <= 0
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No left child or sanity checks failed.
            parent.releaseExclusive();
            return 0;
        }

        final /*P*/ byte[] parentPage = parent.mPage;
        final /*P*/ byte[] rightPage = mPage;

        int rightShrink = 0;
        int leftGrowth = 0;

        final int lastSearchVecLoc;

        check: {
            int searchVecLoc = searchVecStart();
            int searchVecEnd = searchVecLoc + keyPos - 2;

            // Note that loop doesn't examine last entry. At least one must remain.
            for (; searchVecLoc < searchVecEnd; searchVecLoc += 2) {
                int keyLoc = p_ushortGetLE(rightPage, searchVecLoc);
                int len = keyLengthAtLoc(rightPage, keyLoc) + (2 + 8);

                rightShrink += len;
                leftGrowth += len;

                if (rightShrink >= minAmount) {
                    lastSearchVecLoc = searchVecLoc;

                    // Leftmost key to move comes from the parent, and first moved key in the
                    // right node does not affect left node growth.
                    leftGrowth -= len;
                    keyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos - 2);
                    leftGrowth += keyLengthAtLoc(parentPage, keyLoc) + (2 + 8);

                    break check;
                }
            }

            parent.releaseExclusive();
            return 0;
        }

        final Node left;
        try {
            left = parent.tryLatchChildNotSplit(childPos - 2);
        } catch (IOException e) {
            return 0;
        }

        if (left == null) {
            parent.releaseExclusive();
            return 0;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final int searchKeyLoc;
        final int searchKeyLen;
        final int parentKeyLoc;
        final int parentKeyLen;
        final int parentKeyGrowth;

        check: {
            int leftAvail = left.availableInternalBytes();
            if (leftAvail >= leftGrowth) {
                // Parent search key will be updated, so verify that it has room.
                searchKeyLoc = p_ushortGetLE(rightPage, lastSearchVecLoc);
                searchKeyLen = keyLengthAtLoc(rightPage, searchKeyLoc);
                parentKeyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos - 2);
                parentKeyLen = keyLengthAtLoc(parentPage, parentKeyLoc);
                parentKeyGrowth = searchKeyLen - parentKeyLen;
                if (parentKeyGrowth <= 0 || parentKeyGrowth <= parent.availableInternalBytes()) {
                    // Parent has room for the new search key, so proceed with rebalancing.
                    break check;
                }
            }
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        try {
            if (tree.mDatabase.markDirty(tree, left)) {
                parent.updateChildRefId(childPos - 2, left.mId);
            }
        } catch (IOException e) {
            left.releaseExclusive();
            parent.releaseExclusive();
            return 0;
        }

        int garbageAccum = searchKeyLen;
        int searchVecLoc = searchVecStart();
        final int moved = lastSearchVecLoc - searchVecLoc + 2;

        try {
            // Leftmost key to move comes from the parent.
            int pos = left.highestInternalPos();
            InResult result = new InResult();
            left.createInternalEntry(result, tree, pos, parentKeyLen, (pos + 2) << 2, false);
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(parentPage, parentKeyLoc, left.mPage, result.mEntryLoc, parentKeyLen);

            // Remaining keys come from the right node.
            for (; searchVecLoc < lastSearchVecLoc; searchVecLoc += 2) {
                int keyLoc = p_ushortGetLE(rightPage, searchVecLoc);
                int encodedLen = keyLengthAtLoc(rightPage, keyLoc);
                pos = left.highestInternalPos();
                left.createInternalEntry(result, tree, pos, encodedLen, (pos + 2) << 2, false);
                // Note: Must access left page each time, since compaction can replace it.
                p_copy(rightPage, keyLoc, left.mPage, result.mEntryLoc, encodedLen);
                garbageAccum += encodedLen;
            }
        } catch (IOException e) {
            // Can only be caused by node split, but this is not possible.
            throw rethrow(e);
        }

        // Update the parent key after moving it to the left node.
        if (parentKeyGrowth <= 0) {
            p_copy(rightPage, searchKeyLoc, parentPage, parentKeyLoc, searchKeyLen);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKeyEncoded
                (childPos - 2, parentKeyGrowth, rightPage, searchKeyLoc, searchKeyLen);
        }

        // Move encoded child pointers.
        {
            int start = searchVecEnd() + 2;
            int len = moved << 2;
            int end = left.searchVecEnd();
            end = end + ((end - left.searchVecStart()) << 2) + (2 + 16) - len;
            p_copy(rightPage, start, left.mPage, end, len);
            p_copy(rightPage, start + len, rightPage, start, (start - lastSearchVecLoc) << 2);
        }

        garbage(garbage() + garbageAccum);
        searchVecStart(lastSearchVecLoc + 2);

        // Fix cursor positions or move them to the left node.
        final int leftEndPos = left.highestInternalPos() + 2;
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            TreeCursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            int newPos = framePos - moved;
            if (newPos < 0) {
                frame.unbind();
                frame.bind(left, leftEndPos + newPos);
                frame.mParentFrame.mNodePos -= 2;
            } else {
                frame.mNodePos = newPos;
            }
            frame = prev;
        }

        left.releaseExclusive();
        parent.releaseExclusive();

        return moved;
    }

    /**
     * Attempt to make room in this node by moving entries to the right sibling node. First
     * determines if moving entries to the right node is allowed and would free up enough space.
     * Next, attempts to latch parent and child nodes without waiting, avoiding deadlocks.
     *
     * @param tree required
     * @param parentFrame required
     * @param keyPos position to insert into; this position cannot move right
     * @param minAmount minimum amount of bytes to move to make room
     */
    private boolean tryRebalanceInternalRight(Tree tree, TreeCursorFrame parentFrame,
                                              int keyPos, int minAmount)
    {
        final Node parent = parentFrame.tryAcquireExclusive();
        if (parent == null) {
            return false;
        }

        final int childPos = parentFrame.mNodePos;
        if (childPos >= parent.highestInternalPos()
            || parent.mSplit != null
            || parent.mCachedState != mCachedState)
        {
            // No right child or sanity checks failed.
            parent.releaseExclusive();
            return false;
        }

        final /*P*/ byte[] parentPage = parent.mPage;
        final /*P*/ byte[] leftPage = mPage;

        int leftShrink = 0;
        int rightGrowth = 0;

        final int firstSearchVecLoc;

        check: {
            int searchVecStart = searchVecStart() + keyPos;
            int searchVecLoc = searchVecEnd();

            // Note that loop doesn't examine first entry. At least one must remain.
            for (; searchVecLoc > searchVecStart; searchVecLoc -= 2) {
                int keyLoc = p_ushortGetLE(leftPage, searchVecLoc);
                int len = keyLengthAtLoc(leftPage, keyLoc) + (2 + 8);

                leftShrink += len;
                rightGrowth += len;

                if (leftShrink >= minAmount) {
                    firstSearchVecLoc = searchVecLoc;

                    // Rightmost key to move comes from the parent, and first moved key in the
                    // left node does not affect right node growth.
                    rightGrowth -= len;
                    keyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos);
                    rightGrowth += keyLengthAtLoc(parentPage, keyLoc) + (2 + 8);

                    break check;
                }
            }

            parent.releaseExclusive();
            return false;
        }

        final Node right;
        try {
            right = parent.tryLatchChildNotSplit(childPos + 2);
        } catch (IOException e) {
            return false;
        }

        if (right == null) {
            parent.releaseExclusive();
            return false;
        }

        // Notice that try-finally pattern is not used to release the latches. An uncaught
        // exception can only be caused by a bug. Leaving the latches held prevents database
        // corruption from being persisted.

        final int searchKeyLoc;
        final int searchKeyLen;
        final int parentKeyLoc;
        final int parentKeyLen;
        final int parentKeyGrowth;

        check: {
            int rightAvail = right.availableInternalBytes();
            if (rightAvail >= rightGrowth) {
                // Parent search key will be updated, so verify that it has room.
                searchKeyLoc = p_ushortGetLE(leftPage, firstSearchVecLoc);
                searchKeyLen = keyLengthAtLoc(leftPage, searchKeyLoc);
                parentKeyLoc = p_ushortGetLE(parentPage, parent.searchVecStart() + childPos);
                parentKeyLen = keyLengthAtLoc(parentPage, parentKeyLoc);
                parentKeyGrowth = searchKeyLen - parentKeyLen;
                if (parentKeyGrowth <= 0 || parentKeyGrowth <= parent.availableInternalBytes()) {
                    // Parent has room for the new search key, so proceed with rebalancing.
                    break check;
                }
            }
            right.releaseExclusive();
            parent.releaseExclusive();
            return false;
        }

        try {
            if (tree.mDatabase.markDirty(tree, right)) {
                parent.updateChildRefId(childPos + 2, right.mId);
            }
        } catch (IOException e) {
            right.releaseExclusive();
            parent.releaseExclusive();
            return false;
        }

        int garbageAccum = searchKeyLen;
        int searchVecLoc = searchVecEnd();
        final int moved = searchVecLoc - firstSearchVecLoc + 2;

        try {
            // Rightmost key to move comes from the parent.
            InResult result = new InResult();
            right.createInternalEntry(result, tree, 0, parentKeyLen, 0, false);
            // Note: Must access right page each time, since compaction can replace it.
            p_copy(parentPage, parentKeyLoc, right.mPage, result.mEntryLoc, parentKeyLen);

            // Remaining keys come from the left node.
            for (; searchVecLoc > firstSearchVecLoc; searchVecLoc -= 2) {
                int keyLoc = p_ushortGetLE(leftPage, searchVecLoc);
                int encodedLen = keyLengthAtLoc(leftPage, keyLoc);
                right.createInternalEntry(result, tree, 0, encodedLen, 0, false);
                // Note: Must access right page each time, since compaction can replace it.
                p_copy(leftPage, keyLoc, right.mPage, result.mEntryLoc, encodedLen);
                garbageAccum += encodedLen;
            }
        } catch (IOException e) {
            // Can only be caused by node split, but this is not possible.
            throw rethrow(e);
        }

        // Update the parent key after moving it to the right node.
        if (parentKeyGrowth <= 0) {
            p_copy(leftPage, searchKeyLoc, parentPage, parentKeyLoc, searchKeyLen);
            parent.garbage(parent.garbage() - parentKeyGrowth);
        } else {
            parent.updateInternalKeyEncoded
                (childPos, parentKeyGrowth, leftPage, searchKeyLoc, searchKeyLen);
        }

        // Move encoded child pointers.
        {
            int start = searchVecEnd() + 2;
            int len = ((start - searchVecStart()) << 2) + 8 - (moved << 2);
            p_copy(leftPage, start, leftPage, start - moved, len);
            p_copy(leftPage, start + len, right.mPage, right.searchVecEnd() + 2, moved << 2);
        }

        garbage(garbage() + garbageAccum);
        searchVecEnd(firstSearchVecLoc - 2);

        // Fix cursor positions in the right node.
        for (TreeCursorFrame frame = right.mLastCursorFrame; frame != null; ) {
            frame.mNodePos += moved;
            frame = frame.mPrevCousin;
        }

        // Move affected cursor frames to the right node.
        final int adjust = firstSearchVecLoc - searchVecStart() + 4;
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            TreeCursorFrame prev = frame.mPrevCousin;
            int newPos = frame.mNodePos - adjust;
            if (newPos >= 0) {
                frame.unbind();
                frame.bind(right, newPos);
                frame.mParentFrame.mNodePos += 2;
            }
            frame = prev;
        }

        right.releaseExclusive();
        parent.releaseExclusive();

        return true;
    }

    /**
     * Rebind cursor frames affected by split to correct node and
     * position. Caller must hold exclusive latch.
     *
     * @return latched sibling
     */
    private Node rebindSplitFrames(Split split) {
        final Node sibling = split.latchSibling();
        try {
            for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
                // Capture previous frame from linked list before changing the links.
                TreeCursorFrame prev = frame.mPrevCousin;
                split.rebindFrame(frame, sibling);
                frame = prev;
            }
            return sibling;
        } catch (Throwable e) {
            sibling.releaseExclusive();
            throw e;
        }
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     * @param vfrag 0 or ENTRY_FRAGMENTED
     */
    void updateLeafValue(Tree tree, int pos, int vfrag, byte[] value) throws IOException {
        /*P*/ byte[] page = mPage;
        final int searchVecStart = searchVecStart();

        final int start;
        final int keyLen;
        final int garbage;
        quick: {
            int loc;
            start = loc = p_ushortGetLE(page, searchVecStart + pos);
            loc += keyLengthAtLoc(page, loc);

            final int valueHeaderLoc = loc;

            // Note: Similar to leafEntryLengthAtLoc and retrieveLeafValueAtLoc.
            int len = p_byteGet(page, loc++);
            if (len < 0) largeValue: {
                int header;
                if ((len & 0x20) == 0) {
                    header = len;
                    len = 1 + (((len & 0x1f) << 8) | p_ubyteGet(page, loc++));
                } else if (len != -1) {
                    header = len;
                    len = 1 + (((len & 0x0f) << 16)
                               | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
                } else {
                    // ghost
                    len = 0;
                    break largeValue;
                }
                if ((header & ENTRY_FRAGMENTED) != 0) {
                    tree.mDatabase.deleteFragments(page, loc, len);
                    // TODO: If new value needs to be fragmented too, try to
                    // re-use existing value slot.
                    if (vfrag == 0) {
                        // Clear fragmented bit in case new value can be quick copied.
                        p_bytePut(page, valueHeaderLoc, header & ~ENTRY_FRAGMENTED);
                    }
                }
            }

            final int valueLen = value.length;
            if (valueLen > len) {
                // Old entry is too small, and so it becomes garbage.
                keyLen = valueHeaderLoc - start;
                garbage = garbage() + loc + len - start;
                break quick;
            }

            if (valueLen == len) {
                // Quick copy with no garbage created.
                if (valueLen == 0) {
                    // Ensure ghost is replaced.
                    p_bytePut(page, valueHeaderLoc, 0);
                } else {
                    p_copyFromArray(value, 0, page, loc, valueLen);
                    if (vfrag != 0) {
                        p_bytePut(page, valueHeaderLoc, p_byteGet(page, valueHeaderLoc) | vfrag);
                    }
                }
            } else {
                garbage(garbage() + loc + len - copyToLeafValue
                        (page, vfrag, value, valueHeaderLoc) - valueLen);
            }

            return;
        }

        // What follows is similar to createLeafEntry method, except the search
        // vector doesn't grow.

        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd - 1;

        final int vfragOriginal = vfrag;

        int encodedLen;
        if (vfrag != 0) {
            encodedLen = keyLen + calculateFragmentedValueLength(value);
        } else {
            encodedLen = keyLen + calculateLeafValueLength(value);
            if (encodedLen > tree.mMaxEntrySize) {
                Database db = tree.mDatabase;
                value = db.fragment(value, value.length, db.mMaxFragmentedEntrySize - keyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                encodedLen = keyLen + calculateFragmentedValueLength(value);
                vfrag = ENTRY_FRAGMENTED;
            }
        }

        int entryLoc;
        alloc: try {
            if ((entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0) {
                pos += searchVecStart;
                break alloc;
            }

            // Compute remaining space surrounding search vector after update completes.
            int remaining = leftSpace + rightSpace - encodedLen;

            if (garbage > remaining) {
                // Do full compaction and free up the garbage, or split the node.

                byte[][] akeyRef = new byte[1][];
                int loc = p_ushortGetLE(page, searchVecStart + pos);
                boolean isOriginal = retrieveActualKeyAtLoc(page, loc, akeyRef);
                byte[] akey = akeyRef[0];

                if ((garbage + remaining) < 0) {
                    if (mSplit == null) {
                        // Node is full, so split it.
                        byte[] okey = isOriginal ? akey : retrieveKeyAtLoc(this, page, loc);
                        splitLeafAndCreateEntry
                            (tree, okey, akey, vfrag, value, encodedLen, pos, false);
                        return;
                    }

                    // Node is already split, and so value is too large.
                    if (vfrag != 0) {
                        // FIXME: Can this happen?
                        throw new DatabaseException("Fragmented entry doesn't fit");
                    }
                    Database db = tree.mDatabase;
                    int max = Math.min(db.mMaxFragmentedEntrySize,
                                       garbage + leftSpace + rightSpace);
                    value = db.fragment(value, value.length, max);
                    if (value == null) {
                        throw new AssertionError();
                    }
                    encodedLen = keyLen + calculateFragmentedValueLength(value);
                    vfrag = ENTRY_FRAGMENTED;
                }

                garbage(garbage);
                entryLoc = compactLeaf(encodedLen, pos, false);
                page = mPage;
                entryLoc = isOriginal ? encodeNormalKey(akey, page, entryLoc)
                    : encodeFragmentedKey(akey, page, entryLoc);
                copyToLeafValue(page, vfrag, value, entryLoc);
                return;
            }

            int vecLen = searchVecEnd - searchVecStart + 2;
            int newSearchVecStart;

            if (remaining > 0 || (rightSegTail() & 1) != 0) {
                // Re-center search vector, biased to the right, ensuring proper alignment.
                newSearchVecStart = (rightSegTail() - vecLen + (1 - 0) - (remaining >> 1)) & ~1;

                // Allocate entry from left segment.
                entryLoc = leftSegTail();
                leftSegTail(entryLoc + encodedLen);
            } else if ((leftSegTail() & 1) == 0) {
                // Move search vector left, ensuring proper alignment.
                newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                // Allocate entry from right segment.
                entryLoc = rightSegTail() - encodedLen + 1;
                rightSegTail(entryLoc - 1);
            } else {
                // Search vector is misaligned, so do full compaction.
                byte[][] akeyRef = new byte[1][];
                int loc = p_ushortGetLE(page, searchVecStart + pos);
                boolean isOriginal = retrieveActualKeyAtLoc(page, loc, akeyRef);
                byte[] akey = akeyRef[0];

                garbage(garbage);
                entryLoc = compactLeaf(encodedLen, pos, false);
                page = mPage;
                entryLoc = isOriginal ? encodeNormalKey(akey, page, entryLoc)
                    : encodeFragmentedKey(akey, page, entryLoc);
                copyToLeafValue(page, vfrag, value, entryLoc);
                return;
            }

            p_copy(page, searchVecStart, page, newSearchVecStart, vecLen);

            pos += newSearchVecStart;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecStart + vecLen - 2);
        } catch (Throwable e) {
            if (vfrag == ENTRY_FRAGMENTED && vfragOriginal != ENTRY_FRAGMENTED) {
                cleanupFragments(e, value);
            }
            throw e;
        }

        // Copy existing key, and then copy value.
        p_copy(page, start, page, entryLoc, keyLen);
        copyToLeafValue(page, vfrag, value, entryLoc + keyLen);
        p_shortPutLE(page, pos, entryLoc);

        garbage(garbage);
    }

    /**
     * Update an internal node key to be larger than what is currently allocated. Caller must
     * ensure that node has enough space available and that it's not split. New key must not
     * force this node to split. Key MUST be a normal, non-fragmented key.
     *
     * @param pos must be positive
     * @param growth key size growth
     * @param key normal unencoded key
     */
    void updateInternalKey(int pos, int growth, byte[] key, int encodedLen) {
        int entryLoc = doUpdateInternalKey(pos, growth, encodedLen);
        encodeNormalKey(key, mPage, entryLoc);
    }

    /**
     * Update an internal node key to be larger than what is currently allocated. Caller must
     * ensure that node has enough space available and that it's not split. New key must not
     * force this node to split.
     *
     * @param pos must be positive
     * @param growth key size growth
     * @param key page with encoded key
     * @param keyStart encoded key start; includes header
     */
    void updateInternalKeyEncoded(int pos, int growth,
                                  /*P*/ byte[] key, int keyStart, int encodedLen)
    {
        int entryLoc = doUpdateInternalKey(pos, growth, encodedLen);
        p_copy(key, keyStart, mPage, entryLoc, encodedLen);
    }

    /**
     * @return entryLoc
     */
    int doUpdateInternalKey(int pos, final int growth, final int encodedLen) {
        int garbage = garbage() + encodedLen - growth;

        // What follows is similar to createInternalEntry method, except the search
        // vector doesn't grow.

        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        int leftSpace = searchVecStart - leftSegTail();
        int rightSpace = rightSegTail() - searchVecEnd
            - ((searchVecEnd - searchVecStart) << 2) - 17;

        int entryLoc;
        alloc: {
            if ((entryLoc = allocPageEntry(encodedLen, leftSpace, rightSpace)) >= 0) {
                pos += searchVecStart;
                break alloc;
            }

            makeRoom: {
                // Compute remaining space surrounding search vector after update completes.
                int remaining = leftSpace + rightSpace - encodedLen;

                if (garbage > remaining) {
                    // Do full compaction and free up the garbage.
                    if ((garbage + remaining) < 0) {
                        // New key doesn't fit.
                        throw new AssertionError();
                    }
                    break makeRoom;
                }

                int vecLen = searchVecEnd - searchVecStart + 2;
                int childIdsLen = (vecLen << 2) + 8;
                int newSearchVecStart;

                if (remaining > 0 || (rightSegTail() & 1) != 0) {
                    // Re-center search vector, biased to the right, ensuring proper alignment.
                    newSearchVecStart =
                        (rightSegTail() - vecLen - childIdsLen + (1 - 0) - (remaining >> 1)) & ~1;

                    // Allocate entry from left segment.
                    entryLoc = leftSegTail();
                    leftSegTail(entryLoc + encodedLen);
                } else if ((leftSegTail() & 1) == 0) {
                    // Move search vector left, ensuring proper alignment.
                    newSearchVecStart = leftSegTail() + ((remaining >> 1) & ~1);

                    // Allocate entry from right segment.
                    entryLoc = rightSegTail() - encodedLen + 1;
                    rightSegTail(entryLoc - 1);
                } else {
                    // Search vector is misaligned, so do full compaction.
                    break makeRoom;
                }

                /*P*/ byte[] page = mPage;
                p_copy(page, searchVecStart, page, newSearchVecStart, vecLen + childIdsLen);

                pos += newSearchVecStart;
                searchVecStart(newSearchVecStart);
                searchVecEnd(newSearchVecStart + vecLen - 2);

                break alloc;
            }

            // This point is reached for making room via node compaction.

            garbage(garbage);

            InResult result = new InResult();
            compactInternal(result, encodedLen, pos, Integer.MIN_VALUE);

            return result.mEntryLoc;
        }

        // Point to entry. Caller must copy the key to the location.
        p_shortPutLE(mPage, pos, entryLoc);

        garbage(garbage);

        return entryLoc;
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    void updateChildRefId(int pos, long id) {
        p_longPutLE(mPage, searchVecEnd() + 2 + (pos << 2), id);
    }

    /**
     * @param pos position as provided by binarySearch; must be positive
     */
    void deleteLeafEntry(int pos) throws IOException {
        final /*P*/ byte[] page = mPage;

        int searchVecStart = searchVecStart();
        final int entryLoc = p_ushortGetLE(page, searchVecStart + pos);

        // Note: Similar to leafEntryLengthAtLoc and retrieveLeafValueAtLoc.

        int loc = entryLoc;

        {
            int keyLen = p_byteGet(page, loc++);
            if (keyLen >= 0) {
                loc += keyLen + 1;
            } else {
                int header = keyLen;
                keyLen = ((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++);
                if ((header & ENTRY_FRAGMENTED) != 0) {
                    getDatabase().deleteFragments(page, loc, keyLen);
                }
                loc += keyLen;
            }
        }

        int header = p_byteGet(page, loc++);
        if (header >= 0) {
            loc += header;
        } else largeValue: {
            int len;
            if ((header & 0x20) == 0) {
                len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
            } else if (header != -1) {
                len = 1 + (((header & 0x0f) << 16)
                           | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
            } else {
                // ghost
                break largeValue;
            }
            if ((header & ENTRY_FRAGMENTED) != 0) {
                getDatabase().deleteFragments(page, loc, len);
            }
            loc += len;
        }

        doDeleteLeafEntry(pos, loc - entryLoc);
    }

    void doDeleteLeafEntry(int pos, int entryLen) {
        // Increment garbage by the size of the encoded entry.
        garbage(garbage() + entryLen);

        /*P*/ byte[] page = mPage;
        int searchVecStart = searchVecStart();
        int searchVecEnd = searchVecEnd();

        if (pos < ((searchVecEnd - searchVecStart + 2) >> 1)) {
            // Shift left side of search vector to the right.
            p_copy(page, searchVecStart, page, searchVecStart += 2, pos);
            searchVecStart(searchVecStart);
        } else {
            // Shift right side of search vector to the left.
            pos += searchVecStart;
            p_copy(page, pos + 2, page, pos, searchVecEnd - pos);
            searchVecEnd(searchVecEnd - 2);
        }
    }

    /**
     * Moves all the entries from the right node into the tail of the given
     * left node, and then deletes the right node node. Caller must ensure that
     * left node has enough room, and that both nodes are latched exclusively.
     * Caller must also hold commit lock. The right node is always released as
     * a side effect, but left node is never released by this method.
     */
    static void moveLeafToLeftAndDelete(Tree tree, Node leftNode, Node rightNode)
        throws IOException
    {
        tree.mDatabase.prepareToDelete(rightNode);

        final /*P*/ byte[] rightPage = rightNode.mPage;
        final int searchVecEnd = rightNode.searchVecEnd();
        final int leftEndPos = leftNode.highestLeafPos() + 2;

        int searchVecStart = rightNode.searchVecStart();
        while (searchVecStart <= searchVecEnd) {
            int entryLoc = p_ushortGetLE(rightPage, searchVecStart);
            int encodedLen = leafEntryLengthAtLoc(rightPage, entryLoc);
            int leftEntryLoc = leftNode.createLeafEntry
                (tree, leftNode.highestLeafPos() + 2, encodedLen);
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(rightPage, entryLoc, leftNode.mPage, leftEntryLoc, encodedLen);
            searchVecStart += 2;
        }

        // All cursors in the right node must be moved to the left node.
        for (TreeCursorFrame frame = rightNode.mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            TreeCursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            frame.unbind();
            frame.bind(leftNode, framePos + (framePos < 0 ? (-leftEndPos) : leftEndPos));
            frame = prev;
        }

        // If right node was high extremity, left node now is.
        leftNode.type((byte) (leftNode.type() | (rightNode.type() & HIGH_EXTREMITY)));

        tree.mDatabase.deleteNode(rightNode);
    }

    /**
     * Moves all the entries from the right node into the tail of the given
     * left node, and then deletes the right node node. Caller must ensure that
     * left node has enough room, and that both nodes are latched exclusively.
     * Caller must also hold commit lock. The right node is always released as
     * a side effect, but left node is never released by this method.
     *
     * @param parentPage source of entry to merge from parent
     * @param parentLoc location of parent entry
     * @param parentLen length of parent entry
     */
    static void moveInternalToLeftAndDelete(Tree tree, Node leftNode, Node rightNode,
                                            /*P*/ byte[] parentPage, int parentLoc, int parentLen)
        throws IOException
    {
        tree.mDatabase.prepareToDelete(rightNode);

        // Create space to absorb parent key.
        int leftEndPos = leftNode.highestInternalPos();
        InResult result = new InResult();
        leftNode.createInternalEntry
            (result, tree, leftEndPos, parentLen, (leftEndPos += 2) << 2, false);

        // Copy child id associated with parent key.
        final /*P*/ byte[] rightPage = rightNode.mPage;
        int rightChildIdsLoc = rightNode.searchVecEnd() + 2;
        p_copy(rightPage, rightChildIdsLoc, result.mPage, result.mNewChildLoc, 8);
        rightChildIdsLoc += 8;

        // Write parent key.
        p_copy(parentPage, parentLoc, result.mPage, result.mEntryLoc, parentLen);

        final int searchVecEnd = rightNode.searchVecEnd();

        int searchVecStart = rightNode.searchVecStart();
        while (searchVecStart <= searchVecEnd) {
            int entryLoc = p_ushortGetLE(rightPage, searchVecStart);
            int encodedLen = keyLengthAtLoc(rightPage, entryLoc);

            // Allocate entry for left node.
            int pos = leftNode.highestInternalPos();
            leftNode.createInternalEntry(result, tree, pos, encodedLen, (pos + 2) << 2, false);

            // Copy child id.
            p_copy(rightPage, rightChildIdsLoc, result.mPage, result.mNewChildLoc, 8);
            rightChildIdsLoc += 8;

            // Copy key.
            // Note: Must access left page each time, since compaction can replace it.
            p_copy(rightPage, entryLoc, result.mPage, result.mEntryLoc, encodedLen);
            searchVecStart += 2;
        }

        // All cursors in the right node must be moved to the left node.
        for (TreeCursorFrame frame = rightNode.mLastCursorFrame; frame != null; ) {
            // Capture previous frame from linked list before changing the links.
            TreeCursorFrame prev = frame.mPrevCousin;
            int framePos = frame.mNodePos;
            frame.unbind();
            frame.bind(leftNode, leftEndPos + framePos);
            frame = prev;
        }

        // If right node was high extremity, left node now is.
        leftNode.type((byte) (leftNode.type() | (rightNode.type() & HIGH_EXTREMITY)));

        tree.mDatabase.deleteNode(rightNode);
    }

    /**
     * Delete a parent reference to a right child which merged left.
     *
     * @param childPos non-zero two-based position of the right child
     */
    void deleteRightChildRef(int childPos) {
        // Fix affected cursors.
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            int framePos = frame.mNodePos;
            if (framePos >= childPos) {
                frame.mNodePos = framePos - 2;
            }
            frame = frame.mPrevCousin;
        }

        deleteChildRef(childPos);
    }

    /**
     * Delete a parent reference to a left child which merged right.
     *
     * @param childPos two-based position of the left child
     */
    void deleteLeftChildRef(int childPos) {
        // Fix affected cursors.
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            int framePos = frame.mNodePos;
            if (framePos > childPos) {
                frame.mNodePos = framePos - 2;
            }
            frame = frame.mPrevCousin;
        }

        deleteChildRef(childPos);
    }

    /**
     * Delete a parent reference to child, but doesn't fix any affected cursors.
     *
     * @param childPos two-based position
     */
    private void deleteChildRef(int childPos) {
        final /*P*/ byte[] page = mPage;
        int keyPos = childPos == 0 ? 0 : (childPos - 2);
        int searchVecStart = searchVecStart();

        int entryLoc = p_ushortGetLE(page, searchVecStart + keyPos);
        // Increment garbage by the size of the encoded entry.
        garbage(garbage() + keyLengthAtLoc(page, entryLoc));

        // Rescale for long ids as encoded in page.
        childPos <<= 2;

        int searchVecEnd = searchVecEnd();

        // Remove search vector entry (2 bytes) and remove child id entry
        // (8 bytes). Determine which shift operations minimize movement.
        if (childPos < (3 * (searchVecEnd - searchVecStart) + keyPos + 8) >> 1) {
            // Shift child ids right by 8, shift search vector right by 10.
            p_copy(page, searchVecStart + keyPos + 2,
                   page, searchVecStart + keyPos + (2 + 8),
                   searchVecEnd - searchVecStart - keyPos + childPos);
            p_copy(page, searchVecStart, page, searchVecStart += 10, keyPos);
            searchVecEnd(searchVecEnd + 8);
        } else {
            // Shift child ids left by 8, shift search vector right by 2.
            p_copy(page, searchVecEnd + childPos + (2 + 8),
                   page, searchVecEnd + childPos + 2,
                   ((searchVecEnd - searchVecStart) << 2) + 8 - childPos);
            p_copy(page, searchVecStart, page, searchVecStart += 2, keyPos);
        }

        searchVecStart(searchVecStart);
    }

    /**
     * Delete this non-leaf root node, after all keys have been deleted. The
     * state of the lone child is swapped with this root node, and the child
     * node is repurposed into a stub root node. The old page used by the child
     * node is deleted. This design allows active cursors to still function
     * normally until they can unbind.
     *
     * <p>Caller must hold exclusive latches for root node and lone child.
     * Caller must also ensure that both nodes are not splitting. No latches
     * are released by this method.
     */
    void rootDelete(Tree tree, Node child) throws IOException {
        tree.mDatabase.prepareToDelete(child);

        /*P*/ byte[] page = mPage;
        TreeCursorFrame lastCursorFrame = mLastCursorFrame;

        long toDelete = child.mId;
        int toDeleteState = child.mCachedState;

        byte stubType = type();
        mPage = child.mPage;
        /*P*/ // {
        type(child.type());
        garbage(child.garbage());
        leftSegTail(child.leftSegTail());
        rightSegTail(child.rightSegTail());
        searchVecStart(child.searchVecStart());
        searchVecEnd(child.searchVecEnd());
        /*P*/ // }
        mLastCursorFrame = child.mLastCursorFrame;

        // Repurpose the child node into a stub root node. Stub is assigned a
        // reserved id (1) and a clean cached state. It cannot be marked dirty,
        // but it can be evicted when all cursors have unbound from it.
        tree.mDatabase.nodeMapRemove(child, Utils.hash(toDelete));
        child.mPage = page;
        child.mId = STUB_ID;
        child.mCachedState = CACHED_CLEAN;
        child.type(stubType);
        child.clearEntries();
        child.mLastCursorFrame = lastCursorFrame;
        // Search vector also needs to point to root.
        p_longPutLE(page, child.searchVecEnd() + 2, this.mId);

        // Fix cursor bindings for this, the real root node.
        for (TreeCursorFrame frame = mLastCursorFrame; frame != null; ) {
            frame.mNode = this;
            frame = frame.mPrevCousin;
        }
        // Fix cursor bindings for the stub root node.
        for (TreeCursorFrame frame = lastCursorFrame; frame != null; ) {
            frame.mNode = child;
            frame = frame.mPrevCousin;
        }

        tree.addStub(child, toDelete);

        // The page can be deleted earlier in the method, but doing it here
        // might prevent corruption if an unexpected exception occurs.
        tree.mDatabase.deletePage(toDelete, toDeleteState);
    }

    private static final int SMALL_KEY_LIMIT = 128;

    /**
     * Calculate encoded key length, including header. Returns -1 if key is too large and must
     * be fragmented.
     */
    private static int calculateAllowedKeyLength(Tree tree, byte[] key) {
        int len = key.length - 1;
        if ((len & ~(SMALL_KEY_LIMIT - 1)) == 0) {
            // Always safe because minimum node size is 512 bytes.
            return len + 2;
        } else {
            len++;
            return len > tree.mMaxKeySize ? -1 : len + 2;
        }
    }

    /**
     * Calculate encoded key length, including header. Key must fit in the node or have been
     * fragmented.
     */
    static int calculateKeyLength(byte[] key) {
        int len = key.length - 1;
        return len + ((len & ~(SMALL_KEY_LIMIT - 1)) == 0 ? 2 : 3);
    }

    /**
     * Calculate encoded value length for leaf, including header. Value must fit in the node or
     * have been fragmented.
     */
    private static int calculateLeafValueLength(byte[] value) {
        int len = value.length;
        return len + ((len <= 127) ? 1 : ((len <= 8192) ? 2 : 3));
    }

    /**
     * Calculate encoded value length for leaf, including header. Value must fit in the node or
     * have been fragmented.
     */
    private static long calculateLeafValueLength(long vlength) {
        return vlength + ((vlength <= 127) ? 1 : ((vlength <= 8192) ? 2 : 3));
    }

    /**
     * Calculate encoded value length for leaf, including header.
     */
    private static int calculateFragmentedValueLength(byte[] value) {
        return calculateFragmentedValueLength(value.length);
    }

    /**
     * Calculate encoded value length for leaf, including header.
     */
    static int calculateFragmentedValueLength(int vlength) {
        return vlength + ((vlength <= 8192) ? 2 : 3);
    }

    /**
     * @param key unencoded key
     * @param page destination for encoded key, with room for key header
     * @return updated pageLoc
     */
    static int encodeNormalKey(final byte[] key, final /*P*/ byte[] page, int pageLoc) {
        final int keyLen = key.length;

        if (keyLen <= SMALL_KEY_LIMIT && keyLen > 0) {
            p_bytePut(page, pageLoc++, keyLen - 1);
        } else {
            p_bytePut(page, pageLoc++, 0x80 | (keyLen >> 8));
            p_bytePut(page, pageLoc++, keyLen);
        }
        p_copyFromArray(key, 0, page, pageLoc, keyLen);

        return pageLoc + keyLen;
    }

    /**
     * @param key fragmented key
     * @param page destination for encoded key, with room for key header
     * @return updated pageLoc
     */
    static int encodeFragmentedKey(final byte[] key, final /*P*/ byte[] page, int pageLoc) {
        final int keyLen = key.length;
        p_bytePut(page, pageLoc++, (0x80 | ENTRY_FRAGMENTED) | (keyLen >> 8));
        p_bytePut(page, pageLoc++, keyLen);
        p_copyFromArray(key, 0, page, pageLoc, keyLen);
        return pageLoc + keyLen;
    }

    /**
     * @return -1 if not enough contiguous space surrounding search vector
     */
    private int allocPageEntry(int encodedLen, int leftSpace, int rightSpace) {
        final int entryLoc;
        if (encodedLen <= leftSpace && leftSpace >= rightSpace) {
            // Allocate entry from left segment.
            entryLoc = leftSegTail();
            leftSegTail(entryLoc + encodedLen);
        } else if (encodedLen <= rightSpace) {
            // Allocate entry from right segment.
            entryLoc = rightSegTail() - encodedLen + 1;
            rightSegTail(entryLoc - 1);
        } else {
            // No room.
            return -1;
        }
        return entryLoc;
    }

    /**
     * @param okey original key
     * @param akey key to actually store
     * @param vfrag 0 or ENTRY_FRAGMENTED
     */
    private void copyToLeafEntry(byte[] okey, byte[] akey, int vfrag, byte[] value, int entryLoc) {
        final /*P*/ byte[] page = mPage;
        int vloc = okey == akey ? encodeNormalKey(akey, page, entryLoc)
            : encodeFragmentedKey(akey, page, entryLoc);
        copyToLeafValue(page, vfrag, value, vloc);
    }

    /**
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @return page location for first byte of value (first location after header)
     */
    private static int copyToLeafValue(/*P*/ byte[] page, int vfrag, byte[] value, int vloc) {
        final int vlen = value.length;
        vloc = encodeLeafValueHeader(page, vfrag, vlen, vloc);
        p_copyFromArray(value, 0, page, vloc, vlen);
        return vloc;
    }

    /**
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @return page location for first byte of value (first location after header)
     */
    static int encodeLeafValueHeader(/*P*/ byte[] page, int vfrag, int vlen, int vloc) {
        if (vlen <= 127 && vfrag == 0) {
            p_bytePut(page, vloc++, vlen);
        } else {
            vlen--;
            if (vlen <= 8192) {
                p_bytePut(page, vloc++, 0x80 | vfrag | (vlen >> 8));
                p_bytePut(page, vloc++, vlen);
            } else {
                p_bytePut(page, vloc++, 0xa0 | vfrag | (vlen >> 16));
                p_bytePut(page, vloc++, vlen >> 8);
                p_bytePut(page, vloc++, vlen);
            }
        }
        return vloc;
    }

    /**
     * Compact leaf by reclaiming garbage and moving search vector towards
     * tail. Caller is responsible for ensuring that new entry will fit after
     * compaction. Space is allocated for new entry, and the search vector
     * points to it.
     *
     * @param encodedLen length of new entry to allocate
     * @param pos normalized search vector position of entry to insert/update
     * @return location for newly allocated entry, already pointed to by search vector
     */
    private int compactLeaf(int encodedLen, int pos, boolean forInsert) {
        /*P*/ byte[] page = mPage;

        int searchVecLoc = searchVecStart();
        // Size of search vector, possibly with new entry.
        int newSearchVecSize = searchVecEnd() - searchVecLoc + 2;
        if (forInsert) {
            newSearchVecSize += 2;
        }
        pos += searchVecLoc;

        // Determine new location of search vector, with room to grow on both ends.
        int newSearchVecStart;
        // Capacity available to search vector after compaction.
        int searchVecCap = garbage() + rightSegTail() + 1 - leftSegTail() - encodedLen;
        newSearchVecStart = p_length(page) - (((searchVecCap + newSearchVecSize) >> 1) & ~1);

        // Copy into a fresh buffer.

        int destLoc = TN_HEADER_SIZE;
        int newSearchVecLoc = newSearchVecStart;
        int newLoc = 0;
        final int searchVecEnd = searchVecEnd();

        Database db = getDatabase();
        /*P*/ byte[] dest = db.removeSparePage();

        /*P*/ // {
        /*P*/ // |
        /*P*/ // p_intPutLE(dest, 0, type() & 0xff); // set type, reserved byte, and garbage
        /*P*/ // }

        for (; searchVecLoc <= searchVecEnd; searchVecLoc += 2, newSearchVecLoc += 2) {
            if (searchVecLoc == pos) {
                newLoc = newSearchVecLoc;
                if (forInsert) {
                    newSearchVecLoc += 2;
                } else {
                    continue;
                }
            }
            p_shortPutLE(dest, newSearchVecLoc, destLoc);
            int sourceLoc = p_ushortGetLE(page, searchVecLoc);
            int len = leafEntryLengthAtLoc(page, sourceLoc);
            p_copy(page, sourceLoc, dest, destLoc, len);
            destLoc += len;
        }

        // Recycle old page buffer.
        db.addSparePage(page);

        // Write pointer to new allocation.
        p_shortPutLE(dest, newLoc == 0 ? newSearchVecLoc : newLoc, destLoc);

        mPage = dest;
        /*P*/ // {
        garbage(0);
        /*P*/ // }
        leftSegTail(destLoc + encodedLen);
        rightSegTail(p_length(dest) - 1);
        searchVecStart(newSearchVecStart);
        searchVecEnd(newSearchVecStart + newSearchVecSize - 2);

        return destLoc;
    }

    private void cleanupSplit(Throwable cause, Node newNode, Split split) {
        if (split != null) {
            cleanupFragments(cause, split.fragmentedKey());
        }

        try {
            getDatabase().deleteNode(newNode, true);
        } catch (Throwable e) {
            cause.addSuppressed(e);
            panic(cause);
        }
    }

    /**
     * @param okey original key
     * @param akey key to actually store
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @param encodedLen length of new entry to allocate
     * @param pos normalized search vector position of entry to insert/update
     */
    private void splitLeafAndCreateEntry(Tree tree, byte[] okey, byte[] akey,
                                         int vfrag, byte[] value,
                                         int encodedLen, int pos, boolean forInsert)
        throws IOException
    {
        if (mSplit != null) {
            throw new AssertionError("Node is already split");
        }

        // Split can move node entries to a new left or right node. Choose such that the
        // new entry is more likely to go into the new node. This distributes the cost of
        // the split by postponing compaction of this node.

        // Since the split key and final node sizes are not known in advance, don't
        // attempt to properly center the new search vector. Instead, minimize
        // fragmentation to ensure that split is successful.

        /*P*/ byte[] page = mPage;

        if (page == p_emptyTreePage()) {
            // Node is a closed tree root.
            throw new ClosedIndexException();
        }

        Node newNode = tree.mDatabase.allocDirtyNode(NodeUsageList.MODE_UNEVICTABLE);
        tree.mDatabase.nodeMapPut(newNode);

        /*P*/ byte[] newPage = newNode.mPage;

        /*P*/ // {
        newNode.garbage(0);
        /*P*/ // |
        /*P*/ // p_intPutLE(newPage, 0, 0); // set type (fixed later), reserved byte, and garbage
        /*P*/ // }

        if (forInsert && pos == 0) {
            // Inserting into left edge of node, possibly because inserts are
            // descending. Split into new left node, but only the new entry
            // goes into the new node.

            Split split = null;
            try {
                split = newSplitLeft(newNode);
                // Choose an appropriate middle key for suffix compression.
                setSplitKey(tree, split, midKey(okey, 0));
            } catch (Throwable e) {
                cleanupSplit(e, newNode, split);
                throw e;
            }

            mSplit = split;

            // Position search vector at extreme left, allowing new entries to
            // be placed in a natural descending order.
            newNode.leftSegTail(TN_HEADER_SIZE);
            newNode.searchVecStart(TN_HEADER_SIZE);
            newNode.searchVecEnd(TN_HEADER_SIZE);

            int destLoc = p_length(newPage) - encodedLen;
            newNode.copyToLeafEntry(okey, akey, vfrag, value, destLoc);
            p_shortPutLE(newPage, TN_HEADER_SIZE, destLoc);

            newNode.rightSegTail(destLoc - 1);
            newNode.releaseExclusive();

            return;
        }

        final int searchVecStart = searchVecStart();
        final int searchVecEnd = searchVecEnd();

        pos += searchVecStart;

        if (forInsert && pos == searchVecEnd + 2) {
            // Inserting into right edge of node, possibly because inserts are
            // ascending. Split into new right node, but only the new entry
            // goes into the new node.

            Split split = null;
            try {
                split = newSplitRight(newNode);
                // Choose an appropriate middle key for suffix compression.
                setSplitKey(tree, split, midKey(pos - searchVecStart - 2, okey));
            } catch (Throwable e) {
                cleanupSplit(e, newNode, split);
                throw e;
            }

            mSplit = split;

            // Position search vector at extreme right, allowing new entries to
            // be placed in a natural ascending order.
            newNode.rightSegTail(p_length(newPage) - 1);
            int newSearchVecStart = p_length(newPage) - 2;
            newNode.searchVecStart(newSearchVecStart);
            newNode.searchVecEnd(newSearchVecStart);

            newNode.copyToLeafEntry(okey, akey, vfrag, value, TN_HEADER_SIZE);
            p_shortPutLE(newPage, p_length(newPage) - 2, TN_HEADER_SIZE);

            newNode.leftSegTail(TN_HEADER_SIZE + encodedLen);
            newNode.releaseExclusive();

            return;
        }

        // Amount of bytes available in unsplit node.
        int avail = availableLeafBytes();

        int garbageAccum = 0;
        int newLoc = 0;
        int newAvail = p_length(newPage) - TN_HEADER_SIZE;

        // Guess which way to split by examining search position. This doesn't take into
        // consideration the variable size of the entries. If the guess is wrong, the new
        // entry is inserted into original node, which now has space.

        if ((pos - searchVecStart) < (searchVecEnd - pos)) {
            // Split into new left node.

            int destLoc = p_length(newPage);
            int newSearchVecLoc = TN_HEADER_SIZE;

            int searchVecLoc = searchVecStart;
            for (; newAvail > avail; searchVecLoc += 2, newSearchVecLoc += 2) {
                int entryLoc = p_ushortGetLE(page, searchVecLoc);
                int entryLen = leafEntryLengthAtLoc(page, entryLoc);

                if (searchVecLoc == pos) {
                    if ((newAvail -= encodedLen + 2) < 0) {
                        // Entry doesn't fit into new node.
                        break;
                    }
                    newLoc = newSearchVecLoc;
                    if (forInsert) {
                        // Reserve slot in vector for new entry.
                        newSearchVecLoc += 2;
                        if (newAvail <= avail) {
                            // Balanced enough.
                            break;
                        }
                    } else {
                        // Don't copy old entry.
                        garbageAccum += entryLen;
                        avail += entryLen;
                        continue;
                    }
                }

                if ((newAvail -= entryLen + 2) < 0) {
                    // Entry doesn't fit into new node.
                    break;
                }

                // Copy entry and point to it.
                destLoc -= entryLen;
                p_copy(page, entryLoc, newPage, destLoc, entryLen);
                p_shortPutLE(newPage, newSearchVecLoc, destLoc);

                garbageAccum += entryLen;
                avail += entryLen + 2;
            }

            newNode.leftSegTail(TN_HEADER_SIZE);
            newNode.searchVecStart(TN_HEADER_SIZE);
            newNode.searchVecEnd(newSearchVecLoc - 2);

            // Prune off the left end of this node.
            final int originalStart = searchVecStart();
            final int originalGarbage = garbage();
            searchVecStart(searchVecLoc);
            garbage(originalGarbage + garbageAccum);

            Split split = null;
            byte[] fv = null;
            try {
                split = newSplitLeft(newNode);

                if (newLoc == 0) {
                    // Unable to insert new entry into left node. Insert it
                    // into the right node, which should have space now.
                    fv = storeIntoSplitLeaf(tree, okey, akey, vfrag, value, encodedLen, forInsert);
                } else {
                    // Create new entry and point to it.
                    destLoc -= encodedLen;
                    newNode.copyToLeafEntry(okey, akey, vfrag, value, destLoc);
                    p_shortPutLE(newPage, newLoc, destLoc);
                }

                // Choose an appropriate middle key for suffix compression.
                setSplitKey(tree, split, newNode.midKey(newNode.highestKeyPos(), this, 0));

                newNode.rightSegTail(destLoc - 1);
                newNode.releaseExclusive();
            } catch (Throwable e) {
                searchVecStart(originalStart);
                garbage(originalGarbage);
                cleanupFragments(e, fv);
                cleanupSplit(e, newNode, split);
                throw e;
            }

            mSplit = split;
        } else {
            // Split into new right node.

            int destLoc = TN_HEADER_SIZE;
            int newSearchVecLoc = p_length(newPage) - 2;

            int searchVecLoc = searchVecEnd;
            for (; newAvail > avail; searchVecLoc -= 2, newSearchVecLoc -= 2) {
                int entryLoc = p_ushortGetLE(page, searchVecLoc);
                int entryLen = leafEntryLengthAtLoc(page, entryLoc);

                if (forInsert) {
                    if (searchVecLoc + 2 == pos) {
                        if ((newAvail -= encodedLen + 2) < 0) {
                            // Inserted entry doesn't fit into new node.
                            break;
                        }
                        // Reserve spot in vector for new entry.
                        newLoc = newSearchVecLoc;
                        newSearchVecLoc -= 2;
                        if (newAvail <= avail) {
                            // Balanced enough.
                            break;
                        }
                    }
                } else {
                    if (searchVecLoc == pos) {
                        if ((newAvail -= encodedLen + 2) < 0) {
                            // Updated entry doesn't fit into new node.
                            break;
                        }
                        // Don't copy old entry.
                        newLoc = newSearchVecLoc;
                        garbageAccum += entryLen;
                        avail += entryLen;
                        continue;
                    }
                }

                if ((newAvail -= entryLen + 2) < 0) {
                    // Entry doesn't fit into new node.
                    break;
                }

                // Copy entry and point to it.
                p_copy(page, entryLoc, newPage, destLoc, entryLen);
                p_shortPutLE(newPage, newSearchVecLoc, destLoc);
                destLoc += entryLen;

                garbageAccum += entryLen;
                avail += entryLen + 2;
            }

            newNode.rightSegTail(p_length(newPage) - 1);
            newNode.searchVecStart(newSearchVecLoc + 2);
            newNode.searchVecEnd(p_length(newPage) - 2);

            // Prune off the right end of this node.
            final int originalEnd = searchVecEnd();
            final int originalGarbage = garbage();
            searchVecEnd(searchVecLoc);
            garbage(originalGarbage + garbageAccum);

            Split split = null;
            byte[] fv = null;
            try {
                split = newSplitRight(newNode);

                if (newLoc == 0) {
                    // Unable to insert new entry into new right node. Insert
                    // it into the left node, which should have space now.
                    fv = storeIntoSplitLeaf(tree, okey, akey, vfrag, value, encodedLen, forInsert);
                } else {
                    // Create new entry and point to it.
                    newNode.copyToLeafEntry(okey, akey, vfrag, value, destLoc);
                    p_shortPutLE(newPage, newLoc, destLoc);
                    destLoc += encodedLen;
                }

                // Choose an appropriate middle key for suffix compression.
                setSplitKey(tree, split, this.midKey(this.highestKeyPos(), newNode, 0));

                newNode.leftSegTail(destLoc);
                newNode.releaseExclusive();
            } catch (Throwable e) {
                searchVecEnd(originalEnd);
                garbage(originalGarbage);
                cleanupFragments(e, fv);
                cleanupSplit(e, newNode, split);
                throw e;
            }

            mSplit = split;
        }
    }

    /**
     * Store an entry into a node which has just been split and has room.
     *
     * @param okey original key
     * @param akey key to actually store
     * @param vfrag 0 or ENTRY_FRAGMENTED
     * @return non-null if value got fragmented
     */
    private byte[] storeIntoSplitLeaf(Tree tree, byte[] okey, byte[] akey,
                                      int vfrag, byte[] value,
                                      int encodedLen, boolean forInsert)
        throws IOException
    {
        int pos = binarySearch(okey);
        if (forInsert) {
            if (pos >= 0) {
                throw new AssertionError("Key exists");
            }
            int entryLoc = createLeafEntry(tree, ~pos, encodedLen);
            byte[] result = null;
            while (entryLoc < 0) {
                if (vfrag != 0) {
                    // FIXME: Can this happen?
                    throw new DatabaseException("Fragmented entry doesn't fit");
                }
                Database db = tree.mDatabase;
                int max = Math.min(~entryLoc, db.mMaxFragmentedEntrySize);
                int encodedKeyLen = calculateKeyLength(akey);
                value = db.fragment(value, value.length, max - encodedKeyLen);
                if (value == null) {
                    throw new AssertionError();
                }
                result = value;
                vfrag = ENTRY_FRAGMENTED;
                encodedLen = encodedKeyLen + calculateFragmentedValueLength(value);
                entryLoc = createLeafEntry(tree, ~pos, encodedLen);
            }
            copyToLeafEntry(okey, akey, vfrag, value, entryLoc);
            return result;
        } else {
            if (pos < 0) {
                throw new AssertionError("Key not found");
            }
            updateLeafValue(tree, pos, vfrag, value);
            return null;
        }
    }

    /**
     * @param result split result stored here; key and entry loc is -1 if new key was promoted
     * to parent
     * @throws IOException if new node could not be allocated; no side-effects
     */
    private void splitInternal(final InResult result,
                               final Tree tree, final int encodedLen,
                               final int keyPos, final int newChildPos)
        throws IOException
    {
        if (mSplit != null) {
            throw new AssertionError("Node is already split");
        }

        // Split can move node entries to a new left or right node. Choose such that the
        // new entry is more likely to go into the new node. This distributes the cost of
        // the split by postponing compaction of this node.

        // Alloc early in case an exception is thrown.

        final Database db = getDatabase();

        Node newNode;
        try {
            newNode = db.allocDirtyNode(NodeUsageList.MODE_UNEVICTABLE);
        } catch (DatabaseFullException e) {
            // Internal node splits are critical. If a child node reference cannot be inserted,
            // then it would be orphaned. Try allocating again without any capacity limit, or
            // else the caller must panic the database.
            db.capacityLimitOverride(-1);
            try {
                newNode = db.allocDirtyNode(NodeUsageList.MODE_UNEVICTABLE);
            } finally {
                db.capacityLimitOverride(0);
            }
        }

        db.nodeMapPut(newNode);

        final /*P*/ byte[] newPage = newNode.mPage;

        /*P*/ // {
        newNode.garbage(0);
        /*P*/ // |
        /*P*/ // p_intPutLE(newPage, 0, 0); // set type (fixed later), reserved byte, and garbage
        /*P*/ // }

        final /*P*/ byte[] page = mPage;

        final int searchVecStart = searchVecStart();
        final int searchVecEnd = searchVecEnd();

        if ((searchVecEnd - searchVecStart) == 2 && keyPos == 2) {
            // Node has two keys and the key to insert should go in the middle. The new key
            // should not be inserted, but instead be promoted to the parent. Treat this as a
            // special case -- the code below only promotes an existing key to the parent.
            // This case is expected to only occur when using large keys.

            // Allocate Split object first, in case it throws an OutOfMemoryError.
            Split split;
            try {
                split = newSplitLeft(newNode);
            } catch (Throwable e) {
                cleanupSplit(e, newNode, null);
                throw e;
            }

            // Signals that key should not be inserted.
            result.mEntryLoc = -1;

            int leftKeyLoc = p_ushortGetLE(page, searchVecStart);
            int leftKeyLen = keyLengthAtLoc(page, leftKeyLoc);

            // Assume a large key will be inserted later, so arrange it with room: entry at far
            // left and search vector at far right.
            p_copy(page, leftKeyLoc, newPage, TN_HEADER_SIZE, leftKeyLen);
            int leftSearchVecStart = p_length(newPage) - (2 + 8 + 8);
            p_shortPutLE(newPage, leftSearchVecStart, TN_HEADER_SIZE);

            if (newChildPos == 8) {
                // Caller must store child id into left node.
                result.mPage = newPage;
                result.mNewChildLoc = leftSearchVecStart + (2 + 8);
            } else {
                if (newChildPos != 16) {
                    throw new AssertionError();
                }
                // Caller must store child id into right node.
                result.mPage = page;
                result.mNewChildLoc = searchVecEnd + (2 + 8);
            }

            // Copy one or two left existing child ids to left node (newChildPos is 8 or 16).
            p_copy(page, searchVecEnd + 2, newPage, leftSearchVecStart + 2, newChildPos);

            newNode.leftSegTail(TN_HEADER_SIZE + leftKeyLen);
            newNode.rightSegTail(leftSearchVecStart + (2 + 8 + 8 - 1));
            newNode.searchVecStart(leftSearchVecStart);
            newNode.searchVecEnd(leftSearchVecStart);
            newNode.releaseExclusive();

            // Prune off the left end of this node by shifting vector towards child ids.
            p_copy(page, searchVecEnd, page, searchVecEnd + 8, 2);
            int newSearchVecStart = searchVecEnd + 8;
            searchVecStart(newSearchVecStart);
            searchVecEnd(newSearchVecStart);

            garbage(garbage() + leftKeyLen);

            // Caller must set the split key.
            mSplit = split;

            return;
        }

        result.mPage = newPage;
        final int keyLoc = keyPos + searchVecStart;

        int garbageAccum;
        int newKeyLoc;

        // Guess which way to split by examining search position. This doesn't take into
        // consideration the variable size of the entries. If the guess is wrong, do over
        // the other way. Internal splits are infrequent, and split guesses are usually
        // correct. For these reasons, it isn't worth the trouble to create a special case
        // to charge ahead with the wrong guess. Leaf node splits are more frequent, and
        // incorrect guesses are easily corrected due to the simpler leaf node structure.

        // -2: left
        // -1: guess left
        // +1: guess right
        // +2: right
        int splitSide = (keyPos < (searchVecEnd - searchVecStart - keyPos)) ? -1 : 1;

        Split split = null;
        doSplit: while (true) {
            garbageAccum = 0;
            newKeyLoc = 0;

            // Amount of bytes used in unsplit node, including the page header.
            int size = 5 * (searchVecEnd - searchVecStart) + (1 + 8 + 8)
                + leftSegTail() + p_length(page) - rightSegTail() - garbage();

            int newSize = TN_HEADER_SIZE;

            // Adjust sizes for extra child id -- always one more than number of keys.
            size -= 8;
            newSize += 8;

            if (splitSide < 0) {
                // Split into new left node.

                // Since the split key and final node sizes are not known in advance,
                // don't attempt to properly center the new search vector. Instead,
                // minimize fragmentation to ensure that split is successful.

                int destLoc = p_length(newPage);
                int newSearchVecLoc = TN_HEADER_SIZE;

                int searchVecLoc = searchVecStart;
                while (true) {
                    if (searchVecLoc == keyLoc) {
                        newKeyLoc = newSearchVecLoc;
                        newSearchVecLoc += 2;
                        // Reserve slot in vector for new entry and account for size increase.
                        newSize += encodedLen + (2 + 8);
                        if (newSize > p_length(newPage)) {
                            // New entry doesn't fit.
                            if (splitSide == -1) {
                                // Guessed wrong; do over on left side.
                                splitSide = 2;
                                continue doSplit;
                            }
                            // Impossible split. No room for new entry anywhere.
                            throw new AssertionError();
                        }
                    }

                    int entryLoc = p_ushortGetLE(page, searchVecLoc);
                    int entryLen = keyLengthAtLoc(page, entryLoc);

                    // Size change must incorporate child id, although they are copied later.
                    int sizeChange = entryLen + (2 + 8);
                    size -= sizeChange;
                    newSize += sizeChange;

                    searchVecLoc += 2;

                    // Note that last examined key is not moved but is dropped. Garbage must
                    // account for this.
                    garbageAccum += entryLen;

                    boolean full = size < TN_HEADER_SIZE | newSize > p_length(newPage);

                    if (full || newSize >= size) {
                        // New node has accumlated enough entries...

                        if (newKeyLoc != 0) {
                            // ...and split key has been found.
                            try {
                                split = newSplitLeft(newNode);
                                setSplitKey(tree, split, retrieveKeyAtLoc(page, entryLoc));
                            } catch (Throwable e) {
                                cleanupSplit(e, newNode, split);
                                throw e;
                            }
                            break;
                        }

                        if (splitSide == -1) {
                            // Guessed wrong; do over on right side.
                            splitSide = 2;
                            continue doSplit;
                        }

                        // Keep searching on this side for new entry location.
                        if (full || splitSide != -2) {
                            throw new AssertionError();
                        }
                    }

                    // Copy key entry and point to it.
                    destLoc -= entryLen;
                    p_copy(page, entryLoc, newPage, destLoc, entryLen);
                    p_shortPutLE(newPage, newSearchVecLoc, destLoc);
                    newSearchVecLoc += 2;
                }

                result.mEntryLoc = destLoc - encodedLen;

                // Copy existing child ids and insert new child id.
                {
                    p_copy(page, searchVecEnd + 2, newPage, newSearchVecLoc, newChildPos);

                    // Leave gap for new child id, to be set by caller.
                    result.mNewChildLoc = newSearchVecLoc + newChildPos;

                    int tailChildIdsLen = ((searchVecLoc - searchVecStart) << 2) - newChildPos;
                    p_copy(page, searchVecEnd + 2 + newChildPos,
                           newPage, newSearchVecLoc + newChildPos + 8, tailChildIdsLen);
                }

                newNode.leftSegTail(TN_HEADER_SIZE);
                newNode.rightSegTail(destLoc - encodedLen - 1);
                newNode.searchVecStart(TN_HEADER_SIZE);
                newNode.searchVecEnd(newSearchVecLoc - 2);
                newNode.releaseExclusive();

                // Prune off the left end of this node by shifting vector towards child ids.
                int shift = (searchVecLoc - searchVecStart) << 2;
                int len = searchVecEnd - searchVecLoc + 2;
                int newSearchVecStart = searchVecLoc + shift;
                p_copy(page, searchVecLoc, page, newSearchVecStart, len);
                searchVecStart(newSearchVecStart);
                searchVecEnd(searchVecEnd + shift);
            } else {
                // Split into new right node.

                // First copy keys and not the child ids. After keys are copied, shift to
                // make room for child ids and copy them in place.

                int destLoc = TN_HEADER_SIZE;
                int newSearchVecLoc = p_length(newPage);

                int searchVecLoc = searchVecEnd + 2;
                moveEntries: while (true) {
                    if (searchVecLoc == keyLoc) {
                        newSearchVecLoc -= 2;
                        newKeyLoc = newSearchVecLoc;
                        // Reserve slot in vector for new entry and account for size increase.
                        newSize += encodedLen + (2 + 8);
                        if (newSize > p_length(newPage)) {
                            // New entry doesn't fit.
                            if (splitSide == 1) {
                                // Guessed wrong; do over on left side.
                                splitSide = -2;
                                continue doSplit;
                            }
                            // Impossible split. No room for new entry anywhere.
                            throw new AssertionError();
                        }
                    }

                    searchVecLoc -= 2;

                    int entryLoc = p_ushortGetLE(page, searchVecLoc);
                    int entryLen = keyLengthAtLoc(page, entryLoc);

                    // Size change must incorporate child id, although they are copied later.
                    int sizeChange = entryLen + (2 + 8);
                    size -= sizeChange;
                    newSize += sizeChange;

                    // Note that last examined key is not moved but is dropped. Garbage must
                    // account for this.
                    garbageAccum += entryLen;

                    boolean full = size < TN_HEADER_SIZE | newSize > p_length(newPage);

                    if (full || newSize >= size) {
                        // New node has accumlated enough entries...

                        if (newKeyLoc != 0) {
                            // ...and split key has been found.
                            try {
                                split = newSplitRight(newNode);
                                setSplitKey(tree, split, retrieveKeyAtLoc(page, entryLoc));
                            } catch (Throwable e) {
                                cleanupSplit(e, newNode, split);
                                throw e;
                            }
                            break moveEntries;
                        }

                        if (splitSide == 1) {
                            // Guessed wrong; do over on left side.
                            splitSide = -2;
                            continue doSplit;
                        }

                        // Keep searching on this side for new entry location.
                        if (full || splitSide != 2) {
                            throw new AssertionError();
                        }
                    }

                    // Copy key entry and point to it.
                    p_copy(page, entryLoc, newPage, destLoc, entryLen);
                    newSearchVecLoc -= 2;
                    p_shortPutLE(newPage, newSearchVecLoc, destLoc);
                    destLoc += entryLen;
                }

                result.mEntryLoc = destLoc;

                // Move new search vector to make room for child ids and be centered between
                // the segments.
                int newVecLen = p_length(page) - newSearchVecLoc;
                {
                    int highestLoc = p_length(newPage) - (5 * newVecLen) - 8;
                    int midLoc = ((destLoc + encodedLen + highestLoc + 1) >> 1) & ~1;
                    p_copy(newPage, newSearchVecLoc, newPage, midLoc, newVecLen);
                    newKeyLoc -= newSearchVecLoc - midLoc;
                    newSearchVecLoc = midLoc;
                }

                int newSearchVecEnd = newSearchVecLoc + newVecLen - 2;

                // Copy existing child ids and insert new child id.
                {
                    int headChildIdsLen = newChildPos - ((searchVecLoc - searchVecStart + 2) << 2);
                    int newDestLoc = newSearchVecEnd + 2;
                    p_copy(page, searchVecEnd + 2 + newChildPos - headChildIdsLen,
                           newPage, newDestLoc, headChildIdsLen);

                    // Leave gap for new child id, to be set by caller.
                    newDestLoc += headChildIdsLen;
                    result.mNewChildLoc = newDestLoc;

                    int tailChildIdsLen =
                        ((searchVecEnd - searchVecStart) << 2) + 16 - newChildPos;
                    p_copy(page, searchVecEnd + 2 + newChildPos,
                           newPage, newDestLoc + 8, tailChildIdsLen);
                }

                newNode.leftSegTail(destLoc + encodedLen);
                newNode.rightSegTail(p_length(newPage) - 1);
                newNode.searchVecStart(newSearchVecLoc);
                newNode.searchVecEnd(newSearchVecEnd);
                newNode.releaseExclusive();

                // Prune off the right end of this node by shifting vector towards child ids.
                int len = searchVecLoc - searchVecStart;
                int newSearchVecStart = searchVecEnd + 2 - len;
                p_copy(page, searchVecStart, page, newSearchVecStart, len);
                searchVecStart(newSearchVecStart);
            }

            break;
        } // end doSplit

        garbage(garbage() + garbageAccum);
        mSplit = split;

        // Write pointer to key entry.
        p_shortPutLE(newPage, newKeyLoc, result.mEntryLoc);
    }

    private void setSplitKey(Tree tree, Split split, byte[] fullKey) throws IOException {
        byte[] actualKey = fullKey;

        if (calculateAllowedKeyLength(tree, fullKey) < 0) {
            // Key must be fragmented.
            actualKey = tree.fragmentKey(fullKey);
        }

        split.setKey(fullKey, actualKey);
    }

    /**
     * Compact internal node by reclaiming garbage and moving search vector
     * towards tail. Caller is responsible for ensuring that new entry will fit
     * after compaction. Space is allocated for new entry, and the search
     * vector points to it.
     *
     * @param result return result stored here
     * @param encodedLen length of new entry to allocate
     * @param keyPos normalized search vector position of key to insert/update
     * @param childPos normalized search vector position of child node id to insert; pass
     * MIN_VALUE if updating
     */
    private void compactInternal(InResult result, int encodedLen, int keyPos, int childPos) {
        /*P*/ byte[] page = mPage;

        int searchVecLoc = searchVecStart();
        keyPos += searchVecLoc;
        // Size of search vector, possibly with new entry.
        int newSearchVecSize = searchVecEnd() - searchVecLoc + (2 + 2) + (childPos >> 30);

        // Determine new location of search vector, with room to grow on both ends.
        int newSearchVecStart;
        // Capacity available to search vector after compaction.
        int searchVecCap = garbage() + rightSegTail() + 1 - leftSegTail() - encodedLen;
        newSearchVecStart = p_length(page) -
            (((searchVecCap + newSearchVecSize + ((newSearchVecSize + 2) << 2)) >> 1) & ~1);

        // Copy into a fresh buffer.

        int destLoc = TN_HEADER_SIZE;
        int newSearchVecLoc = newSearchVecStart;
        int newLoc = 0;
        final int searchVecEnd = searchVecEnd();

        Database db = getDatabase();
        /*P*/ byte[] dest = db.removeSparePage();

        /*P*/ // {
        /*P*/ // |
        /*P*/ // p_intPutLE(dest, 0, type() & 0xff); // set type, reserved byte, and garbage
        /*P*/ // }

        for (; searchVecLoc <= searchVecEnd; searchVecLoc += 2, newSearchVecLoc += 2) {
            if (searchVecLoc == keyPos) {
                newLoc = newSearchVecLoc;
                if (childPos >= 0) {
                    newSearchVecLoc += 2;
                } else {
                    continue;
                }
            }
            p_shortPutLE(dest, newSearchVecLoc, destLoc);
            int sourceLoc = p_ushortGetLE(page, searchVecLoc);
            int len = keyLengthAtLoc(page, sourceLoc);
            p_copy(page, sourceLoc, dest, destLoc, len);
            destLoc += len;
        }

        if (childPos >= 0) {
            if (newLoc == 0) {
                newLoc = newSearchVecLoc;
                newSearchVecLoc += 2;
            }

            // Copy child ids, and leave room for inserted child id.
            p_copy(page, searchVecEnd() + 2, dest, newSearchVecLoc, childPos);
            p_copy(page, searchVecEnd() + 2 + childPos,
                   dest, newSearchVecLoc + childPos + 8,
                   (newSearchVecSize << 2) - childPos);
        } else {
            if (newLoc == 0) {
                newLoc = newSearchVecLoc;
            }

            // Copy child ids.
            p_copy(page, searchVecEnd() + 2, dest, newSearchVecLoc, (newSearchVecSize << 2) + 8);
        }

        // Recycle old page buffer.
        db.addSparePage(page);

        // Write pointer to key entry.
        p_shortPutLE(dest, newLoc, destLoc);

        mPage = dest;
        /*P*/ // {
        garbage(0);
        /*P*/ // }
        leftSegTail(destLoc + encodedLen);
        rightSegTail(p_length(dest) - 1);
        searchVecStart(newSearchVecStart);
        searchVecEnd(newSearchVecLoc - 2);

        result.mPage = dest;
        result.mNewChildLoc = newSearchVecLoc + childPos;
        result.mEntryLoc = destLoc;
    }

    /**
     * Provides information necessary to complete split by copying split key, pointer to
     * split key, and pointer to new child id.
     */
    static final class InResult {
        /*P*/ byte[] mPage;
        int mNewChildLoc; // location of child pointer
        int mEntryLoc;    // location of key entry, referenced by search vector
    }

    private Split newSplitLeft(Node newNode) {
        Split split = new Split(false, newNode);
        // New left node cannot be a high extremity, and this node cannot be a low extremity.
        newNode.type((byte) (type() & ~HIGH_EXTREMITY));
        type((byte) (type() & ~LOW_EXTREMITY));
        return split;
    }

    private Split newSplitRight(Node newNode) {
        Split split = new Split(true, newNode);
        // New right node cannot be a low extremity, and this node cannot be a high extremity.
        newNode.type((byte) (type() & ~LOW_EXTREMITY));
        type((byte) (type() & ~HIGH_EXTREMITY));
        return split;
    }

    /**
     * Count the number of cursors bound to this node.
     */
    long countCursors() {
        long count = 0;

        acquireShared();
        try {
            TreeCursorFrame frame = mLastCursorFrame;
            while (frame != null) {
                count++;
                frame = frame.mPrevCousin;
            }
        } finally {
            releaseShared();
        }

        return count;
    }

    /**
     * No latches are acquired by this method -- it is only used for debugging.
     */
    @Override
    @SuppressWarnings("fallthrough")
    public String toString() {
        String prefix;

        switch (type()) {
        case TYPE_UNDO_LOG:
            return "UndoNode: {id=" + mId +
                ", cachedState=" + mCachedState +
                ", topEntry=" + garbage() +
                ", lowerNodeId=" + + p_longGetLE(mPage, 4) +
                ", lockState=" + super.toString() +
                '}';
        case TYPE_FRAGMENT:
            return "FragmentNode: {id=" + mId +
                ", cachedState=" + mCachedState +
                ", lockState=" + super.toString() +
                '}';
        case TYPE_TN_IN:
        case (TYPE_TN_IN | LOW_EXTREMITY):
        case (TYPE_TN_IN | HIGH_EXTREMITY):
        case (TYPE_TN_IN | LOW_EXTREMITY | HIGH_EXTREMITY):
            prefix = "Internal";
            break;

        case TYPE_TN_BIN:
        case (TYPE_TN_BIN | LOW_EXTREMITY):
        case (TYPE_TN_BIN | HIGH_EXTREMITY):
        case (TYPE_TN_BIN | LOW_EXTREMITY | HIGH_EXTREMITY):
            prefix = "BottomInternal";
            break;
        default:
            if (!isLeaf()) {
                return "Node: {id=" + mId +
                    ", cachedState=" + mCachedState +
                    ", lockState=" + super.toString() +
                    '}';
            }
            // Fallthrough...
        case TYPE_TN_LEAF:
            prefix = "Leaf";
            break;
        }

        return prefix + "Node: {id=" + mId +
            ", cachedState=" + mCachedState +
            ", isSplit=" + (mSplit != null) +
            ", availableBytes=" + availableBytes() +
            ", extremity=0b" + Integer.toString((type() & (LOW_EXTREMITY | HIGH_EXTREMITY)), 2) +
            ", lockState=" + super.toString() +
            '}';
    }

    /**
     * Caller must acquired shared latch before calling this method. Latch is
     * released unless an exception is thrown. If an exception is thrown by the
     * observer, the latch would have already been released.
     *
     * @return false if should stop
     */
    boolean verifyTreeNode(int level, VerificationObserver observer) {
        int type = type() & ~(LOW_EXTREMITY | HIGH_EXTREMITY);
        if (type != TYPE_TN_IN && type != TYPE_TN_BIN && !isLeaf()) {
            return verifyFailed(level, observer, "Not a tree node: " + type);
        }

        final /*P*/ byte[] page = mPage;

        if (leftSegTail() < TN_HEADER_SIZE) {
            return verifyFailed(level, observer, "Left segment tail: " + leftSegTail());
        }

        if (searchVecStart() < leftSegTail()) {
            return verifyFailed(level, observer, "Search vector start: " + searchVecStart());
        }

        if (searchVecEnd() < (searchVecStart() - 2)) {
            return verifyFailed(level, observer, "Search vector end: " + searchVecEnd());
        }

        if (rightSegTail() < searchVecEnd() || rightSegTail() > (p_length(page) - 1)) {
            return verifyFailed(level, observer, "Right segment tail: " + rightSegTail());
        }

        if (!isLeaf()) {
            int childIdsStart = searchVecEnd() + 2;
            int childIdsEnd = childIdsStart + ((childIdsStart - searchVecStart()) << 2) + 8;
            if (childIdsEnd > (rightSegTail() + 1)) {
                return verifyFailed(level, observer, "Child ids end: " + childIdsEnd);
            }

            LHashTable.Int childIds = new LHashTable.Int(512);

            for (int i = childIdsStart; i < childIdsEnd; i += 8) {
                long childId = p_uint48GetLE(page, i);
                if (childId < 0 || childId == 0 || childId == 1) {
                    return verifyFailed(level, observer, "Illegal child id: " + childId);
                }
                LHashTable.IntEntry e = childIds.insert(childId);
                if (e.value != 0) {
                    return verifyFailed(level, observer, "Duplicate child id: " + childId);
                }
                e.value = 1;
            }
        }

        int used = TN_HEADER_SIZE + rightSegTail() + 1 - leftSegTail();

        int largeValueCount = 0;

        int lastKeyLoc = 0;
        int lastKeyLen = 0;

        for (int i = searchVecStart(); i <= searchVecEnd(); i += 2) {
            int loc = p_ushortGetLE(page, i);

            if (loc < TN_HEADER_SIZE || loc >= p_length(page) ||
                (loc >= leftSegTail() && loc <= rightSegTail()))
            {
                return verifyFailed(level, observer, "Entry location: " + loc);
            }

            if (isLeaf()) {
                used += leafEntryLengthAtLoc(page, loc);
            } else {
                used += keyLengthAtLoc(page, loc);
            }

            int keyLen;
            try {
                keyLen = p_byteGet(page, loc++);
                keyLen = keyLen >= 0 ? (keyLen + 1)
                    : (((keyLen & 0x3f) << 8) | p_ubyteGet(page, loc++));
            } catch (IndexOutOfBoundsException e) {
                return verifyFailed(level, observer, "Key location out of bounds");
            }

            if (loc + keyLen > p_length(page)) {
                return verifyFailed(level, observer, "Key end location: " + (loc + keyLen));
            }

            if (lastKeyLoc != 0) {
                int result = p_compareKeysPageToPage(page, lastKeyLoc, lastKeyLen,
                                                     page, loc, keyLen);
                if (result >= 0) {
                    return verifyFailed(level, observer, "Key order: " + result);
                }
            }

            lastKeyLoc = loc;
            lastKeyLoc = keyLen;

            if (isLeaf()) value: {
                int len;
                try {
                    loc += keyLen;
                    int header = p_byteGet(page, loc++);
                    if (header >= 0) {
                        len = header;
                    } else {
                        if ((header & 0x20) == 0) {
                            len = 1 + (((header & 0x1f) << 8) | p_ubyteGet(page, loc++));
                        } else if (header != -1) {
                            len = 1 + (((header & 0x0f) << 16)
                                       | (p_ubyteGet(page, loc++) << 8) | p_ubyteGet(page, loc++));
                        } else {
                            // ghost
                            break value;
                        }
                        if ((header & ENTRY_FRAGMENTED) != 0) {
                            largeValueCount++;
                        }
                    }
                } catch (IndexOutOfBoundsException e) {
                    return verifyFailed(level, observer, "Value location out of bounds");
                }
                if (loc + len > p_length(page)) {
                    return verifyFailed(level, observer, "Value end location: " + (loc + len));
                }
            }
        }

        int garbage = p_length(page) - used;

        if (garbage() != garbage) {
            return verifyFailed(level, observer, "Garbage: " + garbage() + " != " + garbage);
        }

        int entryCount = numKeys();
        int freeBytes = availableBytes();

        long id = mId;
        releaseShared();
        return observer.indexNodePassed(id, level, entryCount, freeBytes, largeValueCount);
    }

    private boolean verifyFailed(int level, VerificationObserver observer, String message) {
        long id = mId;
        releaseShared();
        observer.failed = true;
        return observer.indexNodeFailed(id, level, message);
    }
}
