/*
 *  Copyright 2012-2015 Cojen.org
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

import java.io.Flushable;
import java.io.IOException;

import java.util.Random;

import org.cojen.tupl.io.CauseCloseable;

import static org.cojen.tupl.RedoOps.*;
import static org.cojen.tupl.Utils.*;

/**
 * Abstract class for active transactions to write into.
 *
 * @author Brian S O'Neill
 * @see RedoDecoder
 */
/*P*/
abstract class RedoWriter implements CauseCloseable, ShutdownHook, Flushable {
    private final byte[] mBuffer;
    private int mBufferPos;

    private long mLastTxnId;

    private boolean mAlwaysFlush;

    volatile Throwable mCause;

    RedoWriter(int bufferSize, long initialTxnId) {
        mBuffer = new byte[bufferSize];
        mLastTxnId = initialTxnId;
    }

    /**
     * Auto-commit transactional store.
     *
     * @param indexId non-zero index id
     * @param key non-null key
     * @param value value to store; null to delete
     * @return non-zero position if caller should call txnCommitSync
     */
    public long store(long indexId, byte[] key, byte[] value, DurabilityMode mode)
        throws IOException
    {
        keyCheck(key);

        synchronized (this) {
            if (value == null) {
                writeOp(OP_DELETE, indexId);
                writeUnsignedVarInt(key.length);
                writeBytes(key);
            } else {
                writeOp(OP_STORE, indexId);
                writeUnsignedVarInt(key.length);
                writeBytes(key);
                writeUnsignedVarInt(value.length);
                writeBytes(value);
            }
            writeTerminator();

            return commitFlush(mode);
        }
    }

    /**
     * Auto-commit non-transactional store.
     *
     * @param indexId non-zero index id
     * @param key non-null key
     * @param value value to store; null to delete
     * @return non-zero position if caller should call txnCommitSync
     */
    public long storeNoLock(long indexId, byte[] key, byte[] value, DurabilityMode mode)
        throws IOException
    {
        keyCheck(key);

        synchronized (this) {
            if (value == null) {
                writeOp(OP_DELETE_NO_LOCK, indexId);
                writeUnsignedVarInt(key.length);
                writeBytes(key);
            } else {
                writeOp(OP_STORE_NO_LOCK, indexId);
                writeUnsignedVarInt(key.length);
                writeBytes(key);
                writeUnsignedVarInt(value.length);
                writeBytes(value);
            }
            writeTerminator();

            return commitFlush(mode);
        }
    }

    /**
     * Auto-commit index rename.
     *
     * @param indexId non-zero index id
     * @param newName non-null new index name
     * @return non-zero position if caller should call txnCommitSync
     */
    public synchronized long renameIndex(long txnId, long indexId, byte[] newName,
                                         DurabilityMode mode)
        throws IOException
    {
        writeTxnOp(OP_RENAME_INDEX, txnId);
        writeLongLE(indexId);
        writeUnsignedVarInt(newName.length);
        writeBytes(newName);
        writeTerminator();
        return commitFlush(mode);
    }

    /**
     * Auto-commit index delete.
     *
     * @param indexId non-zero index id
     * @return non-zero position if caller should call txnCommitSync
     */
    public synchronized long deleteIndex(long txnId, long indexId, DurabilityMode mode)
        throws IOException
    {
        writeTxnOp(OP_DELETE_INDEX, txnId);
        writeLongLE(indexId);
        writeTerminator();
        return commitFlush(mode);
    }

    public synchronized void reset() throws IOException {
        writeOp(OP_RESET);
        mLastTxnId = 0;
        writeTerminator();
    }

    public synchronized void txnEnter(long txnId) throws IOException {
        writeTxnOp(OP_TXN_ENTER, txnId);
        writeTerminator();
    }

    public synchronized void txnRollback(long txnId) throws IOException {
        writeTxnOp(OP_TXN_ROLLBACK, txnId);
        writeTerminator();
    }

    public synchronized void txnRollbackFinal(long txnId) throws IOException {
        writeTxnOp(OP_TXN_ROLLBACK_FINAL, txnId);
        writeTerminator();
    }

