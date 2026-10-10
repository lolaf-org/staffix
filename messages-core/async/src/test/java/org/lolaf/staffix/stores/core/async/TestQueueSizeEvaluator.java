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
package org.lolaf.staffix.stores.core.async;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.AlertLevel;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TestQueueSizeEvaluator {

    private final AtomicInteger size = new AtomicInteger();
    private final QueueSizeEvaluator evaluator = new QueueSizeEvaluator(size::get, AsyncStoreSettings.QueueSizeThresholdsSettings.builder()
            .thresholds(Map.of(AlertLevel.WARNING, 300, AlertLevel.CRITICAL, 500)).build());

    @Test
    void raisesTheHighestLevelReachedAndClearsBelowTheLowest() {
        assertThat(levelAt(299)).isEmpty();
        assertThat(levelAt(300)).contains(AlertLevel.WARNING);
        assertThat(levelAt(500)).contains(AlertLevel.CRITICAL);
        assertThat(levelAt(400)).contains(AlertLevel.WARNING);
        assertThat(levelAt(200)).isEmpty();
    }

    @Test
    void theDefaultsGoThroughEveryLevel() {
        QueueSizeEvaluator defaults = new QueueSizeEvaluator(size::get, AsyncStoreSettings.QueueSizeThresholdsSettings.builder().build());

        size.set(249);
        assertThat(defaults.evaluate()).isEmpty();
        size.set(250);
        assertThat(defaults.evaluate()).map(Alert::getLevel).contains(AlertLevel.WARNING);
        size.set(1000);
        assertThat(defaults.evaluate()).map(Alert::getLevel).contains(AlertLevel.MAJOR);
        size.set(5000);
        assertThat(defaults.evaluate()).map(Alert::getLevel).contains(AlertLevel.CRITICAL);
    }

    @Test
    void theMessageCarriesTheDepthAndThreshold() {
        size.set(320);

        assertThat(evaluator.evaluate()).map(Alert::getMessage).contains("320 events queued, WARNING from 300");
    }

    @Test
    void theProbeIsEvaluatedAtTheThresholdsInterval() {
        AsyncStoreSettings.QueueSizeThresholdsSettings thresholds = AsyncStoreSettings.QueueSizeThresholdsSettings.builder()
                .evaluationInterval(Duration.ofSeconds(5)).build();

        assertThat(QueueSizeEvaluator.probe("queue", "d", new PersistentQueue<>(null), thresholds).getEvaluationInterval())
                .isEqualTo(Duration.ofSeconds(5));
    }

    private Optional<AlertLevel> levelAt(int depth) {
        size.set(depth);
        return evaluator.evaluate().map(Alert::getLevel);
    }
}
