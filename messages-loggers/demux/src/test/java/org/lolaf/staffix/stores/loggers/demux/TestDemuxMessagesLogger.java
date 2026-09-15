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
package org.lolaf.staffix.stores.loggers.demux;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLogger.BatchingLogger;
import org.lolaf.staffix.api.logging.FixMessagesLogger.BatchingLogger.LogEvent;
import org.lolaf.staffix.api.logging.FixMessagesLogger.Logger;
import org.lolaf.staffix.api.logging.FixMessagesLogger.LoggingException;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;

import java.nio.ByteBuffer;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TestDemuxMessagesLogger {

    private FixSessionId fixSessionId;
    private MessageType messageType;
    private MessageTypeRegistry messageTypeRegistry;
    private UTCTime now;
    private ByteBuffer message;

    @BeforeEach
    void before() {
        fixSessionId = FixSessionId.of("test", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
        messageType = MessageType.of("A", false);
        messageTypeRegistry = mock(MessageTypeRegistry.class);
        now = UTCTime.of(Instant.now());
        message = ByteBuffer.wrap("body".getBytes());
    }

    /**
     * Wraps each provided per-session {@link Logger} behind a mocked {@link FixMessagesLogger} and builds a
     * {@link DemuxMessagesLogger} that demultiplexes to them.
     */
    private DemuxMessagesLogger demuxWith(Logger... wrapped) {
        DemuxMessagesLoggerSettings.DemuxMessagesLoggerSettingsBuilder<?, ?> builder =
                DemuxMessagesLoggerSettings.builder().instanceId("demux");
        for (Logger w : wrapped) {
            FixMessagesLogger outer = mock(FixMessagesLogger.class);
            when(outer.getLogger(any(), any(), any())).thenReturn(w);
            builder.demuxedLogger(outer);
        }
        return new DemuxMessagesLogger(builder.build());
    }

    private Logger getCompositeLogger(DemuxMessagesLogger demux) {
        return demux.getLogger("fixInstanceId", fixSessionId, messageTypeRegistry);
    }

    @Test
    void testGetInstanceId() {
        DemuxMessagesLogger demux = demuxWith(mock(Logger.class));
        assertThat(demux.getInstanceId()).isEqualTo("demux");
    }

    @Test
    void testStartAndStopDelegateToWrappedLoggers() {
        FixMessagesLogger logger1 = mock(FixMessagesLogger.class);
        FixMessagesLogger logger2 = mock(FixMessagesLogger.class);
        DemuxMessagesLogger demux = new DemuxMessagesLogger(DemuxMessagesLoggerSettings.builder()
                .instanceId("demux")
                .demuxedLogger(logger1)
                .demuxedLogger(logger2)
                .build());

        demux.start();
        verify(logger1).start();
        verify(logger2).start();

        Deadline deadline = Deadline.immediate();
        demux.stop(deadline);
        verify(logger1).stop(deadline);
        verify(logger2).stop(deadline);
    }

    @Test
    void testGetLoggerReturnsPlainLoggerWhenNotAllBatching() {
        DemuxMessagesLogger demux = demuxWith(mock(Logger.class), mock(BatchingLogger.class));
        Logger composite = getCompositeLogger(demux);
        assertThat(composite).isNotInstanceOf(BatchingLogger.class);
    }

    @Test
    void testGetLoggerReturnsBatchingLoggerWhenAllBatching() {
        DemuxMessagesLogger demux = demuxWith(mock(BatchingLogger.class), mock(BatchingLogger.class));
        Logger composite = getCompositeLogger(demux);
        assertThat(composite).isInstanceOf(BatchingLogger.class);
    }

    @Test
    void testLogIncomingRoutedOnlyToIncomingLoggers() throws LoggingException {
        Logger incoming = mock(Logger.class);
        when(incoming.isLoggingIncoming()).thenReturn(true);
        Logger nonIncoming = mock(Logger.class);
        when(nonIncoming.isLoggingIncoming()).thenReturn(false);

        Logger composite = getCompositeLogger(demuxWith(incoming, nonIncoming));
        composite.logIncoming(now, messageType, message);

        verify(incoming).logIncoming(now, messageType, message);
        verify(nonIncoming, never()).logIncoming(any(), any(), any());
    }

    @Test
    void testLogOutgoingRoutedOnlyToOutgoingLoggers() throws LoggingException {
        Logger outgoing = mock(Logger.class);
        when(outgoing.isLoggingOutgoing()).thenReturn(true);
        Logger nonOutgoing = mock(Logger.class);
        when(nonOutgoing.isLoggingOutgoing()).thenReturn(false);

        Logger composite = getCompositeLogger(demuxWith(outgoing, nonOutgoing));
        composite.logOutgoing(now, messageType, message);

        verify(outgoing).logOutgoing(now, messageType, message);
        verify(nonOutgoing, never()).logOutgoing(any(), any(), any());
    }

    @Test
    void testLogEventRoutedOnlyToEventsLoggers() throws LoggingException {
        Logger events = mock(Logger.class);
        when(events.isLoggingEvents()).thenReturn(true);
        Logger nonEvents = mock(Logger.class);
        when(nonEvents.isLoggingEvents()).thenReturn(false);

        Logger composite = getCompositeLogger(demuxWith(events, nonEvents));
        composite.logEvent(now, "an event");

        verify(events).logEvent(now, "an event");
        verify(nonEvents, never()).logEvent(any(), any());
    }

    @Test
    void testLogEventWithParamsRoutedOnlyToEventsLoggers() throws LoggingException {
        Logger events = mock(Logger.class);
        when(events.isLoggingEvents()).thenReturn(true);
        Logger nonEvents = mock(Logger.class);
        when(nonEvents.isLoggingEvents()).thenReturn(false);

        Logger composite = getCompositeLogger(demuxWith(events, nonEvents));
        composite.logEvent(now, "an event %s", "param");

        verify(events).logEvent(now, "an event %s", "param");
        verify(nonEvents, never()).logEvent(any(), any(), any());
    }

    @Test
    void testIsLoggingFlagsAreOredAcrossWrappedLoggers() {
        Logger incomingOnly = mock(Logger.class);
        when(incomingOnly.isLoggingIncoming()).thenReturn(true);
        Logger outgoingOnly = mock(Logger.class);
        when(outgoingOnly.isLoggingOutgoing()).thenReturn(true);

        Logger composite = getCompositeLogger(demuxWith(incomingOnly, outgoingOnly));

        assertThat(composite.isLoggingIncoming()).isTrue();
        assertThat(composite.isLoggingOutgoing()).isTrue();
        assertThat(composite.isLoggingEvents()).isFalse();
    }

    @Test
    void testIsLoggingFlagsAllFalseWhenNoneLog() {
        Logger composite = getCompositeLogger(demuxWith(mock(Logger.class), mock(Logger.class)));

        assertThat(composite.isLoggingIncoming()).isFalse();
        assertThat(composite.isLoggingOutgoing()).isFalse();
        assertThat(composite.isLoggingEvents()).isFalse();
    }

    @Test
    void testLogIncomingCallsAllIncomingLoggersAndRethrowsOnFailure() throws LoggingException {
        Logger failing = mock(Logger.class);
        when(failing.isLoggingIncoming()).thenReturn(true);
        doThrow(new LoggingException()).when(failing).logIncoming(any(), any(), any());
        Logger succeeding = mock(Logger.class);
        when(succeeding.isLoggingIncoming()).thenReturn(true);

        Logger composite = getCompositeLogger(demuxWith(failing, succeeding));

        assertThatThrownBy(() -> composite.logIncoming(now, messageType, message))
                .isInstanceOf(LoggingException.class);

        // even though the first logger failed, the second must still have been invoked
        verify(succeeding).logIncoming(now, messageType, message);
    }

    @Test
    void testIsUnderlyingStorageResourceAvailableRequiresAllAvailable() {
        Logger available = mock(Logger.class);
        when(available.isUnderlyingStorageResourceAvailable()).thenReturn(true);
        Logger unavailable = mock(Logger.class);
        when(unavailable.isUnderlyingStorageResourceAvailable()).thenReturn(false);

        assertThat(getCompositeLogger(demuxWith(available, available)).isUnderlyingStorageResourceAvailable()).isTrue();
        assertThat(getCompositeLogger(demuxWith(available, unavailable)).isUnderlyingStorageResourceAvailable()).isFalse();
    }

    @Test
    void testGetUnderlyingStorageResourceDescriptionIsJoined() {
        Logger first = mock(Logger.class);
        when(first.getUnderlyingStorageResourceDescription()).thenReturn("fileA");
        Logger second = mock(Logger.class);
        when(second.getUnderlyingStorageResourceDescription()).thenReturn("fileB");

        Logger composite = getCompositeLogger(demuxWith(first, second));
        assertThat(composite.getUnderlyingStorageResourceDescription()).isEqualTo("fileA, fileB");
    }

    @Test
    void testCompositeStartStopDelegatesToWrappedLoggers() {
        Logger first = mock(Logger.class);
        Logger second = mock(Logger.class);
        Logger composite = getCompositeLogger(demuxWith(first, second));

        composite.start();
        verify(first).start();
        verify(second).start();

        Deadline deadline = Deadline.immediate();
        composite.stop(deadline);
        verify(first).stop(deadline);
        verify(second).stop(deadline);
    }

    @Test
    void testBatchingLoggerLogEventsDelegatesToAllBatchingLoggers() throws LoggingException {
        BatchingLogger first = mock(BatchingLogger.class);
        BatchingLogger second = mock(BatchingLogger.class);
        Logger composite = getCompositeLogger(demuxWith(first, second));
        assertThat(composite).isInstanceOf(BatchingLogger.class);

        LogEvent[] events = new LogEvent[]{mock(LogEvent.class)};
        ((BatchingLogger) composite).logEvents(events, 1);

        verify(first).logEvents(events, 1);
        verify(second).logEvents(events, 1);
    }

    @Test
    void testGetLoggerForwardsArgumentsToWrappedLoggers() {
        Logger wrapped = mock(Logger.class);
        FixMessagesLogger outer = mock(FixMessagesLogger.class, RETURNS_DEEP_STUBS);
        when(outer.getLogger(any(), any(), any())).thenReturn(wrapped);
        DemuxMessagesLogger demux = new DemuxMessagesLogger(DemuxMessagesLoggerSettings.builder()
                .instanceId("demux")
                .demuxedLogger(outer)
                .build());

        demux.getLogger("fixInstanceId", fixSessionId, messageTypeRegistry);

        verify(outer).getLogger("fixInstanceId", fixSessionId, messageTypeRegistry);
    }
}