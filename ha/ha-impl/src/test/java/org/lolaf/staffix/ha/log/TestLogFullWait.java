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
package org.lolaf.staffix.ha.log;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TestLogFullWait {

    @Test
    void anAppendRetriesWithinTheTimeout() {
        LogFullWait wait = new LogFullWait(Duration.ofMinutes(1));

        wait.reset();

        assertThat(wait.awaitRetry()).isTrue();
    }

    @Test
    void anAppendGivesUpOnceTheTimeoutHasPassedSinceItsFirstRetry() {
        Duration timeout = Duration.ofMillis(20);
        LogFullWait wait = new LogFullWait(timeout);
        long startNanos = System.nanoTime();

        wait.reset();
        while (wait.awaitRetry()) {
            assertThat(System.nanoTime() - startNanos).isLessThan(Duration.ofSeconds(10).toNanos());
        }

        assertThat(System.nanoTime() - startNanos).isGreaterThanOrEqualTo(timeout.toNanos());
    }
}
