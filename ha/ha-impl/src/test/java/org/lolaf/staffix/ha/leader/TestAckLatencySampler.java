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
package org.lolaf.staffix.ha.leader;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.monitoring.Timer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestAckLatencySampler {

    private final List<Long> recorded = new CopyOnWriteArrayList<>();
    private final AckLatencySampler sampler = new AckLatencySampler(new Timer() {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void record(long duration, TimeUnit unit) {
            recorded.add(unit.toNanos(duration));
        }
    });

    @Test
    void anAckBeforeTheSampledEventRecordsNothing() {
        sampler.sent(100, System.nanoTime());

        sampler.acked(99);

        assertThat(recorded).isEmpty();
    }

    @Test
    void theAckCoveringTheSampledEventRecordsTheTimeSinceItWasSentOnce() {
        sampler.sent(100, System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(5));

        sampler.acked(150);
        sampler.acked(200);

        assertThat(recorded).singleElement().satisfies(nanos -> assertThat(nanos).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(5)));
    }

    @Test
    void eventsSentWhileASampleWaitsAreNotSampled() {
        long first = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
        sampler.sent(100, first);
        sampler.sent(200, System.nanoTime());

        sampler.acked(200);
        sampler.acked(300);

        assertThat(recorded).singleElement().satisfies(nanos -> assertThat(nanos).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(1)));
    }

    @Test
    void theNextEventSentAfterAnAckIsSampled() {
        sampler.sent(100, System.nanoTime());
        sampler.acked(100);
        sampler.sent(200, System.nanoTime());

        sampler.acked(200);

        assertThat(recorded).hasSize(2);
    }
}
