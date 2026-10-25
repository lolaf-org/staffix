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
package org.lolaf.staffix.monitoring.micrometer;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.monitoring.*;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.collections.IndexableMap;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * Publishes session and message metrics to a Micrometer registry the application supplies.
 */
@Slf4j
public class MicrometerMonitoringManager extends Startable.SimpleStartable<FixSessionsMonitoringManager> implements FixSessionsMonitoringManager, FixEngineMonitoringManager {

    static final String MESSAGES_READ_LATENCY = "messages.read.latency";
    static final String MESSAGES_WRITE_LATENCY = "messages.write.latency";
    static final String MESSAGES_DECODING_LATENCY = "messages.decoding.latency";
    static final String MESSAGES_ENCODING_LATENCY = "messages.encoding.latency";
    static final String SESSION_LOGON_STATUS = "session.logon.status";
    static final String SESSION_RTT = "session.rtt";
    static final String SESSION_CLOCK_OFFSET = "session.clock.offset";
    private static final String READ_LATENCY_DESCRIPTION = "Time to fully process a received message: from reading its bytes off the socket"
            + " until the engine is done with it, including the application's handling, the message log and the message store.";
    private static final String DECODING_LATENCY_DESCRIPTION = "Time to decode a received message and let the application handle it: from"
            + " reading its bytes off the socket until the application's decoder returns; unlike the read latency, without the"
            + " message log and the message store.";
    private static final String ENCODING_LATENCY_DESCRIPTION = "Time to encode a sent message: from the moment the application starts"
            + " building it until it is encoded on the session's I/O thread, just before it is written, including the wait in the"
            + " session's outgoing queue.";
    private static final String WRITE_LATENCY_DESCRIPTION = "Time to send an encoded message: from handing it to the network layer until"
            + " it is written to the socket, including any wait for the I/O thread; messages sent together in one batch share the"
            + " same start time.";
    private static final String RTT_DESCRIPTION = "Round-trip time to the counterparty, measured with TestRequest / Heartbeat exchanges"
            + " and smoothed (exponential moving average); probes run at the session's probe interval, or only when the"
            + " counterparty falls silent if none is set.";
    private static final String CLOCK_OFFSET_DESCRIPTION = "The counterparty's clock minus this engine's, estimated NTP-style from the"
            + " SendingTime (52) of the Heartbeats answering TestRequests, and smoothed; positive means the counterparty's clock"
            + " is ahead.";
    private static final String LOGON_STATUS_DESCRIPTION = "Where the session stands for an operator: "
            + Arrays.stream(FixSessionStatus.values()).map(status -> status.getCode() + " " + status.name()).collect(Collectors.joining(", "))
            + ".";

    private final MicrometerMonitoringManagerSettings settings;
    private final Map<FixSessionId, FixSessionEventsListenerImpl> listeners;
    private final Map<FixSessionId, Gauge> destroyedSessionsStatus;
    private final List<FixMeterDescriptor> builtInMeters;
    private final Map<String, MicrometerMeters> engineMeters = new ConcurrentHashMap<>();
    private MeterRegistry meterRegistry;

    public MicrometerMonitoringManager(MicrometerMonitoringManagerSettings settings) {
        this.settings = settings;
        this.listeners = new ConcurrentHashMap<>();
        this.destroyedSessionsStatus = new ConcurrentHashMap<>();
        this.builtInMeters = builtInMeters(settings);
    }

    private static List<FixMeterDescriptor> builtInMeters(MicrometerMonitoringManagerSettings settings) {
        boolean latencyPercentiles = settings.getDefaultTimersSettings().isPublishPercentileHistogram()
                || settings.getBuiltInTimerSettings().values().stream().anyMatch(MicrometerMonitoringManagerSettings.TimerSettings::isPublishPercentileHistogram);
        List<FixMeterDescriptor> meters = new ArrayList<>();
        if (settings.isReadLatencyEnabled()) {
            meters.add(latency(MESSAGES_READ_LATENCY, READ_LATENCY_DESCRIPTION, latencyPercentiles));
        }
        if (settings.isDecodingLatencyEnabled()) {
            meters.add(latency(MESSAGES_DECODING_LATENCY, DECODING_LATENCY_DESCRIPTION, latencyPercentiles));
        }
        if (settings.isEncodingLatencyEnabled()) {
            meters.add(latency(MESSAGES_ENCODING_LATENCY, ENCODING_LATENCY_DESCRIPTION, latencyPercentiles));
        }
        if (settings.isWriteLatencyEnabled()) {
            meters.add(latency(MESSAGES_WRITE_LATENCY, WRITE_LATENCY_DESCRIPTION, latencyPercentiles));
        }
        if (settings.isRttLatencyEnabled()) {
            meters.add(FixMeterDescriptor.builder().name(SESSION_RTT).type(FixMeterDescriptor.Type.TIMER).description(RTT_DESCRIPTION)
                    .percentiles(settings.getRttTimerSettings().isPublishPercentileHistogram()).build());
        }
        if (settings.isClockOffsetEnabled()) {
            meters.add(FixMeterDescriptor.builder().name(SESSION_CLOCK_OFFSET).type(FixMeterDescriptor.Type.GAUGE)
                    .description(CLOCK_OFFSET_DESCRIPTION).unit(TimeUnit.NANOSECONDS).build());
        }
        meters.add(FixMeterDescriptor.builder().name(SESSION_LOGON_STATUS).type(FixMeterDescriptor.Type.GAUGE)
                .description(LOGON_STATUS_DESCRIPTION).build());
        return List.copyOf(meters);
    }

