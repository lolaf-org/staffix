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

import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;

import java.util.Map;

/**
 * SPI implemented by per-impl Spring Boot modules (e.g. {@code staffix-alerts-publisher-slf4j-spring-boot}).
 *
 * <p>Each contributor adds entries to a shared {@code registry} (key -> {@link FixAlertsPublisherSettings}) and
 * should fail on collisions. A wrapper (e.g. a future demux) replaces the entries it wraps, and overrides
 * {@link #order()} to run after them.
 */
public interface FixAlertsPublisherSettingsContributor {

    /**
     * Lower runs first.
     */
    default int order() {
        return 0;
    }

    /**
     * Contribute entries by mutating {@code registry} directly. The map is mutable, ordered,
     * never {@code null}, and accumulates across all contributors.
     */
    void contribute(Map<String, FixAlertsPublisherSettings> registry);
}
