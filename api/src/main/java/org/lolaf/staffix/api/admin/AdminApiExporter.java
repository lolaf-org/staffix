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

    /**
     * Publishes the engine's admin API, called once the engine has started.
     */
    void export(AdminApi adminApi);

    /**
     * Withdraws what {@link #export(AdminApi)} published, finishing by the deadline. Called first when the engine
     * stops, so no admin operation reaches a session being torn down.
     */
    void shutdown(Deadline deadline);

    /**
     * The service provider that builds an admin API exporter from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface AdminApiExporterFactory<S> extends Factory<AdminApiExporter, S> {
    }
}