    private static FixMeterDescriptor latency(String name, String description, boolean percentiles) {
        return FixMeterDescriptor.builder()
                .name(name)
                .type(FixMeterDescriptor.Type.TIMER)
                .description(description)
                .tagKey(FixMonitoringAttributes.FIX_MESSAGE_TYPE.getKey())
                .tagKey(FixMonitoringAttributes.FIX_MESSAGE_DIRECTION.getKey())
                .percentiles(percentiles)
                .build();
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        settings.getStoppingMeterRegistryConsumer().accept(meterRegistry);
        destroyedSessionsStatus.clear();
        engineMeters.values().forEach(MicrometerMeters::remove);
        engineMeters.clear();
    }

    @Override
    protected void startMe() throws StartStopException {
        meterRegistry = settings.getMeterRegistrySupplier().get();
        settings.getStartedMeterRegistryConsumer().accept(meterRegistry);
    }

    /**
     * Its meters are removed when this stops, after the stopping consumer, so a push registry publishes them last.
     */
    @Override
    public FixEngineMonitoringContext engineContext(String fixEngineId) {
        if (meterRegistry == null) {
            throw new IllegalStateException("Monitoring " + getInstanceId() + " hands out engine meters once started");
        }
        return engineMeters.computeIfAbsent(fixEngineId, id -> new MicrometerMeters(meterRegistry, settings,
                Tags.of(FixMonitoringAttributes.FIX_ENGINE_ID.getKey(), id), false));
    }

    @Override
    public Optional<FixSessionPlugin<FixSessionsMonitoringContext, Void>> onSessionCreated(String fixEngineId, String fixInstanceId, FixSession fixSession, Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes) {
        return Optional.of(listeners.computeIfAbsent(fixSession.getFixSessionId(),
                fid -> {
                    removeDestroyedSessionStatus(fid);
                    return new FixSessionEventsListenerImpl(fixEngineId, fixInstanceId, fixSession, incomingMessageTypes, outgoingMessageTypes, meterRegistry, settings,
                            builtInMeters, this::onSessionDestroyed);
                }));
    }

    // registering the same meter id again would hand back the frozen gauge instead of a live one
    private void removeDestroyedSessionStatus(FixSessionId fixSessionId) {
        Gauge sessionStatus = destroyedSessionsStatus.remove(fixSessionId);
        if (sessionStatus != null) {
            meterRegistry.remove(sessionStatus);
        }
    }

    @Override
    public boolean requiresTimeMeasurement(FixSessionId fixSessionId, FixSessionSettings fixSessionSettings) {
        return true;
    }

    @Override
    public boolean matchesPluginClass(Class<? extends FixSessionsPlugin<?>> pluginClass) {
        return FixSessionsMonitoringManager.class.isAssignableFrom(pluginClass);
    }

