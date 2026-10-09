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
package org.lolaf.staffix.api.monitoring;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * The values of {@link FixMonitoringAttributes#FIX_MESSAGE_DIRECTION}.
 */
@Getter
@RequiredArgsConstructor
public enum FixMessageDirection {

    /**
     * A message received.
     */
    IN("i"),
    /**
     * A message sent.
     */
    OUT("o");

    /**
     * As written to the monitoring backends, e.g. {@code i}, short since every message log record carries it.
     */
    private final String value;

    /**
     * Empty for a value staffix does not write, so a tool reading a backend can skip what it does not know.
     */
    public static Optional<FixMessageDirection> ofValue(String value) {
        for (FixMessageDirection direction : values()) {
            if (direction.value.equals(value)) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }
}
