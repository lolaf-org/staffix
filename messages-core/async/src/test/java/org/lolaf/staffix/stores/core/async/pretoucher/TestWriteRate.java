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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.lolaf.staffix.stores.core.async.pretoucher.WriteRate.DECAY_NANOS;
import static org.lolaf.staffix.stores.core.async.pretoucher.WriteRate.MIN_SAMPLE_NANOS;

class TestWriteRate {

    private static final long MILLI = 1_000_000;

    final WriteRate writeRate = new WriteRate();

    @Test
    void isZeroBeforeASample() {
        writeRate.update(1000, 0);

        assertThat(writeRate.bytesIn(MILLI)).isZero();
    }

    @Test
    void measuresBytesPerNanosecond() {
        writeRate.restartAt(0, 0);

        writeRate.update(MILLI, MILLI);

        assertThat(writeRate.bytesIn(40 * MILLI)).isEqualTo(40 * MILLI);
    }

    @Test
    void mergesASampleTooShortIntoTheNext() {
        writeRate.restartAt(0, 0);

        writeRate.update(MILLI, MIN_SAMPLE_NANOS / 1000);
        assertThat(writeRate.bytesIn(MILLI)).isZero();

        writeRate.update(MILLI, 2 * MIN_SAMPLE_NANOS);
        assertThat(writeRate.bytesIn(2 * MIN_SAMPLE_NANOS)).isEqualTo(MILLI);
    }

    @Test
    void risesAtOnceToAFasterSample() {
        writeRate.restartAt(0, 0);
        writeRate.update(MILLI, MILLI);

        writeRate.update(11 * MILLI, 2 * MILLI);

        assertThat(writeRate.bytesIn(MILLI)).isEqualTo(10 * MILLI);
    }

    @Test
    void decaysSlowlyWhenTheWriterPauses() {
        writeRate.restartAt(0, 0);
        writeRate.update(MILLI, MILLI);

        long now = MILLI;
        for (int i = 0; i < 10; i++) {
            now += MILLI;
            writeRate.update(MILLI, now);
        }
        assertThat(writeRate.bytesIn(MILLI)).isGreaterThan(MILLI * 98 / 100);

        writeRate.update(MILLI, now + DECAY_NANOS);
        assertThat(writeRate.bytesIn(MILLI)).isZero();
    }

    @Test
    void keepsTheRateAcrossANewFile() {
        writeRate.restartAt(0, 0);
        writeRate.update(10 * MILLI, MILLI);

        writeRate.restartAt(100, MILLI);
        writeRate.update(100, MILLI + MIN_SAMPLE_NANOS / 2);

        assertThat(writeRate.bytesIn(MILLI)).isEqualTo(10 * MILLI);
    }

    @Test
    void restartsWhenThePositionGoesBack() {
        writeRate.restartAt(10 * MILLI, 0);

        writeRate.update(100, MILLI);
        writeRate.update(100 + MILLI, 2 * MILLI);

        assertThat(writeRate.bytesIn(MILLI)).isEqualTo(MILLI);
    }
}
