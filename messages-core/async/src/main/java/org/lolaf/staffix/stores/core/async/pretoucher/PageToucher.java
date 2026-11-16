/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.stores.core.async.pretoucher;

import lombok.RequiredArgsConstructor;
import net.openhft.chronicle.bytes.MappedBytesStore;
import net.openhft.chronicle.bytes.MappedFile;
import net.openhft.chronicle.core.io.ReferenceOwner;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Touches the pages of one mapped file ahead of a given position, never touching a page twice.
 *
 * <p>It touches through the file's shared chunk mappings, the ones the writer uses, since a page is provided per
 * mapping: touching it through a mapping of its own would leave the writer's fault in place. It holds the chunk it
 * touched last, so that chunk stays mapped until the writer reaches it.
 */
@RequiredArgsConstructor
final class PageToucher implements ReferenceOwner, AutoCloseable {

    /**
     * Bounds a call, so that a stop waiting for it is not held up; it also caps the write rate a queue can be kept ahead
     * of at this many pages per pretouch interval.
     */
    static final int MAX_PAGES_PER_CALL = 64;

    private final MappedFile mappedFile;
    private final int pageSize;
    /**
     * Pages the writer reached first, so it took their fault: those it had written when touched, and those it moved
     * past between two calls.
     */
    private final AtomicLong missedPages;
    private MappedBytesStore chunk;
    private long firstUntouchedPage;

    /**
     * Touches the pages after the one holding {@code position}, up to {@code position + aheadBytes} but no further
     * than half a chunk, at most {@link #MAX_PAGES_PER_CALL} of them: the next call goes on from there.
     *
     * @return the number of pages touched
     */
    int touchAhead(long position, long aheadBytes) throws IOException {
        long end = position + Math.min(aheadBytes, mappedFile.chunkSize() / 2);
        long firstPageAhead = pageAfter(position);
        countPagesTheWriterPassed(firstPageAhead);
        long page = Math.max(firstUntouchedPage, firstPageAhead);
        int touched = 0;
        for (; page < end && touched < MAX_PAGES_PER_CALL; page += pageSize, touched++) {
            if (chunk == null || !chunk.inside(page)) {
                acquireChunkOf(page);
            }
            // a write access that never changes a value, so it is safe on a page the writer is filling
            if (!chunk.compareAndSwapLong(page, 0, 0)) {
                missedPages.incrementAndGet();
            }
        }
        firstUntouchedPage = Math.max(firstUntouchedPage, page);
        return touched;
    }

    @Override
    public void close() {
        releaseChunk();
    }

    private void countPagesTheWriterPassed(long firstPageAhead) {
        boolean touchedBefore = firstUntouchedPage > 0;
        if (touchedBefore && firstPageAhead > firstUntouchedPage) {
            missedPages.addAndGet((firstPageAhead - firstUntouchedPage) / pageSize);
        }
    }

    private long pageAfter(long position) {
        return (position / pageSize + 1) * pageSize;
    }

    private void acquireChunkOf(long page) throws IOException {
        releaseChunk();
        chunk = mappedFile.acquireByteStore(this, page);
    }

    private void releaseChunk() {
        if (chunk != null) {
            chunk.release(this);
            chunk = null;
        }
    }
}
