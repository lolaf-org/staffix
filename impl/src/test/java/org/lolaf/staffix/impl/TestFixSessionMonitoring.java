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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.fix44.msg.MessageTypes;
import org.lolaf.staffix.tests.TestingFixSessionMonitoringManagerSettings;
import org.mockito.Mockito;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class TestFixSessionMonitoring extends AbstractFixTests {

    FixSessionsMonitoringManager initiatorFixSessionMonitoringManager;
    FixSessionsMonitoringManager acceptorFixSessionMonitoringManager;
    FixSessionPlugin<FixSessionsMonitoringContext, Void> initiatorFixSessionEventsListener;
    FixSessionPlugin<FixSessionsMonitoringContext, Void> acceptorFixSessionEventsListener;

    @Override
    FixSessionSettings.FixSessionSettingsBuilder getAcceptorFixSessionSettings() {
        return super.getAcceptorFixSessionSettings().fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, InstanceProvider.DEFAULT_INSTANCE_ID);
    }

    @Override
    FixSessionSettings.FixSessionSettingsBuilder getInitiatorFixSessionSettings() {
        return super.getInitiatorFixSessionSettings().fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, InstanceProvider.DEFAULT_INSTANCE_ID);
    }

    @Override
    void setupMessageDecoders(ConnectorType connectorType) {
        super.setupMessageDecoders(connectorType);
        Map<MessageType, FixMessageDecoder> targetMap = connectorType.select(initiatorMessageDecoders, acceptorMessageDecoders);
        FixMessageDecoder incomingAdditionalDecoder = connectorType.select(new TestingExecutionReportDecoder(), new TestingNewOrderSingleDecoder());
        targetMap.put(incomingAdditionalDecoder.getMessageType(), incomingAdditionalDecoder);

        when(getFixApplication(connectorType).setup(any(), any(),
                Mockito.argThat(argument -> {
                    argument.add(connectorType.select(MessageTypes.NewOrderSingle, MessageTypes.ExecutionReport));
                    return true;
                }))).thenReturn(List.of(incomingAdditionalDecoder));
    }

    @BeforeEach
    @Override
    void setup() {
        super.setup();
        initiatorFixSessionMonitoringManager = mock(FixSessionsMonitoringManager.class);
        initiatorFixSessionEventsListener = mock(FixSessionPlugin.class);
        when(initiatorFixSessionEventsListener.requiresTimeMeasurement()).thenReturn(true);
        when(initiatorFixSessionMonitoringManager.matchesPluginClass(FixSessionsMonitoringManager.class)).thenReturn(true);
        doReturn(Optional.of(initiatorFixSessionEventsListener)).when(initiatorFixSessionMonitoringManager).onSessionCreated(anyString(), any(), any(), any());

        acceptorFixSessionMonitoringManager = mock(FixSessionsMonitoringManager.class);
        acceptorFixSessionEventsListener = mock(FixSessionPlugin.class);
        when(acceptorFixSessionEventsListener.requiresTimeMeasurement()).thenReturn(true);
        when(acceptorFixSessionMonitoringManager.matchesPluginClass(FixSessionsMonitoringManager.class)).thenReturn(true);
        doReturn(Optional.of(acceptorFixSessionEventsListener)).when(acceptorFixSessionMonitoringManager).onSessionCreated(anyString(), any(), any(), any());

        initiatorFixEngine.stop(Deadline.unlimited());
        initiatorFixEngine = initiatorFixEngineBuilder
                .toBuilder()
                .fixSessionsPlugin(TestingFixSessionMonitoringManagerSettings.builder()
                        .mock(initiatorFixSessionMonitoringManager)
                        .build())
                .build()
                .instance().start();
        fixInitiator = initiatorFixEngine.newInitiator(fixInitiatorBuilder);

        acceptorFixEngine.stop(Deadline.unlimited());
        acceptorFixEngine = acceptorFixEngineBuilder
                .toBuilder()
                .fixSessionsPlugin(TestingFixSessionMonitoringManagerSettings.builder()
                        .mock(acceptorFixSessionMonitoringManager)
                        .build())
                .build()
                .instance().start();
        fixAcceptor = acceptorFixEngine.newAcceptor(fixAcceptorBuilder);
    }

    @Test
    void testMonitoringManagerReceivedAllMessageTypesDefinedForApplication() {
        logonClient();

        await().untilAsserted(() -> {
            verify(acceptorFixSessionEventsListener).onLogon();
            verify(initiatorFixSessionEventsListener).onLogon();
        });
        verify(initiatorFixSessionMonitoringManager).onSessionCreated(anyString(), any(),
                Mockito.assertArg((Consumer<Collection<MessageType>>) messageTypes ->
                        assertThat(messageTypes).containsExactly(MessageTypes.ExecutionReport)),
                Mockito.assertArg((Consumer<Collection<MessageType>>) messageTypes ->
                        assertThat(messageTypes).containsExactly(MessageTypes.NewOrderSingle)));

        verify(acceptorFixSessionMonitoringManager).onSessionCreated(anyString(), any(),
                Mockito.assertArg((Consumer<Collection<MessageType>>) messageTypes ->
                        assertThat(messageTypes).containsExactly(MessageTypes.NewOrderSingle)),
                Mockito.assertArg((Consumer<Collection<MessageType>>) messageTypes ->
                        assertThat(messageTypes).containsExactly(MessageTypes.ExecutionReport)));
    }

    @Test
    void testLogonLogoutEventsAreDispatched() {
        MessageTypeRegistry mtr = MessageTypeRegistry.Registry.getInstance(FixDictionaryId.of(FixDictionaryId.DEFAULT_ID, FixRegularVersion.VERSION_44));

        logonClient();

        await().untilAsserted(() -> {
            verify(acceptorFixSessionEventsListener).onLogon();
            verify(initiatorFixSessionEventsListener).onLogon();
        });
        verify(initiatorFixSessionEventsListener).onMessageSent(eq(mtr.find(CoreMessageType.LOGON)), anyInt(), anyLong(), any());
        verify(acceptorFixSessionEventsListener).onMessageReceived(eq(mtr.find(CoreMessageType.LOGON)), anyInt(), anyLong(), any());

        fixInitiatorSession.logoutPermanently("test");

        await().untilAsserted(() -> {
            verify(acceptorFixSessionEventsListener).onLogout();
            verify(initiatorFixSessionEventsListener).onLogout();
        });

        verify(initiatorFixSessionEventsListener).onMessageSent(eq(mtr.find(CoreMessageType.LOGOUT)), anyInt(), anyLong(), any());
        verify(acceptorFixSessionEventsListener).onMessageReceived(eq(mtr.find(CoreMessageType.LOGOUT)), anyInt(), anyLong(), any());
    }

    @Test
    void testBusinessMessagesAreTracked() {
        logonClient();

        await().untilAsserted(() -> {
            verify(acceptorFixSessionEventsListener).onLogon();
            verify(initiatorFixSessionEventsListener).onLogon();
        });

        reset(initiatorFixSessionEventsListener, acceptorFixSessionEventsListener);
        fixInitiatorSession.send(encodeTestMessage(1), null);
        await().untilAsserted(() -> {
            verify(initiatorFixSessionEventsListener).onMessageSent(eq(testMessageType), eq(219), anyLong(), any());
            verify(acceptorFixSessionEventsListener).onMessageReceived(eq(testMessageType), eq(219), anyLong(), any());
        });

        reset(initiatorFixSessionEventsListener, acceptorFixSessionEventsListener);
        fixAcceptorSession.send(encodeTestMessage(100), null);
        await().untilAsserted(() -> {
            verify(acceptorFixSessionEventsListener).onMessageSent(eq(testMessageType), eq(223), anyLong(), any());
            verify(initiatorFixSessionEventsListener).onMessageReceived(eq(testMessageType), eq(223), anyLong(), any());
        });
    }
}