    private void onSessionDestroyed(String fixInstanceId, FixSessionId fixSession) {
        FixSessionEventsListenerImpl l = listeners.remove(fixSession);
        if (l != null) {
            l.destroy(meterRegistry);
            destroyedSessionsStatus.put(fixSession, l.sessionStatus);
        }
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    private static class FixSessionEventsListenerImpl implements FixSessionPlugin<FixSessionsMonitoringContext, Void>, FixSessionsMonitoringContext {

        private final Map<MessageType, Timer> readsTimersPerMsgType;
        private final Map<MessageType, Timer> writesTimersPerMsgType;
        private final Map<MessageType, Timer> decodingTimersPerMsgType;
        private final Map<MessageType, Timer> encodingTimersPerMsgType;
        private final Timer rttTimer;
        private final AtomicLong clockOffsetNanos;
        private final Gauge clockOffsetGauge;
        private final Gauge sessionStatus;
        private final BiConsumer<String, FixSessionId> onSessionDestroyed;
        private final MicrometerMeters customMeters;
        private final Optional<FixSessionsMonitoringContext> pluginContext;
        private final String fixEngineId;
        private final List<FixMeterDescriptor> builtInMeters;
        private boolean destroyed;

        FixSessionEventsListenerImpl(String fixEngineId, String fixInstanceId, FixSession fixSession, Collection<MessageType> incomingMessageTypes,
                                     Collection<MessageType> outgoingMessageTypes, MeterRegistry meterRegistry, MicrometerMonitoringManagerSettings micrometerMonitoringManagerSettings,
                                     List<FixMeterDescriptor> builtInMeters, BiConsumer<String, FixSessionId> onSessionDestroyed) {
            FixSessionId fixSessionId = fixSession.getFixSessionId();
            this.fixEngineId = fixEngineId;
            this.builtInMeters = builtInMeters;
            this.onSessionDestroyed = onSessionDestroyed;
            Tags tags = Tags.of(Tag.of(FixMonitoringAttributes.FIX_ENGINE_ID.getKey(), fixEngineId),
                    Tag.of(FixMonitoringAttributes.FIX_INSTANCE_ID.getKey(), fixInstanceId),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_NAME.getKey(), fixSessionId.getName()),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_GROUP.getKey(), fixSessionId.getGroup()));
            this.customMeters = new MicrometerMeters(meterRegistry, micrometerMonitoringManagerSettings, tags, true);
            // pulled at each export: the schedule changes the status with no event to push it on
            this.sessionStatus = Gauge.builder(SESSION_LOGON_STATUS, fixSession, this::statusCode)
                    .strongReference(true)
                    .description(LOGON_STATUS_DESCRIPTION)
                    .tags(tags).register(meterRegistry);

            boolean readLatencyEnabled = micrometerMonitoringManagerSettings.isReadLatencyEnabled();
            this.readsTimersPerMsgType = readLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            boolean writeLatencyEnabled = micrometerMonitoringManagerSettings.isWriteLatencyEnabled();
            this.writesTimersPerMsgType = writeLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            boolean decodingLatencyEnabled = micrometerMonitoringManagerSettings.isDecodingLatencyEnabled();
            this.decodingTimersPerMsgType = decodingLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            boolean encodingLatencyEnabled = micrometerMonitoringManagerSettings.isEncodingLatencyEnabled();
            this.encodingTimersPerMsgType = encodingLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            this.pluginContext = Optional.of(this);
            registerTimers(FixMessageDirection.IN.getValue(), fixInstanceId, fixSessionId, meterRegistry, micrometerMonitoringManagerSettings,
                    incomingMessageTypes, readLatencyEnabled, writeLatencyEnabled, decodingLatencyEnabled, encodingLatencyEnabled);
            registerTimers(FixMessageDirection.OUT.getValue(), fixInstanceId, fixSessionId, meterRegistry, micrometerMonitoringManagerSettings,
                    outgoingMessageTypes, readLatencyEnabled, writeLatencyEnabled, decodingLatencyEnabled, encodingLatencyEnabled);

            this.rttTimer = micrometerMonitoringManagerSettings.isRttLatencyEnabled()
                    ? MicrometerMeters.timer(SESSION_RTT, RTT_DESCRIPTION, meterRegistry, tags, micrometerMonitoringManagerSettings.getRttTimerSettings())
                    : null;
            if (micrometerMonitoringManagerSettings.isClockOffsetEnabled()) {
                this.clockOffsetNanos = new AtomicLong(0L);
                this.clockOffsetGauge = Gauge.builder(SESSION_CLOCK_OFFSET, clockOffsetNanos, AtomicLong::doubleValue)
                        .description(CLOCK_OFFSET_DESCRIPTION)
                        .tags(tags)
                        .register(meterRegistry);
            } else {
                this.clockOffsetNanos = null;
                this.clockOffsetGauge = null;
            }
        }

