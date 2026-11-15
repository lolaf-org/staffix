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

import net.openhft.chronicle.bytes.MappedBytesStore;
import net.openhft.chronicle.bytes.MappedFile;
import net.openhft.chronicle.core.io.ReferenceOwner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TestPageToucher {

    private static final int PAGE_SIZE = 4096;
    private static final long CHUNK_SIZE = 16L << 20;

    final AtomicLong missedPages = new AtomicLong();
    MappedFile mappedFile;
    PageToucher pageToucher;

    @AfterEach
    void shutdown() {
        pageToucher.close();
        mappedFile.releaseLast();
    }

    private void open(long chunkSize) throws IOException {
        File file = new File("./target/page-toucher-" + System.nanoTime());
        file.deleteOnExit();
        mappedFile = MappedFile.mappedFile(file, chunkSize);
        pageToucher = new PageToucher(mappedFile, PAGE_SIZE, missedPages);
    }

    private int touchAll(long position, long aheadBytes) throws IOException {
        int touched = 0;
        for (int pages; (pages = pageToucher.touchAhead(position, aheadBytes)) > 0; ) {
            assertThat(pages).isLessThanOrEqualTo(PageToucher.MAX_PAGES_PER_CALL);
            touched += pages;
        }
        return touched;
    }

    @Test
    void touchesAtMostABudgetPerCallAndGoesOnFromThere() throws IOException {
        open(CHUNK_SIZE);
        long aheadBytes = 4L << 20;

        assertThat(pageToucher.touchAhead(0, aheadBytes)).isEqualTo(PageToucher.MAX_PAGES_PER_CALL);
        assertThat(touchAll(0, aheadBytes) + PageToucher.MAX_PAGES_PER_CALL)
                .as("every page after the first, up to the distance ahead")
                .isEqualTo((int) (aheadBytes / PAGE_SIZE) - 1);
    }

    @Test
    void neverTouchesAPageTwice() throws IOException {
        open(CHUNK_SIZE);
        pageToucher.touchAhead(0, 16 * PAGE_SIZE);

        assertThat(pageToucher.touchAhead(8 * PAGE_SIZE, 16 * PAGE_SIZE)).isEqualTo(8);
    }

    @Test
    void staysWithinHalfAChunkAhead() throws IOException {
        open(1 << 20);

        assertThat(touchAll(0, 4L << 20)).isEqualTo((int) ((1 << 19) / PAGE_SIZE) - 1);
    }

    @Test
    void countsThePagesTheWriterMovedPastBetweenTwoCalls() throws IOException {
        open(CHUNK_SIZE);
        pageToucher.touchAhead(0, 16 * PAGE_SIZE);

        pageToucher.touchAhead(40 * PAGE_SIZE, 16 * PAGE_SIZE);

        assertThat(missedPages).as("pages 16 to 40, the writer's included").hasValue(25);
    }

    @Test
    void theFirstCallMissesNothing() throws IOException {
        open(CHUNK_SIZE);

        pageToucher.touchAhead(40 * PAGE_SIZE, 16 * PAGE_SIZE);

        assertThat(missedPages).hasValue(0);
    }

    @Test
    void countsThePagesTheWriterReachedFirst() throws IOException {
        open(CHUNK_SIZE);
        ReferenceOwner writer = ReferenceOwner.temporary("writer");
        MappedBytesStore written = mappedFile.acquireByteStore(writer, 0);
        try {
            written.writeLong(2 * PAGE_SIZE, 42);
            written.writeLong(5 * PAGE_SIZE, 42);
        } finally {
            written.release(writer);
        }

        touchAll(0, 16 * PAGE_SIZE);

        assertThat(missedPages).hasValue(2);
    }
}
