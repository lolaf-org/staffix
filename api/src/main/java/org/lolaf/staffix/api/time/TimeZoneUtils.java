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

import lombok.experimental.UtilityClass;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Resolves the session-schedule times a configuration gives in a named zone.
 *
 * <p>A session's start and end are local times in a venue's zone, so the instant they mean moves with that
 * zone's daylight saving - the arithmetic cannot be done once at startup.
 */
@UtilityClass
public class TimeZoneUtils {

    private static final int MILLIS_IN_AN_HOUR = 3_600_000;
    private static volatile ZoneOffset cachedZoneOffset;
    private static volatile long lastUpdate;
    private static Clock clock = Clock.systemUTC();

    public static ZoneOffset getZoneOffset(long nowInMillis) {
        // Refresh every hour
        long nowInMillisStripedToHours = getNowInMillisStripedToHours(nowInMillis);
        if (nowInMillisStripedToHours - lastUpdate >= MILLIS_IN_AN_HOUR) {
            cachedZoneOffset = ZonedDateTime.now(clock).getOffset();
            lastUpdate = nowInMillisStripedToHours;
        }
        return cachedZoneOffset;
    }

    public static int getOffsetSeconds(long nowInMillis) {
        return getZoneOffset(nowInMillis).getTotalSeconds();
    }

    private static long getNowInMillisStripedToHours(long nowInMillis) {
        return (nowInMillis / MILLIS_IN_AN_HOUR) * MILLIS_IN_AN_HOUR;
    }

    static void setClock(Clock clock) {
        TimeZoneUtils.clock = clock;
    }

}
