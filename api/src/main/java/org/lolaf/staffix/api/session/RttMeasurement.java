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
package org.lolaf.staffix.api.session;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import org.lolaf.staffix.api.time.UTCTime;

import java.time.Duration;

/**
 * Snapshot of the continuous RTT measurement state for a FIX session, exposed via
 * {@link FixSession#getRttMeasurement()}.
 *
 * @see FixSessionSettings.RttMeasurementSettings
 */
@Getter
@ToString
@EqualsAndHashCode
@RequiredArgsConstructor
public class RttMeasurement {

    /**
     * EMA-smoothed estimate of the network round-trip time to the remote FIX session.
     * <p>
     * Updated from each successful TestRequest/Heartbeat exchange where the round trip is
     * measured locally using a monotonic clock.
     */
    private final Duration roundTripTime;

    /**
     * EMA-smoothed estimate of the remote-vs-local wall-clock offset.
     * <p>
     * Computed NTP-style from the remote SendingTime (FIX tag 52) of TestRequest responses and the
     * local wall-clock times at probe send and response receipt, assuming symmetric one-way latency.
     * A positive value means the remote clock is ahead of the local clock.
     */
    private final Duration clockOffset;

    /**
     * Local wall-clock instant at which the most recent RTT sample was recorded.
     */
    private final UTCTime sampleTime;
}