    public synchronized void txnCommit(long txnId) throws IOException {
        writeTxnOp(OP_TXN_COMMIT, txnId);
        writeTerminator();
    }

    /**
     * @return non-zero position if caller should call txnCommitSync
     */
    public synchronized long txnCommitFinal(long txnId, DurabilityMode mode)
        throws IOException
    {
        writeTxnOp(OP_TXN_COMMIT_FINAL, txnId);
        writeTerminator();
        return commitFlush(mode);
    }

    /**
     * Called after txnCommitFinal.
     *
     * @param txn transaction committed
     * @param commitPos highest position to sync (exclusive)
     */
    public void txnCommitSync(LocalTransaction txn, long commitPos) throws IOException {
        sync(false);
    }

    /**
     * Called after txnCommitFinal.
     *
     * @param pending pending transaction committed
     */
    public void txnCommitPending(PendingTxn pending) throws IOException {
        throw new UnsupportedOperationException();
    }

    public synchronized void txnStore(byte op, long txnId, long indexId, byte[] key, byte[] value)
        throws IOException
    {
        keyCheck(key);
        writeTxnOp(op, txnId);
        writeLongLE(indexId);
        writeUnsignedVarInt(key.length);
        writeBytes(key);
        writeUnsignedVarInt(value.length);
        writeBytes(value);
        writeTerminator();
    }

    /**
     * @return non-zero position if caller should call txnCommitSync
     */
    public synchronized long txnStoreCommitFinal(long txnId, long indexId,
                                                 byte[] key, byte[] value, DurabilityMode mode)
        throws IOException
    {
        txnStore(OP_TXN_STORE_COMMIT_FINAL, txnId, indexId, key, value);
        return commitFlush(mode);
    }

    public synchronized void txnDelete(byte op, long txnId, long indexId, byte[] key)
        throws IOException
    {
        keyCheck(key);
        writeTxnOp(op, txnId);
        writeLongLE(indexId);
        writeUnsignedVarInt(key.length);
        writeBytes(key);
        writeTerminator();
    }

    /**
     * @return non-zero position if caller should call txnCommitSync
     */
    public synchronized long txnDeleteCommitFinal(long txnId, long indexId,
                                                  byte[] key, DurabilityMode mode)
        throws IOException
    {
        txnDelete(OP_TXN_DELETE_COMMIT_FINAL, txnId, indexId, key);
        return commitFlush(mode);
    }

    public synchronized void txnCustom(long txnId, byte[] message)
        throws IOException
    {
        if (message == null) {
            throw new NullPointerException("Message is null");
        }
        writeTxnOp(OP_TXN_CUSTOM, txnId);
        writeUnsignedVarInt(message.length);
        writeBytes(message);
        writeTerminator();
    }

    public synchronized void txnCustomLock(long txnId, byte[] message, long indexId, byte[] key)
        throws IOException
    {
        keyCheck(key);
        if (message == null) {
            throw new NullPointerException("Message is null");
        }
        writeTxnOp(OP_TXN_CUSTOM_LOCK, txnId);
        writeLongLE(indexId);
        writeUnsignedVarInt(key.length);
        writeBytes(key);
        writeUnsignedVarInt(message.length);
        writeBytes(message);
        writeTerminator();
    }

    public synchronized void timestamp() throws IOException {
        writeOp(OP_TIMESTAMP, System.currentTimeMillis());
        writeTerminator();
    }

    public synchronized void endFile() throws IOException {
        writeOp(OP_END_FILE, System.currentTimeMillis());
        writeTerminator();
        flush();
    }

    public synchronized void nopRandom() throws IOException {
        writeOp(OP_NOP_RANDOM, new Random().nextLong());
        writeTerminator();
        flush();
    }

    @Override
    public synchronized void flush() throws IOException {
        doFlush();
    }

    public void flushSync(boolean metadata) throws IOException {
        flush();
        sync(metadata);
    }

    @Override
    public final synchronized void close() throws IOException {
        close(null);
    }

    @Override
    public synchronized void close(Throwable cause) throws IOException {
        if (cause != null) {
            mCause = cause;
        }
        shutdown(OP_CLOSE);
    }

