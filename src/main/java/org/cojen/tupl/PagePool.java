/*
 *  Copyright 2011-2015 Cojen.org
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

import org.cojen.tupl.util.Latch;
import org.cojen.tupl.util.LatchCondition;

/**
 * Pool of spare page buffers not currently in use by nodes.
 *
 * @author Brian S O'Neill
 */
@SuppressWarnings("serial")
final class PagePool extends Latch {
    private final transient LatchCondition mQueue;
    private final /*P*/ byte[][] mPool;
    private int mPos;

    PagePool(int pageSize, int poolSize) {
        mQueue = new LatchCondition();
        /*P*/ byte[][] pool = PageOps.p_allocArray(poolSize);
        for (int i=0; i<poolSize; i++) {
            pool[i] = PageOps.p_calloc(pageSize);
        }
        mPool = pool;
        mPos = poolSize;
    }

    /**
     * Remove a page from the pool, waiting for one to become available if necessary.
     */
    /*P*/ byte[] remove() {
        acquireExclusive();
        try {
            int pos;
            while ((pos = mPos) == 0) {
                mQueue.await(this, -1, 0);
            }
            return mPool[mPos = pos - 1];
        } finally {
            releaseExclusive();
        }
    }

    /**
     * Add a previously removed page back into the pool.
     */
    void add(/*P*/ byte[] page) {
        acquireExclusive();
        try {
            int pos = mPos;
            mPool[pos] = page;
            // Adjust pos after assignment to prevent harm if an array bounds exception was thrown.
            mPos = pos + 1;
            mQueue.signal();
        } finally {
            releaseExclusive();
        }
    }

    /**
     * Must be called when object is no longer referenced.
     */
    void delete() {
        acquireExclusive();
        try {
            for (int i=0; i<mPos; i++) {
                /*P*/ byte[] page = mPool[i];
                mPool[i] = PageOps.p_null();
                PageOps.p_delete(page);
            }
        } finally {
            releaseExclusive();
        }
    }
}
