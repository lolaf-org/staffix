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
package org.lolaf.staffix.stores.sessions.file;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.CancelOnDisconnectType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TestToYamlFixSessionSettingsTransformer {

    ObjectMapper mapper;
    StringWriter stringWriter;
    YamlFixSessionSettings.YamlFixSessionSettingsBuilder settingsBuilder;
    YamlFixSessionSettings.YamlFixSessionSettingsBuilder defaultSettingsBuilder;

    @BeforeEach
    void setup() {
        mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        mapper.configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL); // null objects are not serialized
        stringWriter = new StringWriter();

        defaultSettingsBuilder = YamlFixSessionSettings.builder()
                .fixSessionId(YamlFixSessionSettings.FixSessionId.builder()
                        .id("default")
                        .fixVersion(FixRegularVersion.VERSION_50_SP2.toString())
                        .senderCompID("senderCompId")
                        .targetCompID("targetCompId")
                        .build())
                .dictionaryId("testId");

        settingsBuilder = YamlFixSessionSettings.builder()
                .fixSessionId(YamlFixSessionSettings.FixSessionId.builder()
                        .id("testSession1")
                        .fixVersion(FixRegularVersion.VERSION_44.toString())
                        .build());
    }

    /**
     * Reads an expected yaml, substituting the {@code $expectedTz} placeholder with the machine default time zone so
     * that settings falling back on {@link TimeZone#getDefault()} compare equal whatever the machine time zone is.
     */
    private YamlFixSessionSettings readExpected(String testName) throws IOException {
        String resource = "test-yamls/" + testName + ".yaml";
        try (InputStream in = this.getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("missing test resource %s", resource).isNotNull();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            String yaml = buffer.toString(StandardCharsets.UTF_8)
                    .replace("$expectedTz", TimeZone.getDefault().getID());
            return mapper.readValue(yaml, YamlFixSessionSettings.class);
        }
    }

    @Test
    void testMinimalFixSessionSettingsWorks() throws IOException {
        FixSessionSettings testFullSettings = FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id("testMinimalFixSessionSettingsWorks")
                        .group("test-group")
                        .senderCompID("senderCompId")
                        .senderSubID("senderSubId")
                        .senderLocationID("senderLocId")
                        .targetCompID("targetCompId")
                        .targetSubID("targetSubId")
                        .targetLocationID("targetLocId")
                        .build()))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .build();

        YamlFixSessionSettings yamlFixSessionSettings = ToYamlFixSessionSettingsTransformer.toYamlFixSessionSettings(testFullSettings);
        mapper.writeValue(stringWriter, yamlFixSessionSettings);

        YamlFixSessionSettings expected = readExpected("testMinimalFixSessionSettingsWorks");

        YamlFixSessionSettings actual = mapper.readValue(stringWriter.getBuffer().toString(), YamlFixSessionSettings.class);

        assertThat(actual).isEqualTo(expected);
    }

    /**
     * The non-stop days round-trip on their own rather than as part of the full settings, whose trading windows cover
     * the days this claims: a day either keeps windows or runs non-stop. Two days with different reset times and
     * different roles, since that is what a per-day list buys over a single setting.
     */
    @Test
    void testNonStopScheduleFixSessionSettingsWorks() throws IOException {
        FixSessionSettings testNonStopSettings = FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id("testNonStopScheduleFixSessionSettingsWorks")
                        .group("test-group")
                        .senderCompID("senderCompId")
                        .senderSubID("senderSubId")
                        .senderLocationID("senderLocId")
                        .targetCompID("targetCompId")
                        .targetSubID("targetSubId")
                        .targetLocationID("targetLocId")
                        .build()))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("Europe/Zurich"))
                        .nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(DayOfWeek.MONDAY)
                                .sequenceResetTime(LocalTime.of(17, 0))
                                .initiatesReset(true)
                                .build())
                        .nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(DayOfWeek.TUESDAY)
                                .sequenceResetTime(LocalTime.of(18, 30))
                                .initiatesReset(false)
                                .build())
                        // a day that never closes and never rolls, whose two absent fields have to survive both
                        // directions rather than coming back as some default
                        .nonStopSchedule(FixSessionSettings.SessionScheduleSettings.NonStopScheduleEntry.builder()
                                .dayOfWeek(DayOfWeek.WEDNESDAY)
                                .build())
                        .build())
                .build();

        YamlFixSessionSettings yamlFixSessionSettings = ToYamlFixSessionSettingsTransformer.toYamlFixSessionSettings(testNonStopSettings);
        mapper.writeValue(stringWriter, yamlFixSessionSettings);

        YamlFixSessionSettings expected = readExpected("testNonStopScheduleFixSessionSettingsWorks");
        YamlFixSessionSettings actual = mapper.readValue(stringWriter.getBuffer().toString(), YamlFixSessionSettings.class);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void testFullFixSessionSettingsWorks() throws IOException {
        FixSessionSettings testFullSettings = FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id("testFullFixSessionSettingsWorks")
                        .group("test-group")
                        .senderCompID("senderCompId")
                        .senderSubID("senderSubId")
                        .senderLocationID("senderLocId")
                        .targetCompID("targetCompId")
                        .targetSubID("targetSubId")
                        .targetLocationID("targetLocId")
                        .build()))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .sessionScheduleSettings(FixSessionSettings.SessionScheduleSettings.builder()
                        .timeZone(TimeZone.getTimeZone("Europe/Zurich"))
                        .outsideSessionTimePreTriggerDelay(Duration.ofSeconds(11))
                        .withinSessionTimeCheckInterval(Duration.ofSeconds(12))
                        .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                                .startTime(LocalTime.of(10, 10, 10, 99999999))
                                .endTime(LocalTime.of(10, 10, 11))
                                .startDay(DayOfWeek.MONDAY)
                                .endDay(DayOfWeek.TUESDAY)
                                .build())
                        .sessionSchedule(FixSessionSettings.SessionScheduleSettings.ScheduleEntry.builder()
                                .startTime(LocalTime.of(11, 10, 10, 99999999))
                                .endTime(LocalTime.of(11, 10, 11))
                                .startDay(DayOfWeek.WEDNESDAY)
                                .endDay(DayOfWeek.FRIDAY)
                                .build())
                        .build())
                .cancelOnDisconnectSettings(FixSessionSettings.CancelOnDisconnectSettings.builder()
                        .enabled(true)
                        .cancelOnDisconnectType(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT)
                        .cancelOnDisconnectTypeFieldCode(1234)
                        .cancelOnDisconnectTypeFieldCodes(new TreeMap<>(Map.of(CancelOnDisconnectType.CANCEL_ON_DISCONNECT_OR_LOGOUT, '1',
                                CancelOnDisconnectType.CANCEL_ON_DISCONNECT_ONLY, '2')))
                        .codTimeoutWindowFieldCode(4321)
                        .codTimeoutWindow(Duration.ofMillis(1000))
                        .codTimeoutWindowScale(TimeUnit.MILLISECONDS)
                        .build())
                .validationSettings(FixSessionSettings.ValidationSettings.builder()
                        .maxMessageSize(100)
                        .requiredPeerMaxMessageSize(200)
                        .maxSendingTime(Duration.ofSeconds(10)).build())
                .allowedAddress(InetAddress.getByName("127.0.0.1"))
                .build();

        YamlFixSessionSettings yamlFixSessionSettings = ToYamlFixSessionSettingsTransformer.toYamlFixSessionSettings(testFullSettings);
        mapper.writeValue(stringWriter, yamlFixSessionSettings);

        YamlFixSessionSettings expected = readExpected("testFullFixSessionSettingsWorks");

        YamlFixSessionSettings actual = mapper.readValue(stringWriter.getBuffer().toString(), YamlFixSessionSettings.class);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void pluginsRoundTrip() throws IOException {
        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id("rt")
                        .senderCompID("senderCompId")
                        .targetCompID("targetCompId")
                        .build()))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .fixSessionPluginsInstanceId(TestPlugin.class, "plugin-instance")
                .build();

        YamlFixSessionSettings yaml = ToYamlFixSessionSettingsTransformer.toYamlFixSessionSettings(settings);
        mapper.writeValue(stringWriter, yaml);
        YamlFixSessionSettings readBack = mapper.readValue(stringWriter.getBuffer().toString(), YamlFixSessionSettings.class);

        assertThat(readBack.getFixSessionPluginsInstanceIds())
                .containsEntry(TestPlugin.class.getName(), "plugin-instance");

        FixSessionSettings back = FromYamlFixSessionSettingsTransformer.toFixSessionSettings(readBack);

        assertThat(back.getFixSessionPluginsInstanceIds()).containsEntry(TestPlugin.class, "plugin-instance");
    }

    @Test
    void recoveryBoundsRoundTrip() throws IOException {
        // the three bounds a recovery is subject to, all carrying a 0 that has to survive the round trip: for the
        // resend and the out of sequence queue it means "no limit", and for the outgoing hold "hold nothing", so
        // dropping it on the way through would silently change what the session does rather than just a number
        FixSessionSettings settings = FixSessionSettings.builder()
                .fixSessionId(FixSessionId.of(FixRegularVersion.VERSION_44, FixSessionId.FixSessionIdBuilder.builder()
                        .id("recovery-bounds")
                        .senderCompID("senderCompId")
                        .targetCompID("targetCompId")
                        .build()))
                .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
                .maxOutOfSequenceMessagesQueued(0)
                .maxOutgoingMessagesHeldDuringRecovery(25)
                .maxMessagesResentPerRequest(0)
                .build();

        YamlFixSessionSettings yaml = ToYamlFixSessionSettingsTransformer.toYamlFixSessionSettings(settings);
        mapper.writeValue(stringWriter, yaml);
        YamlFixSessionSettings readBack = mapper.readValue(stringWriter.getBuffer().toString(), YamlFixSessionSettings.class);

        assertThat(readBack.getMaxOutOfSequenceMessagesQueued()).isZero();
        assertThat(readBack.getMaxOutgoingMessagesHeldDuringRecovery()).isEqualTo(25);
        assertThat(readBack.getMaxMessagesResentPerRequest()).isZero();

        FixSessionSettings back = FromYamlFixSessionSettingsTransformer.toFixSessionSettings(readBack);

        assertThat(back.getMaxOutOfSequenceMessagesQueued()).isZero();
        assertThat(back.getMaxOutgoingMessagesHeldDuringRecovery()).isEqualTo(25);
        assertThat(back.getMaxMessagesResentPerRequest()).isZero();
    }

    static class TestPlugin implements FixSessionsPlugin<PluginContext> {

        @Override
        public String getInstanceId() {
            return "test-plugin";
        }

        @Override
        public Optional<FixSessionPlugin<PluginContext, Void>> onSessionCreated(String fixInstanceId, FixSession fixSession,
                                                                                Collection<MessageType> incomingMessageTypes,
                                                                                Collection<MessageType> outgoingMessageTypes) {
            return Optional.empty();
        }
    }
}
