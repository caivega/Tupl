/*
 *  Copyright 2012-2013 Brian S O'Neill
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

/**
 * 
 *
 * @author Brian S O'Neill
 */
final class ReverseView implements View {
    private final View mSource;

    ReverseView(View source) {
        mSource = source;
    }

    @Override
    public Cursor newCursor(Transaction txn) {
        return new ReverseCursor(mSource.newCursor(txn));
    }

    @Override
    public byte[] load(Transaction txn, byte[] key) throws IOException {
        return mSource.load(txn, key);
    }

    @Override
    public void store(Transaction txn, byte[] key, byte[] value) throws IOException {
        mSource.store(txn, key, value);
    }

    @Override
    public byte[] exchange(Transaction txn, byte[] key, byte[] value) throws IOException {
        return mSource.exchange(txn, key, value);
    }

    @Override
    public boolean insert(Transaction txn, byte[] key, byte[] value) throws IOException {
        return mSource.insert(txn, key, value);
    }

    @Override
    public boolean replace(Transaction txn, byte[] key, byte[] value) throws IOException {
        return mSource.replace(txn, key, value);
    }

    @Override
    public boolean update(Transaction txn, byte[] key, byte[] oldValue, byte[] newValue)
        throws IOException
    {
        return mSource.update(txn, key, oldValue, newValue);
    }

    @Override
    public boolean delete(Transaction txn, byte[] key) throws IOException {
        return mSource.delete(txn, key);
    }

    @Override
    public boolean remove(Transaction txn, byte[] key, byte[] value) throws IOException {
        return mSource.remove(txn, key, value);
    }

    @Override
    public Stream newStream() {
        return mSource.newStream();
    }

    @Override
    public View viewGe(byte[] key) {
        return new ReverseView(mSource.viewLe(key));
    }

    @Override
    public View viewGt(byte[] key) {
        return new ReverseView(mSource.viewLt(key));
    }

    @Override
    public View viewLe(byte[] key) {
        return new ReverseView(mSource.viewGe(key));
    }

    @Override
    public View viewLt(byte[] key) {
        return new ReverseView(mSource.viewGt(key));
    }

    @Override
    public View viewReverse() {
        return mSource;
    }

    @Override
    public View viewUnmodifiable() {
        return UnmodifiableView.apply(this);
    }

    @Override
    public boolean isUnmodifiable() {
        return mSource.isUnmodifiable();
    }
}