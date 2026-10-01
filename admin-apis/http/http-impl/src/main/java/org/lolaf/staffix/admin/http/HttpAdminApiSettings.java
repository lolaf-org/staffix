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

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.lolaf.staffix.api.admin.AdminApiExporterSettings;

import javax.net.ssl.SSLContext;
import java.time.Duration;

/**
 * Where the engine serves its admin API, and the staffix admin console it announces itself to.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class HttpAdminApiSettings extends AdminApiExporterSettings {

    @Builder.Default
    private final String bindAddress = "0.0.0.0";

    /**
     * The port to listen on; 0 picks a free one.
     */
    @Builder.Default
    private final int port = 8686;

    /**
     * Serves HTTPS when set, plain HTTP otherwise.
     */
    private final SSLContext sslContext;

    /**
     * The bearer token every API request must carry; null generates a random one at export, known only to the
     * console it is announced to.
     */
    private final String apiToken;

    /**
     * The URL the console reaches this engine at; null derives it from the host name and the bound port, which
     * is wrong behind a proxy or a NAT.
     */
    private final String advertisedUrl;

    /**
     * The console's base URL the engine announces itself to; null serves the API without announcing it.
     */
    private final String announceUrl;

    /**
     * An admin console user with the ENGINE role, used only to announce the engine; it grants nothing on this API.
     */
    private final String announceUsername;

    private final String announcePassword;

    /**
     * How often the engine announces itself again, so a restarted console finds it; keep it well under the
     * console's heartbeat timeout.
     */
    @Builder.Default
    private final Duration announceInterval = Duration.ofSeconds(30);
}