    @Override
    public void shutdown() {
        try {
            shutdown(OP_SHUTDOWN);
        } catch (IOException e) {
            // Ignore.
        }
    }

    private void shutdown(byte op) throws IOException {
        synchronized (this) {
            mAlwaysFlush = true;

            if (!isOpen()) {
                return;
            }

            writeOp(op, System.currentTimeMillis());
            writeTerminator();
            doFlush();

            // If shutdown hook is invoked, don't close the stream. It may interfere with other
            // shutdown hooks the user may have installed, causing unexpected exceptions to be
            // thrown during the whole shutdown sequence. Recovery may see additional
            // operations after the shutdown op and it may also see an unexpected end of file.
            // This is not harmful, and recovery needs to handle these cases anyhow.

            if (op == OP_CLOSE) {
                try {
                    forceAndClose();
                } catch (IOException e) {
                    throw rethrow(e, mCause);
                }
                return;
            }
        }

        force(true);
    }

    public abstract long encoding();

    /**
     * Return a new or existing RedoWriter for a new transaction.
     */
    public abstract RedoWriter txnRedoWriter();

    // Caller must be synchronized.
    abstract boolean isOpen();

    /**
     * Returns true if uncheckpointed redo size is at least the given threshold
     */
    abstract boolean shouldCheckpoint(long sizeThreshold);

    /**
     * Called before checkpointSwitch, to perform any expensive operations like opening a new
     * file. Method must not perform any checkpoint state transition.
     */
    abstract void checkpointPrepare() throws IOException;

    /**
     * With exclusive commit lock held, switch to the previously prepared state, also capturing
     * the checkpoint position and transaction id.
     */
    abstract void checkpointSwitch() throws IOException;

    /**
     * Returns the checkpoint number for the first change after the checkpoint switch.
     */
    abstract long checkpointNumber() throws IOException;

    /**
     * Returns the redo position for the first change after the checkpoint switch.
     */
    abstract long checkpointPosition() throws IOException;

    /**
     * Returns the transaction id for the first change after the checkpoint switch, which is
     * later used by recovery. If not needed by recovery, simply return 0.
     */
    abstract long checkpointTransactionId() throws IOException;

    /**
     * Called after checkpointPrepare and exclusive commit lock is released, but checkpoint is
     * aborted due to an exception.
     */
    abstract void checkpointAborted();

    /**
     * Called after exclusive commit lock is released. Dirty pages start flushing as soon as
     * this method returns.
     */
    abstract void checkpointStarted() throws IOException;

    /**
     * Called after all dirty pages have flushed.
     */
    abstract void checkpointFlushed() throws IOException;

    /**
     * Writer can discard all redo data lower than the checkpointed position, which was
     * captured earlier.
     */
    abstract void checkpointFinished() throws IOException;

    /**
     * @throws UnmodifiableReplicaException if a replica
     */
    // Caller must be synchronized.
    void opWriteCheck() throws IOException {
        // Non-replica by default.
    }

    /**
     * Negate the identifier if a replica, but leave alone otherwise.
     *
     * @param id new transaction identifier; greater than zero
     */
    long adjustTransactionId(long txnId) {
        // Non-replica by default.
        return txnId;
    }

    /**
     * Write to the physical log.
     */
    // Caller must be synchronized.
    abstract void write(byte[] buffer, int len) throws IOException;

    /**
     * Write to the physical log and commit.
     *
     * @return highest log position afterwards
     */
    // Caller must be synchronized.
    abstract long writeCommit(byte[] buffer, int len) throws IOException;

    abstract void force(boolean metadata) throws IOException;

    abstract void forceAndClose() throws IOException;

    // Caller must be synchronized.
    abstract void writeTerminator() throws IOException;

    synchronized void clearAndReset() throws IOException {
        mBufferPos = 0;
        reset();
    }

    // Caller must be synchronized.
    long lastTransactionId() {
        return mLastTxnId;
    }

