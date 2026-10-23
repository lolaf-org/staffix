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

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Value;

import java.time.Instant;
import java.util.Optional;

/**
 * Where a probe stood at its last evaluation. Unlike {@link AlertEvent}, it follows every change of message.
 */
@Value
@Builder
public class ProbeState {

    @NonNull
    String probeName;
    @Getter(AccessLevel.NONE)
    Alert alert;
    @Getter(AccessLevel.NONE)
    Instant since;
    @Getter(AccessLevel.NONE)
    Instant lastEvaluation;
    /**
     * Whether an operator cleared it from the admin; only ever true on the clear's
     * {@link ProbesListener#onAlertTransition transition}.
     */
    boolean clearedManually;
    /**
     * Whether the probe is not evaluated while its session is not logged in, as
     * {@link Probe#isOnlyWhileLoggedIn()} asks; it is then normal, and its last evaluation is from before the logout.
     */
    boolean suspended;

    /**
     * The ongoing alert, empty while the probe is normal.
     */
    public Optional<Alert> getAlert() {
        return Optional.ofNullable(alert);
    }

    /**
     * When the ongoing alert was first raised, kept across level changes; empty while the probe is normal.
     */
    public Optional<Instant> getSince() {
        return Optional.ofNullable(since);
    }

    /**
     * Empty for a probe {@link #isSuspended() suspended} before its first evaluation.
     */
    public Optional<Instant> getLastEvaluation() {
        return Optional.ofNullable(lastEvaluation);
    }
}
