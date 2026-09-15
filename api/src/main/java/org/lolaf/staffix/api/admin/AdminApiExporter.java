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
package org.lolaf.staffix.api.admin;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Factory;

/**
 * Exposes a FIX engine's {@link AdminApi} to an external management channel (e.g. JMX).
 *
 * <p>A single exporter is bound to one engine: the engine hands its own {@link AdminApi} to
 * {@link #export(AdminApi)} when it starts, and calls {@link #shutdown(Deadline)} when it stops.
 * Implementations are responsible for publishing/unpublishing whatever management endpoints they
 * provide for that engine.
 */
public interface AdminApiExporter {

    void export(AdminApi adminApi);

    void shutdown(Deadline deadline);

    interface AdminApiExporterFactory<S> extends Factory<AdminApiExporter, S> {
    }
}