        private void registerTimers(String msgDirection, String fixInstanceId, FixSessionId fixSessionId, MeterRegistry meterRegistry, MicrometerMonitoringManagerSettings micrometerMonitoringManagerSettings,
                                    Collection<MessageType> messageTypes, boolean readLatencyEnabled, boolean writeLatencyEnabled, boolean decodingLatencyEnabled, boolean encodingLatencyEnabled) {
            messageTypes.forEach(mt -> {
                log.debug("Registering timers for message type {} and fix session {}", mt, fixSessionId);
                MicrometerMonitoringManagerSettings.TimerSettings timerSettings =
                        micrometerMonitoringManagerSettings.getBuiltInTimerSettings().get(mt);
                if (timerSettings == null) {
                    timerSettings = micrometerMonitoringManagerSettings.getDefaultTimersSettings();
                }
                Tags tagsForTimer = Tags.of(Tag.of(FixMonitoringAttributes.FIX_ENGINE_ID.getKey(), fixEngineId),
                        Tag.of(FixMonitoringAttributes.FIX_INSTANCE_ID.getKey(), fixInstanceId),
                        Tag.of(FixMonitoringAttributes.FIX_SESSION_NAME.getKey(), fixSessionId.getName()),
                        Tag.of(FixMonitoringAttributes.FIX_MESSAGE_TYPE.getKey(), mt.code()),
                        Tag.of(FixMonitoringAttributes.FIX_SESSION_GROUP.getKey(), fixSessionId.getGroup()),
                        Tag.of(FixMonitoringAttributes.FIX_MESSAGE_DIRECTION.getKey(), msgDirection));
                if (readLatencyEnabled) {
                    readsTimersPerMsgType.put(mt, MicrometerMeters.timer(MESSAGES_READ_LATENCY,
                            READ_LATENCY_DESCRIPTION, meterRegistry, tagsForTimer, timerSettings));
                }
                if (writeLatencyEnabled) {
                    writesTimersPerMsgType.put(mt, MicrometerMeters.timer(MESSAGES_WRITE_LATENCY,
                            WRITE_LATENCY_DESCRIPTION, meterRegistry, tagsForTimer, timerSettings));
                }
                if (decodingLatencyEnabled) {
                    decodingTimersPerMsgType.put(mt, MicrometerMeters.timer(MESSAGES_DECODING_LATENCY,
                            DECODING_LATENCY_DESCRIPTION, meterRegistry, tagsForTimer, timerSettings));
                }
                if (encodingLatencyEnabled) {
                    encodingTimersPerMsgType.put(mt, MicrometerMeters.timer(MESSAGES_ENCODING_LATENCY,
                            ENCODING_LATENCY_DESCRIPTION, meterRegistry, tagsForTimer, timerSettings));
                }
            });
        }

