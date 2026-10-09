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
package org.lolaf.staffix.admin.http.dto;

import lombok.Value;

import java.util.List;

/**
 * The engine's running sessions as the console draws them, fetched again only when {@link EngineStatus} reports
 * another version.
 */
@Value
public class EngineSessions {
    String engineId;
    /**
     * The SHA-256 of {@link #sessions} as JSON, so equal content has the same version across engine restarts.
     */
    String version;
    List<SessionDescription> sessions;
}
