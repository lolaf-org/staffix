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
package org.lolaf.staffix;

import lombok.Setter;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;

public class TestingClock implements Clock {

    private static final TestingClock CLOCK = new TestingClock();
    @Setter
    private long epochSeconds;
    @Setter
    private int nanoOfSecond;
    @Setter
    private long nowEpochMillis;
    @Setter
    private long nowEpochNanos;
    @Setter
    private long nanoTime;

    public static TestingClock get() {
        CLOCK.reset();
        return CLOCK;
    }

    public void reset() {
        epochSeconds = 0;
        nanoOfSecond = 0;
        nowEpochNanos = 0;
        nowEpochMillis = 0;
    }

    @Override
    public UTCTime now() {
        return new UTCTime() {
            @Override
            public long getEpochSeconds() {
                return epochSeconds;
            }

            @Override
            public int getNanosOfSecond() {
                return nanoOfSecond++;
            }

            @Override
            public UTCTime asImmutable() {
                return this;
            }

            @Override
            public boolean isImmutable() {
                return true;
            }
        };
    }

    @Override
    public long nowEpochMillis() {
        return nowEpochMillis;
    }

    @Override
    public long nowEpochNanos() {
        return nowEpochNanos;
    }

    @Override
    public long nanoTime() {
        return nanoTime;
    }
}
