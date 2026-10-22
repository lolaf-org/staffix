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
package org.lolaf.staffix.api.alerting;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestThresholdAlertEvaluator {

    private final AtomicLong bits = new AtomicLong();

    private Optional<Alert> evaluateAt(ThresholdAlertEvaluator evaluator, double current) {
        bits.set(Double.doubleToRawLongBits(current));
        return evaluator.evaluate();
    }

    private ThresholdAlertEvaluator.ThresholdAlertEvaluatorBuilder builder() {
        return ThresholdAlertEvaluator.builder().value(() -> Double.longBitsToDouble(bits.get()));
    }

    @Test
    void raisesTheHighestLevelReachedGoingUp() {
        ThresholdAlertEvaluator evaluator = builder().above(AlertLevel.WARNING, 300).above(AlertLevel.CRITICAL, 500).build();

        assertThat(evaluateAt(evaluator, 299)).isEmpty();
        assertThat(evaluateAt(evaluator, 300)).map(Alert::getLevel).contains(AlertLevel.WARNING);
        assertThat(evaluateAt(evaluator, 500)).map(Alert::getLevel).contains(AlertLevel.CRITICAL);
    }

    @Test
    void raisesTheHighestLevelReachedGoingDown() {
        ThresholdAlertEvaluator evaluator = builder().below(AlertLevel.WARNING, 100).below(AlertLevel.MAJOR, 10).build();

        assertThat(evaluateAt(evaluator, 101)).isEmpty();
        assertThat(evaluateAt(evaluator, 100)).map(Alert::getLevel).contains(AlertLevel.WARNING);
        assertThat(evaluateAt(evaluator, 10)).map(Alert::getLevel).contains(AlertLevel.MAJOR);
    }

    @Test
    void watchesBothBoundsOfABand() {
        ThresholdAlertEvaluator evaluator = builder().below(AlertLevel.WARNING, 10).above(AlertLevel.MAJOR, 90).build();

        assertThat(evaluateAt(evaluator, 50)).isEmpty();
        assertThat(evaluateAt(evaluator, 5)).map(Alert::getLevel).contains(AlertLevel.WARNING);
        assertThat(evaluateAt(evaluator, 95)).map(Alert::getLevel).contains(AlertLevel.MAJOR);
    }

    @Test
    void theMessageCarriesTheValueItsLabelAndTheThreshold() {
        ThresholdAlertEvaluator evaluator = builder().valueLabel("orders in flight")
                .above(AlertLevel.WARNING, 300).below(AlertLevel.MAJOR, 0.5).build();

        assertThat(evaluateAt(evaluator, 320)).map(Alert::getMessage).contains("320 orders in flight, WARNING from 300");
        assertThat(evaluateAt(evaluator, 0.25)).map(Alert::getMessage).contains("0.25 orders in flight, MAJOR at 0.5 and below");
    }

    @Test
    void aValueThatIsNotANumberRaisesNothing() {
        ThresholdAlertEvaluator evaluator = builder().above(AlertLevel.WARNING, 1).below(AlertLevel.WARNING, 0).build();

        assertThat(evaluateAt(evaluator, Double.NaN)).isEmpty();
    }

    @Test
    void refusesNoThreshold() {
        assertThatThrownBy(() -> builder().build()).isInstanceOf(IllegalArgumentException.class);
    }
}
