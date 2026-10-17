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
package org.lolaf.staffix.buildtools.schema;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.convert.DurationStyle;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class TestValueSchemas {

    @ParameterizedTest
    @ValueSource(strings = {"PT10S", "PT1H30M", "P2DT3H", "PT0.0000015S", "-PT5M", "PT2M", "PT0S"})
    void theSettingsPatternAcceptsWhatADurationWrites(String duration) {
        assertThat(Duration.parse(duration).toString()).matches(ValueSchemas.ISO_DURATION);
        assertThat(duration).matches(ValueSchemas.ISO_DURATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"P1Y", "P3W", "10s", "P", "PT", "P1DT"})
    void theSettingsPatternRefusesWhatADurationCannotParse(String duration) {
        assertThat(duration).doesNotMatch(ValueSchemas.ISO_DURATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT10S", "PT1H30M", "P2DT3H", "PT0.0000015S", "-PT5M", "pt10s", "10s", "500ms", "500", "1D",
            "3us", "2ns", "P1Y", "P3W", "10x", "P", "PT", "P1DT", "1.5s", ""})
    void theSpringPatternAcceptsExactlyWhatSpringBootBinds(String duration) {
        assertThat(Pattern.matches(ValueSchemas.SPRING_DURATION, duration))
                .as(duration)
                .isEqualTo(springBinds(duration));
    }

    @ParameterizedTest
    @ValueSource(strings = {"09:30", "17:00:05", "10:10:10.099999999", "00:00"})
    void theTimePatternAcceptsWhatALocalTimeWrites(String time) {
        assertThat(LocalTime.parse(time).toString()).matches(ValueSchemas.LOCAL_TIME);
    }

    private static boolean springBinds(String duration) {
        try {
            DurationStyle.detectAndParse(duration);
            return true;
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return false;
        }
    }
}
