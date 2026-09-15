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
package org.lolaf.staffix.api.fields;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The edges of the user defined range, which is the whole of {@link FixField#isUserDefined(int)}: a range open at the
 * top - {@code code >= 5000}, as this used to be - takes the standard tags the Global Technical Committee has
 * allocated from 40000 up for user defined ones, and there are 3127 of those in FIX Latest.
 */
class FixFieldUserDefinedTest {

    @ParameterizedTest
    @ValueSource(ints = {5000, 5001, 9999, 10000, 19999, 20000, 39998, 39999})
    void testTagsOwnedByUsersAreUserDefined(int code) {
        assertThat(FixField.isUserDefined(code))
                .as("tag %s is in the user defined range", code)
                .isTrue();
    }

    @ParameterizedTest
    // below the range: standard tags, low ones still being allocated - 3106 arrived with EP300. Above it: the range
    // the Global Technical Committee took for itself in EP161, 42087 being NoUnderlyingProtectionTermObligations and
    // 50000 BatchID
    @ValueSource(ints = {1, 35, 4999, 3106, 40000, 42087, 43123, 50000, Integer.MAX_VALUE})
    void testTagsOwnedByTheStandardAreNotUserDefined(int code) {
        assertThat(FixField.isUserDefined(code))
                .as("tag %s belongs to the standard", code)
                .isFalse();
    }

    @Test
    void testTheFieldItselfAnswersTheSameAsItsCode() {
        assertThat(FixField.of(5001, FieldType.INT, FieldLocation.BODY).isUserDefined()).isTrue();
        assertThat(FixField.of(42087, FieldType.INT, FieldLocation.BODY).isUserDefined()).isFalse();
    }
}
