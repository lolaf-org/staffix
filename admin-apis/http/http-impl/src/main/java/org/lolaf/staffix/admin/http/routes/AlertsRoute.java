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

import org.lolaf.staffix.admin.http.ProbesDocuments;
import org.lolaf.staffix.admin.http.dto.ActiveAlert;
import org.lolaf.staffix.admin.http.dto.EngineAlerts;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.FixInitiatorTargets;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.SessionProbeState;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AlertsRoute extends JsonRoute {

    private final AdminApi adminApi;
    private final ProbesDocuments probes;

    public AlertsRoute(AdminApi adminApi, ProbesDocuments probes, JsonResponseBuffer json) {
        super("v1/alerts", json);
        this.adminApi = adminApi;
        this.probes = probes;
    }

    /**
     * The version is read before the alerts, so a transition racing this call moves it past what the console holds.
     */
    @Override
    Object body(List<String> path) {
        long alertsVersion = probes.getAlertsVersion();
        Map<FixSessionId, String> mainNames = new HashMap<>();
        for (FixInitiatorTargets initiator : adminApi.getInitiatorsTargets()) {
            mainNames.put(initiator.getActiveFixSessionId(), initiator.getMainTarget().getFixSessionId().getName());
        }
        List<ActiveAlert> alerts = new ArrayList<>();
        for (SessionProbeState active : adminApi.getActiveAlerts()) {
            FixSessionId fixSessionId = active.getFixSessionId();
            ProbeState state = active.getProbeState();
            Alert alert = state.getAlert().orElseThrow();
            alerts.add(new ActiveAlert(fixSessionId.getGroup(), mainNames.getOrDefault(fixSessionId, fixSessionId.getName()),
                    fixSessionId.getName(), state.getProbeName(), alert.getLevel(), alert.getMessage(),
                    state.getSince().orElse(null), active.getRunbookUrl().map(Object::toString).orElse(null)));
        }
        return new EngineAlerts(adminApi.getInstanceId(), alertsVersion, alerts);
    }
}