    // Caller must be synchronized.
    void writeIntLE(int v) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos > buffer.length - 4) {
            doFlush(buffer, pos);
            pos = 0;
        }
        Utils.encodeIntLE(buffer, pos, v);
        mBufferPos = pos + 4;
    }

    // Caller must be synchronized.
    private void writeLongLE(long v) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos > buffer.length - 8) {
            doFlush(buffer, pos);
            pos = 0;
        }
        Utils.encodeLongLE(buffer, pos, v);
        mBufferPos = pos + 8;
    }

    // Caller must be synchronized.
    private void writeUnsignedVarInt(int v) throws IOException {
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos > buffer.length - 5) {
            doFlush(buffer, pos);
            pos = 0;
        }
        mBufferPos = Utils.encodeUnsignedVarInt(buffer, pos, v);
    }

    // Caller must be synchronized.
    private void writeBytes(byte[] bytes) throws IOException {
        writeBytes(bytes, 0, bytes.length);
    }

    // Caller must be synchronized.
    private void writeBytes(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) {
            return;
        }
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        while (true) {
            if (pos <= buffer.length - length) {
                System.arraycopy(bytes, offset, buffer, pos, length);
                mBufferPos = pos + length;
                return;
            }
            int remaining = buffer.length - pos;
            System.arraycopy(bytes, offset, buffer, pos, remaining);
            doFlush(buffer, buffer.length);
            pos = 0;
            offset += remaining;
            length -= remaining;
        }
    }

    // Caller must be synchronized.
    public void writeOp(byte op) throws IOException {
        opWriteCheck();
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos >= buffer.length - 1) { // 1 for op
            doFlush(buffer, pos);
            pos = 0;
        }
        buffer[pos] = op;
        mBufferPos = pos + 1;
    }

    // Caller must be synchronized.
    private void writeOp(byte op, long operand) throws IOException {
        opWriteCheck();
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos >= buffer.length - (1 + 8)) { // 1 for op, 8 for operand
            doFlush(buffer, pos);
            pos = 0;
        }
        buffer[pos] = op;
        Utils.encodeLongLE(buffer, pos + 1, operand);
        mBufferPos = pos + 9;
    }

    // Caller must be synchronized.
    private void writeTxnOp(byte op, long txnId) throws IOException {
        opWriteCheck();
        byte[] buffer = mBuffer;
        int pos = mBufferPos;
        if (pos >= buffer.length - (1 + 9)) { // 1 for op, up to 9 for txn delta
            doFlush(buffer, pos);
            pos = 0;
        }
        buffer[pos] = op;
        mBufferPos = Utils.encodeSignedVarLong(buffer, pos + 1, txnId - mLastTxnId);
        mLastTxnId = txnId;
    }

    // Caller must be synchronized.
    private void doFlush() throws IOException {
        doFlush(mBuffer, mBufferPos);
    }

    // Caller must be synchronized.
    private void doFlush(byte[] buffer, int len) throws IOException {
        try {
            write(buffer, len);
            // Discard only if write succeeds. Although caller is expected to rollback, redo
            // entries from other transactions might also exist.
            mBufferPos = 0;
        } catch (IOException e) {
            throw rethrow(e, mCause);
        }
    }

    private void sync(boolean metadata) throws IOException {
        try {
            force(metadata);
        } catch (IOException e) {
            throw rethrow(e, mCause);
        }
    }

    // Caller must be synchronized. Returns sync position.
    private long commitFlush(DurabilityMode mode) throws IOException {
        try {
            switch (mode) {
            default:
                return 0;
            case NO_FLUSH:
                if (mAlwaysFlush) {
                    doCommitFlush();
                }
                return 0;
            case SYNC:
                return doCommitFlush();
            case NO_SYNC:
                doCommitFlush();
                return 0;
            }
        } catch (IOException e) {
            throw rethrow(e, mCause);
        }
    }

    /**
     * @return highest log position afterwards
     */
    // Caller must be synchronized.
    private long doCommitFlush() throws IOException {
        // Discard buffer even if the write fails. Caller is expected to
        // rollback the transaction, and so redo is not used.
        int len = mBufferPos;
        mBufferPos = 0;
        return writeCommit(mBuffer, len);
    }
}
