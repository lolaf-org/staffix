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
package org.lolaf.staffix.impl.session;

import org.assertj.core.api.Assertions;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

class TestClockImpl {

    private ClockImpl clock;

    @Test
    void testGetTimeWithOptimizedClock() {
        clock = new ClockImpl(true);
        UTCTime utcTime = clock.now();

        Assertions.assertThat(utcTime.getEpochSeconds())
                .isCloseTo(Instant.now().getEpochSecond(), Offset.offset(1L));
    }

    @Test
    void testGetNanoEpochTimeWithOptimizedClock() {
        clock = new ClockImpl(true);
        UTCTime utcTime = clock.now();
        Assertions.assertThat(clock.nowEpochNanos())
                .isCloseTo(utcTime.toEpochNanos(), Offset.offset(TimeUnit.MICROSECONDS.toNanos(900)));
    }

    @Test
    void testGetTimeWithInstantImpl() {
        clock = new ClockImpl(false);
        UTCTime utcTime = clock.now();

        Assertions.assertThat(utcTime.getEpochSeconds())
                .isCloseTo(Instant.now().getEpochSecond(), Offset.offset(1L));
    }

    @Test
    void testGetNanoEpochTimeWithInstantImpl() {
        clock = new ClockImpl(false);
        UTCTime utcTime = clock.now();

        Assertions.assertThat(clock.nowEpochNanos())
                .isCloseTo(utcTime.toEpochNanos(), Offset.offset(TimeUnit.MICROSECONDS.toNanos(250)));
    }
}
