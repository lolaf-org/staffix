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
import org.lolaf.staffix.admin.http.AdminApiHandler;
import org.lolaf.staffix.admin.http.HttpProblemException;
import org.lolaf.staffix.admin.http.SessionSettingsDocuments;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
public final class AddSessionRoute extends Route {

    private final AdminApi adminApi;

    public AddSessionRoute(AdminApi adminApi) {
        super("POST", "v1/session-settings-stores/{storeId}/sessions");
        this.adminApi = adminApi;
    }

    @Override
    public void handle(HttpExchange exchange, List<String> path) throws IOException {
        String storeId = param(path, "storeId");
        if (!adminApi.getFixSessionsSettingsStoresInstanceIds().contains(storeId)) {
            throw new HttpProblemException(404, "No session settings store " + storeId);
        }
        FixSessionSettings settings = SessionSettingsDocuments.read(exchange.getRequestBody(), null);
        FixSessionId fixSessionId = settings.getFixSessionId();
        log.info("Admin API: add session {} to session settings store {}", fixSessionId.getQualifiedName(), storeId);
        adminApi.addFixSessionSettings(storeId, settings);
        exchange.getResponseHeaders().set("Location", exchange.getHttpContext().getPath() + AdminApiHandler.API_VERSION + "/sessions/"
                + encode(fixSessionId.getGroup()) + "/" + encode(fixSessionId.getName()));
        exchange.sendResponseHeaders(201, -1);
    }

    private static String encode(String pathSegment) {
        return URLEncoder.encode(pathSegment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
