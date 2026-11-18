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
package org.lolaf.staffix.stores.messages.file;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestSeqNumIndex {

    private final Set<Long> inFile = new HashSet<>();
    private final SeqNumIndex index = new SeqNumIndex((seqNum, position) -> inFile.contains(seqNum));

    @Test
    void aFindFromBelowTheFileSkipsTheEntriesOfOverwrittenMessages() {
        write(1, 1_000);
        overwrite(1, 900);

        index.dropOverwritten();

        assertThat(handed(1, Long.MAX_VALUE)).hasSize(100).startsWith(901L).endsWith(1_000L);
    }

    @Test
    void anEntryStillInTheFileStopsTheSkipping() {
        write(1, 1_000);
        overwrite(1, 400);
        overwrite(501, 900);

        index.dropOverwritten();

        assertThat(handed(1, Long.MAX_VALUE)).hasSize(600).startsWith(401L);
    }

    @Test
    void aMergedEntryStillInTheFileIsNotSkipped() {
        write(1, 100);
        overwrite(1, 10);
        index.add(5, 0);
        inFile.add(5L);

        index.dropOverwritten();

        assertThat(handed(1, Long.MAX_VALUE)).startsWith(5L).hasSize(96);
    }

    private void write(long from, long to) {
        for (long seqNum = from; seqNum <= to; seqNum++) {
            index.add(seqNum, (int) seqNum);
            inFile.add(seqNum);
        }
    }

    private void overwrite(long from, long to) {
        for (long seqNum = from; seqNum <= to; seqNum++) {
            inFile.remove(seqNum);
        }
    }

    private List<Long> handed(long start, long stop) {
        List<Long> handed = new ArrayList<>();
        index.find(start, stop, (seqNum, position) -> handed.add(seqNum));
        return handed;
    }
}
