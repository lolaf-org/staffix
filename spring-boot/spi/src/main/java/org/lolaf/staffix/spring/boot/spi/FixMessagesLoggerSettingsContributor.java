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
package org.lolaf.staffix.spring.boot.spi;

import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;

import java.util.Map;

/**
 * SPI implemented by per-impl Spring Boot modules (e.g. {@code staffix-messages-logger-slf4j-spring-boot}).
 *
 * <p>Each contributor mutates a shared {@code registry} (key -> {@link FixMessagesLoggerSettings}).
 * Simple-logger contributors only add entries and should fail on collisions. Wrapper contributors
 * (e.g. async) look up the wrapped target by key and replace it in-place — they intentionally
 * overwrite an entry contributed by an earlier-order contributor.
 *
 * <p>Wrappers must override {@link #order()} so they run after the simple loggers they reference.
 */
public interface FixMessagesLoggerSettingsContributor {

    /**
     * Lower runs first. Simple loggers should keep the default; wrappers (e.g. async) should
     * return a higher value so their {@code wraps:} keys can resolve against already-registered
     * contributions.
     */
    default int order() {
        return 0;
    }

    /**
     * Contribute entries by mutating {@code registry} directly. The map is mutable, ordered,
     * never {@code null}, and accumulates across all contributors.
     */
    void contribute(Map<String, FixMessagesLoggerSettings> registry);
}