        @Override
        public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
            return pluginClass.equals(FixSessionsMonitoringContext.class);
        }

        @Override
        public Optional<FixSessionsMonitoringContext> getPluginContext() {
            return pluginContext;
        }

        @Override
        public org.lolaf.staffix.api.monitoring.Timer getTimer(String id, String description, Map<String, String> tags) {
            return customMeters.getTimer(id, description, tags);
        }

        @Override
        public org.lolaf.staffix.api.monitoring.Counter getCounter(String id, String description, Map<String, String> tags) {
            return customMeters.getCounter(id, description, tags);
        }

        @Override
        public org.lolaf.staffix.api.monitoring.Counter getCounter(String id, String description, Map<String, String> tags, LongSupplier total) {
            return customMeters.getCounter(id, description, tags, total);
        }

        @Override
        public org.lolaf.staffix.api.monitoring.Gauge getGauge(String id, String description, Map<String, String> tags) {
            return customMeters.getGauge(id, description, tags);
        }

        @Override
        public org.lolaf.staffix.api.monitoring.Gauge getGauge(String id, String description, Map<String, String> tags, DoubleSupplier value) {
            return customMeters.getGauge(id, description, tags, value);
        }

        @Override
        public List<FixMeterDescriptor> getMeterDescriptors() {
            if (customMeters.isEmpty()) {
                return builtInMeters;
            }
            List<FixMeterDescriptor> meters = new ArrayList<>(builtInMeters);
            meters.addAll(customMeters.getMeterDescriptors());
            return meters;
        }

        @Override
        public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
            onSessionDestroyed.accept(fixInstanceId, fixSessionId);
        }

        private double statusCode(FixSession fixSession) {
            return (destroyed ? FixSessionStatus.LOGGED_OUT_BY_OPERATOR : fixSession.getStatus()).getCode();
        }

        /**
         * Keeps the status gauge, frozen at logged out by an operator, so that a push registry's final publish on close
         * reports the session down rather than its last sampled status.
         */
        void destroy(MeterRegistry meterRegistry) {
            destroyed = true;
            customMeters.remove();
            if (readsTimersPerMsgType != null) {
                readsTimersPerMsgType.values().forEach(meterRegistry::remove);
                readsTimersPerMsgType.clear();
            }
            if (writesTimersPerMsgType != null) {
                writesTimersPerMsgType.values().forEach(meterRegistry::remove);
                writesTimersPerMsgType.clear();
            }
            if (decodingTimersPerMsgType != null) {
                decodingTimersPerMsgType.values().forEach(meterRegistry::remove);
                decodingTimersPerMsgType.clear();
            }
            if (encodingTimersPerMsgType != null) {
                encodingTimersPerMsgType.values().forEach(meterRegistry::remove);
                encodingTimersPerMsgType.clear();
            }
            if (rttTimer != null) {
                meterRegistry.remove(rttTimer);
            }
            if (clockOffsetGauge != null) {
                meterRegistry.remove(clockOffsetGauge);
            }
        }

        @Override
        public void onMessageDecodingFinished(MessageType messageType, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            if (decodingTimersPerMsgType != null) {
                Timer t = decodingTimersPerMsgType.get(messageType);
                if (t != null) {
                    t.record(System.nanoTime() - localReceiveTimeInNanos, TimeUnit.NANOSECONDS);
                } else if (messageType.getAsInt() >= 0) {
                    logUnknownDecodingTimerMessageType(messageType);
                }
            }
        }

        @Override
        public void onMessageReceived(MessageType messageType, int payloadSize, long localReceiveTimeInNanos, UTCTime localReceiveTime) {
            if (readsTimersPerMsgType != null) {
                Timer t = readsTimersPerMsgType.get(messageType);
                if (t != null) {
                    t.record(System.nanoTime() - localReceiveTimeInNanos, TimeUnit.NANOSECONDS);
                } else if (messageType.getAsInt() >= 0) {
                    // we may have for some reason a message type that has been created on the fly
                    logUnknownDecodingTimerMessageType(messageType);
                }
            }
        }

        @Override
        public void onMessageEncodingFinished(MessageType messageType, long encodingEndTimeInNanos, Void encodingToken) {
            if (encodingTimersPerMsgType != null) {
                Timer t = encodingTimersPerMsgType.get(messageType);
                if (t != null) {
                    t.record(System.nanoTime() - encodingEndTimeInNanos, TimeUnit.NANOSECONDS);
                } else if (messageType.getAsInt() >= 0) {
                    logUnknowEncodingTimerMessageType(messageType);
                }
            }
        }

        @Override
        public void onMessageSent(MessageType messageType, int payloadSize, long localSendingTimeInNanos, UTCTime localSendingTime) {
            if (writesTimersPerMsgType != null) {
                Timer t = writesTimersPerMsgType.get(messageType);
                if (t != null) {
                    t.record(System.nanoTime() - localSendingTimeInNanos, TimeUnit.NANOSECONDS);
                } else if (messageType.getAsInt() >= 0) {
                    logUnknowEncodingTimerMessageType(messageType);
                }
            }
        }

        private void logUnknownDecodingTimerMessageType(MessageType messageType) {
            if (!messageType.isAdmin()) {
                // we may have for some reason a message type that has been created on the fly
                log.warn("Abnormal: unable to find a timer for FIX received message type {}", messageType);
            }
        }

        private void logUnknowEncodingTimerMessageType(MessageType messageType) {
            if (!messageType.isAdmin()) {
                log.warn("Unable to find a timer for FIX sent message type {}, please add it using Set during method call" +
                        "FixApplication.setup(..., Set<MessageType> encodedMessagesTypes)", messageType);
            }
        }

        @Override
        public void onRttMeasurement(RttMeasurement measurement) {
            if (rttTimer != null) {
                rttTimer.record(measurement.getRoundTripTime().toNanos(), TimeUnit.NANOSECONDS);
            }
            if (clockOffsetNanos != null) {
                clockOffsetNanos.set(measurement.getClockOffset().toNanos());
            }
        }
    }

    public static class MicrometerMonitoringManagerFactoryImpl implements FixSessionMonitoringManagerSettings.FixSessionMonitoringManagerFactory<MicrometerMonitoringManagerSettings> {

        @Override
        public Class<MicrometerMonitoringManagerSettings> getSettingsClass() {
            return MicrometerMonitoringManagerSettings.class;
        }

        @Override
        public FixSessionsMonitoringManager newInstance(MicrometerMonitoringManagerSettings settings) {
            return new MicrometerMonitoringManager(settings);
        }
    }

}