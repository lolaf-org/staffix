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

import org.lolaf.staffix.api.admin.AdminApiExporterSettings;

import java.util.function.Consumer;

/**
 * SPI implemented by per-impl Spring Boot modules (e.g. {@code staffix-admin-api-jmx-spring-boot}).
 *
 * <p>Unlike the Map-based contributors for stores/loggers/sessions, the FIX engine accepts at most
 * one {@link AdminApiExporterSettings}. Each contributor must invoke {@code sink} at most once (or
 * not at all if disabled). The starter aggregates the calls across all contributors and fails the
 * engine build if more than one contribution is collected.
 */
public interface AdminApiExporterSettingsContributor {

    /**
     * Lower runs first. Default is fine for most contributors.
     */
    default int order() {
        return 0;
    }

    /**
     * Contribute at most one {@link AdminApiExporterSettings} via {@code sink}. No-op when disabled.
     */
    void contribute(Consumer<AdminApiExporterSettings> sink);
}
