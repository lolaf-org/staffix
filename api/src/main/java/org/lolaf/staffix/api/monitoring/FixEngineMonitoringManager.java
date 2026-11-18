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

/**
 * A monitoring session plugin that also publishes the meters of the engine's own components, such as its high
 * availability, through the same backend: one exporter for both. The engine finds it among its session plugins, by
 * instance id.
 */
public interface FixEngineMonitoringManager {

    /**
     * The engine's meters, once the plugin is started; the same context for the same engine.
     */
    FixEngineMonitoringContext engineContext(String fixEngineId);
}
