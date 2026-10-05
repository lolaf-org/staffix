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
package org.lolaf.staffix.admin.http.routes;

import org.lolaf.staffix.admin.http.dto.SessionMeters;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;

import java.util.List;
import java.util.stream.Collectors;

public final class MetersRoute extends JsonRoute {

    private final AdminApi adminApi;

    public MetersRoute(AdminApi adminApi, JsonResponseBuffer json) {
        super("v1/meters", json);
        this.adminApi = adminApi;
    }

    @Override
    Object body(List<String> path) {
        return adminApi.getManagedFixSessions().stream()
                .map(session -> new SessionMeters(session.getFixSessionId().getGroup(), session.getFixSessionId().getName(),
                        session.getPluginContext(FixSessionsMonitoringContext.class)
                                .map(FixSessionsMonitoringContext::getMeterDescriptors)
                                .orElse(List.of())))
                .collect(Collectors.toList());
    }
}
