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
package org.lolaf.staffix.api.time;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;


class TestTimeZoneUtils {

    @Test
    void testOffsetSecondsAreRefreshedEveryHourAndManageDST() {
        ZoneId zid = ZoneId.of("Europe/Zurich");

        java.time.Clock winterTime = java.time.Clock.fixed(Instant.ofEpochSecond(LocalDateTime.of(2025, 1, 1, 0, 0).toEpochSecond(ZoneOffset.ofHoursMinutes(0, 0))), zid);
        TimeZoneUtils.setClock(winterTime);

        Assertions.assertThat(TimeZoneUtils.getOffsetSeconds(System.currentTimeMillis())).isEqualTo(3600);

        java.time.Clock summerTime = java.time.Clock.fixed(Instant.ofEpochSecond(LocalDateTime.of(2025, 7, 1, 0, 0).toEpochSecond(ZoneOffset.ofHoursMinutes(0, 0))), zid);
        TimeZoneUtils.setClock(summerTime);

        Assertions.assertThat(TimeZoneUtils.getOffsetSeconds(System.currentTimeMillis() + 3600000)).isEqualTo(3600 * 2);

        TimeZoneUtils.setClock(java.time.Clock.systemUTC());
    }
}
