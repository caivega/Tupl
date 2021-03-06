/*
 *  Copyright 2016 Cojen.org
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

import java.util.Arrays;

/**
 * Unnamed tree which prohibits redo durabilty.
 *
 * @author Brian S O'Neill
 */
/*P*/
final class TempTree extends Tree {
    TempTree(LocalDatabase db, long id, byte[] idBytes, byte[] name, Node root) {
        super(db, id, idBytes, name, root);
    }

    @Override
    public TreeCursor newCursor(Transaction txn) {
        return new TempTreeCursor(this, txn);
    }

    @Override
    public void store(Transaction txn, byte[] key, byte[] value) throws IOException {
        if (txn == null) {
            super.store(Transaction.BOGUS, key, value);
        } else {
            txnStore(txn, key, value);
        }
    }

    private void txnStore(Transaction txn, byte[] key, byte[] value) throws IOException {
        final DurabilityMode dmode = txn.durabilityMode();
        if (dmode == DurabilityMode.NO_REDO) {
            super.store(txn, key, value);
        } else {
            txn.durabilityMode(DurabilityMode.NO_REDO);
            try {
                super.store(txn, key, value);
            } finally {
                txn.durabilityMode(dmode);
            }
        }
    }

    @Override
    public byte[] exchange(Transaction txn, byte[] key, byte[] value) throws IOException {
        if (txn == null) {
            return super.exchange(Transaction.BOGUS, key, value);
        } else {
            return txnExchange(txn, key, value);
        }
    }

    private byte[] txnExchange(Transaction txn, byte[] key, byte[] value) throws IOException {
        final DurabilityMode dmode = txn.durabilityMode();
        if (dmode == DurabilityMode.NO_REDO) {
            return super.exchange(txn, key, value);
        } else {
            txn.durabilityMode(DurabilityMode.NO_REDO);
            try {
                return super.exchange(txn, key, value);
            } finally {
                txn.durabilityMode(dmode);
            }
        }
    }

    @Override
    public boolean insert(Transaction txn, byte[] key, byte[] value) throws IOException {
        if (txn == null) {
            return super.insert(Transaction.BOGUS, key, value);
        } else {
            return txnInsert(txn, key, value);
        }
    }

    private boolean txnInsert(Transaction txn, byte[] key, byte[] value) throws IOException {
        final DurabilityMode dmode = txn.durabilityMode();
        if (dmode == DurabilityMode.NO_REDO) {
            return super.insert(txn, key, value);
        } else {
            txn.durabilityMode(DurabilityMode.NO_REDO);
            try {
                return super.insert(txn, key, value);
            } finally {
                txn.durabilityMode(dmode);
            }
        }
    }

    @Override
    public boolean replace(Transaction txn, byte[] key, byte[] value) throws IOException {
        if (txn == null) {
            return super.replace(Transaction.BOGUS, key, value);
        } else {
            return txnReplace(txn, key, value);
        }
    }

    private boolean txnReplace(Transaction txn, byte[] key, byte[] value) throws IOException {
        final DurabilityMode dmode = txn.durabilityMode();
        if (dmode == DurabilityMode.NO_REDO) {
            return super.replace(txn, key, value);
        } else {
            txn.durabilityMode(DurabilityMode.NO_REDO);
            try {
                return super.replace(txn, key, value);
            } finally {
                txn.durabilityMode(dmode);
            }
        }
    }

    @Override
    public boolean update(Transaction txn, byte[] key, byte[] oldValue, byte[] newValue)
        throws IOException
    {
        if (txn == null) {
            return super.update(Transaction.BOGUS, key, oldValue, newValue);
        } else {
            return txnUpdate(txn, key, oldValue, newValue);
        }
    }

    public boolean txnUpdate(Transaction txn, byte[] key, byte[] oldValue, byte[] newValue)
        throws IOException
    {
        final DurabilityMode dmode = txn.durabilityMode();
        if (dmode == DurabilityMode.NO_REDO) {
            return super.update(txn, key, oldValue, newValue);
        } else {
            txn.durabilityMode(DurabilityMode.NO_REDO);
            try {
                return super.update(txn, key, oldValue, newValue);
            } finally {
                txn.durabilityMode(dmode);
            }
        }
    }

    /*
    @Override
    public Stream newStream() {
        TreeCursor cursor = new TempTreeCursor(this);
        cursor.autoload(false);
        return new TreeValueStream(cursor);
    }
    */
}
