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
package org.lolaf.staffix.sessions.settings.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.IOException;
import java.io.StringWriter;
import java.net.InetAddress;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestFromFixSessionSettingsDocumentTransformer {

    ObjectMapper mapper;
    StringWriter stringWriter;
    FixSessionSettingsDocument.FixSessionSettingsDocumentBuilder settingsBuilder;
    FixSessionSettingsDocument.FixSessionSettingsDocumentBuilder defaultSettingsBuilder;

    @BeforeEach
    void setup() {
        mapper = FixSessionSettingsDocumentMapper.configure(new ObjectMapper(new YAMLFactory()));
        stringWriter = new StringWriter();

        defaultSettingsBuilder = FixSessionSettingsDocument.builder()
                .fixSessionId(FixSessionSettingsDocument.FixSessionId.builder()
                        .fixVersion(FixRegularVersion.VERSION_50_SP2.toString())
                        .senderCompID("senderCompId")
                        .targetCompID("targetCompId")
                        .build())
                .dictionaryId("testId");

        settingsBuilder = FixSessionSettingsDocument.builder()
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .fixSessionId(FixSessionSettingsDocument.FixSessionId.builder()
                        .name("testSession1")
                        .fixVersion(FixRegularVersion.VERSION_44.toString())
                        .build());
    }

    @Test
    void testMergingWorks() throws IOException {
        defaultSettingsBuilder.validationSettings(FixSessionSettingsDocument.ValidationSettings.builder().maxMessageSize(1024).build())
                .allowedAddresses(Collections.singletonList(InetAddress.getByName("localhost")));

        settingsBuilder.fixSessionId(FixSessionSettingsDocument.FixSessionId.builder()
                .name("test")
                .fixVersion(FixRegularVersion.VERSION_44.toString())
                .senderSubID("senderSubId-test")
                .senderLocationID("senderLocId")
                .targetSubID("targetSubId-test")
                .targetLocationID("targetLocId")
                .build());

        FixSessionSettings d = FromFixSessionSettingsDocumentTransformer.toFixSessionSettings(
                FromFixSessionSettingsDocumentTransformer.mergeWithDefault(
                        roundTrip(defaultSettingsBuilder.build()), roundTrip(settingsBuilder.build())));

        assertThat(d.getFixSessionId())
                .isEqualTo(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .name("test")
                        .senderCompID("senderCompId")
                        .targetCompID("targetCompId")
                        .senderSubID("senderSubId-test")
                        .senderLocationID("senderLocId")
                        .targetSubID("targetSubId-test")
                        .targetLocationID("targetLocId")
                        .build()));

        assertThat(d.getDictionaryId()).isEqualTo("testId");

        assertThat(d.getValidationSettings().getMaxMessageSize()).isEqualTo(1024);

        assertThat(d.getAllowedAddresses().get(0).getHostName()).isEqualTo("localhost");
    }

    @Test
    void testCustomSettingsMergingWorks() throws IOException {
        defaultSettingsBuilder.fixApplicationSessionSettings(Map.of("fooSetting", "barSetting"));

        settingsBuilder.fixApplicationSessionSettings(Map.of("session1Setting", "session1Value"));

        FixSessionSettings d = FromFixSessionSettingsDocumentTransformer.toFixSessionSettings(
                FromFixSessionSettingsDocumentTransformer.mergeWithDefault(
                        roundTrip(defaultSettingsBuilder.build()), roundTrip(settingsBuilder.build())));

        Map<FixApplicationSessionSettingDescriptor, String> customSettings = d.getFixApplicationSessionSettings();

        assertThat(customSettings).hasSize(2)
                .containsKey(FixApplicationSessionSettingDescriptor.of("fooSetting"))
                .containsKey(FixApplicationSessionSettingDescriptor.of("session1Setting"));
    }

    @Test
    void customSettingOfTheSessionWinsOverTheDefault() throws IOException {
        defaultSettingsBuilder.fixApplicationSessionSettings(Map.of("shared", "fromDefault"));
        settingsBuilder.fixApplicationSessionSettings(Map.of("shared", "fromSession"));

        FixSessionSettings d = FromFixSessionSettingsDocumentTransformer.toFixSessionSettings(
                FromFixSessionSettingsDocumentTransformer.mergeWithDefault(
                        roundTrip(defaultSettingsBuilder.build()), roundTrip(settingsBuilder.build())));

        assertThat(d.getFixApplicationSessionSettings())
                .containsEntry(FixApplicationSessionSettingDescriptor.of("shared"), "fromSession");
    }

    @Test
    void testCertificateAreParsed() throws IOException {
        settingsBuilder
                .fixSessionId(FixSessionSettingsDocument.FixSessionId.builder()
                        .name("test")
                        .fixVersion(FixRegularVersion.VERSION_50_SP2.toString())
                        .senderCompID("senderCompId")
                        .targetCompID("targetCompId")
                        .build())
                .allowedCertificates(List.of(FixSessionSettingsDocument.Certificate.builder()
                        .filePath("src/test/resources/test.pem")
                        .type("X.509").build()))
                .build();

        FixSessionSettings d = FromFixSessionSettingsDocumentTransformer.toFixSessionSettings(
                roundTrip(settingsBuilder.build()));

        Certificate cert = d.getAllowedCertificates().get(0);

        assertThat(cert.getType()).isEqualTo("X.509");
        assertThat(cert.toString()).contains("Subject: EMAILADDRESS=test@test.com, CN=test, OU=test, O=test, L=test, ST=test, C=CH");
    }

    private FixSessionSettingsDocument roundTrip(FixSessionSettingsDocument settings) throws IOException {
        StringWriter writer = new StringWriter();
        mapper.writeValue(writer, settings);
        return mapper.readValue(writer.getBuffer().toString(), FixSessionSettingsDocument.class);
    }
}
