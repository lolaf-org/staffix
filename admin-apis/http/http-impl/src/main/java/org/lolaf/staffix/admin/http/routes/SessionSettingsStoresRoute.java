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

import org.lolaf.staffix.admin.http.dto.SessionSettingsStore;
import org.lolaf.staffix.api.admin.AdminApi;

import java.util.List;
import java.util.stream.Collectors;

public final class SessionSettingsStoresRoute extends JsonRoute {

    private final AdminApi adminApi;

    public SessionSettingsStoresRoute(AdminApi adminApi, JsonResponseBuffer json) {
        super("v1/session-settings-stores", json);
        this.adminApi = adminApi;
    }

    @Override
    Object body(List<String> path) {
        return adminApi.getFixSessionsSettingsStoresInstanceIds().stream()
                .map(id -> new SessionSettingsStore(id, adminApi.isFixSessionsSettingsStorePersistent(id)))
                .collect(Collectors.toList());
    }
}
