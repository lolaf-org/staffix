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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.admin.http.SessionsDocument;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSession;

import java.io.IOException;
import java.util.List;

/**
 * Shared by {@code sessions} and {@code status}, so the status poll reuses the document instead of building it.
 */
@RequiredArgsConstructor
public final class SessionsDocumentCache {

    private final AdminApi adminApi;
    private final ObjectMapper mapper;
    private SessionsDocument document;

    public synchronized SessionsDocument get() throws IOException {
        List<FixSession> managed = adminApi.getManagedFixSessions();
        if (document == null || !document.describes(managed)) {
            document = SessionsDocument.of(adminApi, managed, mapper);
        }
        return document;
    }
}
