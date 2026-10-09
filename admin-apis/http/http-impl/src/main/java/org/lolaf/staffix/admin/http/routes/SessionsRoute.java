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
import org.lolaf.staffix.admin.http.SessionsDocument;

import java.io.IOException;
import java.util.List;

public final class SessionsRoute extends Route {

    private final SessionsDocumentCache documents;

    public SessionsRoute(SessionsDocumentCache documents) {
        super("GET", "v1/sessions");
        this.documents = documents;
    }

    @Override
    public void handle(HttpExchange exchange, List<String> path) throws IOException {
        SessionsDocument document = documents.get();
        if (!notModified(exchange, document.version)) {
            send(exchange, 200, "application/json", document.json);
        }
    }
}
