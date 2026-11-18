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

import com.sun.net.httpserver.HttpExchange;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.admin.http.HttpProblemException;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.alerting.ProbeId;
import org.lolaf.staffix.api.alerting.ProbeScope;

import java.io.IOException;
import java.util.List;

/**
 * Answers 404 for an unknown component or probe, and 409 when the alert cannot be cleared by hand.
 */
@Slf4j
public final class ComponentClearAlertRoute extends Route {

    private final AdminApi adminApi;
    private final ComponentLookup components;

    public ComponentClearAlertRoute(AdminApi adminApi, ComponentLookup components) {
        super("POST", "v1/engine/{component}/probes/{probe}/clear");
        this.adminApi = adminApi;
        this.components = components;
    }

    @Override
    public void handle(HttpExchange exchange, List<String> path) throws IOException {
        ProbeScope scope = components.scope(param(path, "component"));
        String probe = param(path, "probe");
        if (adminApi.getProbeDefinitions(scope).stream().noneMatch(definition -> definition.getName().equals(probe))) {
            throw new HttpProblemException(404, "Component " + scope.getName() + " has no probe named " + probe);
        }
        log.info("Admin API: clear alert {} of component {}", probe, scope.getName());
        if (!adminApi.clearAlert(ProbeId.of(scope, probe))) {
            throw new HttpProblemException(409, "Probe " + probe + " is not in alert, or its alert cannot be cleared by hand");
        }
        sendNoContent(exchange);
    }
}
