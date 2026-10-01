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

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsJsonTest {

    private static final FixSessionSettings SETTINGS = FixSessionSettings.builder()
            .fixSessionId(FixSessionId.of("trading", FixRegularVersion.VERSION_44, "US", "ALPHA"))
            .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
            .fixApplicationSessionSetting(FixApplicationSessionSettingDescriptor.of("logon.password"), "hunter2")
            .fixApplicationSessionSetting(FixApplicationSessionSettingDescriptor.of("apiToken"), "abc")
            .fixApplicationSessionSetting(FixApplicationSessionSettingDescriptor.of("account"), "ACC-1")
            .allowedAddress(InetAddress.getLoopbackAddress())
            .build();

    @Test
    void readableValuesForIdsDurationsAndAddresses() {
        JsonNode json = SettingsJson.of(SETTINGS);

        assertThat(json.get("fixSessionId").asText()).isEqualTo(SETTINGS.getFixSessionId().toString());
        assertThat(json.get("fixSessionType").asText()).isEqualTo("ACCEPTOR");
        assertThat(json.get("logInOrOutResponseTimeout").asText()).isEqualTo("PT10S");
        assertThat(json.get("allowedAddresses").get(0).asText()).isEqualTo("127.0.0.1");
        assertThat(json.at("/sessionScheduleSettings/timeZone").isTextual()).isTrue();
    }

    @Test
    void applicationSettingsThatLookSecretAreMasked() {
        JsonNode application = SettingsJson.of(SETTINGS).get("fixApplicationSessionSettings");

        assertThat(application.get("logon.password").asText()).isEqualTo(SettingsJson.MASK);
        assertThat(application.get("apiToken").asText()).isEqualTo(SettingsJson.MASK);
        assertThat(application.get("account").asText()).isEqualTo("ACC-1");
    }
}
