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
package org.lolaf.staffix.admin.http;

import lombok.Value;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/**
 * What {@code GET /engines/{instanceId}/} answers, outside any API version so a client finds the versions served.
 */
@Value
public class EngineInfo {

    static final String STAFFIX_VERSION = readStaffixVersion();

    String engineId;
    /**
     * Null when the engine's jar does not say.
     */
    String staffixVersion;
    List<String> apiVersions;

    /**
     * Read from the engine's jar rather than this one's, as this connector may be built apart from the engine.
     */
    private static String readStaffixVersion() {
        try (InputStream in = EngineInfo.class.getClassLoader().getResourceAsStream("engine-version.txt")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("engineVersion");
        } catch (IOException e) {
            return null;
        }
    }
}
