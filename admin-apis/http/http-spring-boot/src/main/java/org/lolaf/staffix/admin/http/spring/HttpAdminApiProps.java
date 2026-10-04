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
package org.lolaf.staffix.admin.http.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Properties for the HTTP admin API.
 *
 * <p>Bound from {@code staffix.admin-api-http.*}.
 */
@Data
@ConfigurationProperties(prefix = "staffix.admin-api-http")
public class HttpAdminApiProps {
    /**
     * The address to listen on.
     */
    private String bindAddress = "0.0.0.0";

    /**
     * The port to listen on; 0 picks a free one.
     */
    private int port = 8686;

    /**
     * The name of a Spring Boot SSL bundle; serves HTTPS when set, plain HTTP otherwise.
     */
    private String sslBundle;

    /**
     * The bearer token every API request must carry; unset generates a random one at start, known only to the
     * console it is announced to.
     */
    private String apiToken;

    /**
     * A second bearer token that may only read, for monitoring tools; unset serves none.
     */
    private String readOnlyApiToken;

    /**
     * The URL the console reaches this engine at; unset derives it from the host name and the bound port.
     */
    private String advertisedUrl;

    /**
     * The console's base URL the engine announces itself to; unset serves the API without announcing it.
     */
    private String announceUrl;

    /**
     * A console user with the ENGINE role, used only to announce the engine; it grants nothing on this API.
     */
    private String announceUsername;

    /**
     * That user's password.
     */
    private String announcePassword;

    /**
     * How often the engine announces itself again; keep it well under the console's heartbeat timeout.
     */
    private Duration announceInterval = Duration.ofSeconds(30);
}
