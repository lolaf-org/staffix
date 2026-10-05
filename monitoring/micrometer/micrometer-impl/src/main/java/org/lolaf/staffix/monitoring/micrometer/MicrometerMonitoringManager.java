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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.monitoring.FixMeterDescriptor;
import org.lolaf.staffix.api.monitoring.FixMonitoringAttributes;
import org.lolaf.staffix.api.monitoring.FixSessionMonitoringManagerSettings;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.collections.IndexableMap;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Publishes session and message metrics to a Micrometer registry the application supplies.
 */
@Slf4j
public class MicrometerMonitoringManager extends Startable.SimpleStartable<FixSessionsMonitoringManager> implements FixSessionsMonitoringManager {

    static final String MESSAGES_READ_LATENCY = "messages.read.latency";
    static final String MESSAGES_WRITE_LATENCY = "messages.write.latency";
    static final String MESSAGES_DECODING_LATENCY = "messages.decoding.latency";
    static final String MESSAGES_ENCODING_LATENCY = "messages.encoding.latency";
    static final String SESSION_LOGON_STATE = "session.logon.state";
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
    private static final String LOGON_STATE_DESCRIPTION = "1 while the session is logged on, 0 otherwise.";

    private final MicrometerMonitoringManagerSettings settings;
    private final Map<FixSessionId, FixSessionEventsListenerImpl> listeners;
    private final List<FixMeterDescriptor> builtInMeters;
    private MeterRegistry meterRegistry;

    public MicrometerMonitoringManager(MicrometerMonitoringManagerSettings settings) {
        this.settings = settings;
        this.listeners = new ConcurrentHashMap<>();
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
        meters.add(FixMeterDescriptor.builder().name(SESSION_LOGON_STATE).type(FixMeterDescriptor.Type.GAUGE)
                .description(LOGON_STATE_DESCRIPTION).build());
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
        meterRegistry.close();
    }

    @Override
    protected void startMe() throws StartStopException {
        meterRegistry = settings.getMeterRegistrySupplier().get();
        settings.getStartedMeterRegistryConsumer().accept(meterRegistry);
    }

    @Override
    public Optional<FixSessionPlugin<FixSessionsMonitoringContext, Void>> onSessionCreated(String fixEngineId, String fixInstanceId, FixSession fixSession, Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes) {
        return Optional.of(listeners.computeIfAbsent(fixSession.getFixSessionId(),
                fid -> new FixSessionEventsListenerImpl(fixEngineId, fixInstanceId, fid, incomingMessageTypes, outgoingMessageTypes, meterRegistry, settings,
                        builtInMeters, this::onSessionDestroyed)));
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
        }
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    private static class FixSessionEventsListenerImpl implements FixSessionPlugin<FixSessionsMonitoringContext, Void>, FixSessionsMonitoringContext {

        private final AtomicBoolean loggedOn;
        private final Map<MessageType, Timer> readsTimersPerMsgType;
        private final Map<MessageType, Timer> writesTimersPerMsgType;
        private final Map<MessageType, Timer> decodingTimersPerMsgType;
        private final Map<MessageType, Timer> encodingTimersPerMsgType;
        private final Timer rttTimer;
        private final AtomicLong clockOffsetNanos;
        private final Gauge clockOffsetGauge;
        private final Gauge sessionState;
        private final BiConsumer<String, FixSessionId> onSessionDestroyed;
        private final Map<String, MicrometerTimerWrapper> timersForSession;
        private final MeterRegistry meterRegistry;
        private final MicrometerMonitoringManagerSettings settings;
        private final Optional<FixSessionsMonitoringContext> pluginContext;
        private final String fixEngineId;
        private final String fixInstanceId;
        private final FixSessionId fixSessionId;
        private final List<FixMeterDescriptor> builtInMeters;
        private final Map<String, FixMeterDescriptor> customMeters;

        FixSessionEventsListenerImpl(String fixEngineId, String fixInstanceId, FixSessionId fixSessionId, Collection<MessageType> incomingMessageTypes,
                                     Collection<MessageType> outgoingMessageTypes, MeterRegistry meterRegistry, MicrometerMonitoringManagerSettings micrometerMonitoringManagerSettings,
                                     List<FixMeterDescriptor> builtInMeters, BiConsumer<String, FixSessionId> onSessionDestroyed) {
            this.fixEngineId = fixEngineId;
            this.fixInstanceId = fixInstanceId;
            this.fixSessionId = fixSessionId;
            this.builtInMeters = builtInMeters;
            this.customMeters = new ConcurrentHashMap<>();
            this.meterRegistry = meterRegistry;
            this.settings = micrometerMonitoringManagerSettings;
            this.timersForSession = new ConcurrentHashMap<>();
            this.loggedOn = new AtomicBoolean(false);
            this.onSessionDestroyed = onSessionDestroyed;
            Tags tags = Tags.of(Tag.of(FixMonitoringAttributes.FIX_ENGINE_ID.getKey(), fixEngineId),
                    Tag.of(FixMonitoringAttributes.FIX_INSTANCE_ID.getKey(), fixInstanceId),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_NAME.getKey(), fixSessionId.getName()),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_GROUP.getKey(), fixSessionId.getGroup()));
            this.sessionState = Gauge.builder(SESSION_LOGON_STATE, loggedOn, value -> loggedOn.get() ? 1d : 0d)
                    .description(LOGON_STATE_DESCRIPTION)
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
            registerTimers("in", fixInstanceId, fixSessionId, meterRegistry, micrometerMonitoringManagerSettings,
                    incomingMessageTypes, readLatencyEnabled, writeLatencyEnabled, decodingLatencyEnabled, encodingLatencyEnabled);
            registerTimers("out", fixInstanceId, fixSessionId, meterRegistry, micrometerMonitoringManagerSettings,
                    outgoingMessageTypes, readLatencyEnabled, writeLatencyEnabled, decodingLatencyEnabled, encodingLatencyEnabled);

            this.rttTimer = micrometerMonitoringManagerSettings.isRttLatencyEnabled()
                    ? getTimer(SESSION_RTT, RTT_DESCRIPTION, meterRegistry, tags, micrometerMonitoringManagerSettings.getRttTimerSettings())
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

        private static Timer getTimer(String name, String description, MeterRegistry meterRegistry, Tags tagsForTimer,
                                      MicrometerMonitoringManagerSettings.TimerSettings timerSettings) {
            return Timer.builder(name)
                    .tags(tagsForTimer)
                    .description(description)
                    .publishPercentileHistogram(timerSettings.isPublishPercentileHistogram())
                    .publishPercentiles(timerSettings.getHistogramsPercentiles().stream().mapToDouble(Double::doubleValue).toArray())
                    .distributionStatisticExpiry(timerSettings.getDistributionStatisticExpiry())
                    .minimumExpectedValue(timerSettings.getMinExpectedValue())
                    .maximumExpectedValue(timerSettings.getMaxExpectedValue())
                    .distributionStatisticBufferLength(timerSettings.getDistributionStatisticBufferLength())
                    .percentilePrecision(timerSettings.getPercentilePrecision())
                    .serviceLevelObjectives(timerSettings.getServiceLevelObjectives().toArray(new Duration[0]))
                    .register(meterRegistry);
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
                    readsTimersPerMsgType.put(mt, getTimer(MESSAGES_READ_LATENCY,
                            READ_LATENCY_DESCRIPTION, meterRegistry, tagsForTimer, timerSettings));
                }
                if (writeLatencyEnabled) {
                    writesTimersPerMsgType.put(mt, getTimer(MESSAGES_WRITE_LATENCY,
                            WRITE_LATENCY_DESCRIPTION, meterRegistry, tagsForTimer, timerSettings));
                }
                if (decodingLatencyEnabled) {
                    decodingTimersPerMsgType.put(mt, getTimer(MESSAGES_DECODING_LATENCY,
                            DECODING_LATENCY_DESCRIPTION, meterRegistry, tagsForTimer, timerSettings));
                }
                if (encodingLatencyEnabled) {
                    encodingTimersPerMsgType.put(mt, getTimer(MESSAGES_ENCODING_LATENCY,
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
            Tags tagsForTimer = Tags.of(
                    Tag.of(FixMonitoringAttributes.FIX_ENGINE_ID.getKey(), fixEngineId),
                    Tag.of(FixMonitoringAttributes.FIX_INSTANCE_ID.getKey(), fixInstanceId),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_NAME.getKey(), fixSessionId.getName()),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_GROUP.getKey(), fixSessionId.getGroup()));

            AtomicReference<Tags> tagsRef = new AtomicReference<>(tagsForTimer);
            StringBuilder tagsToString = new StringBuilder();
            tags.forEach((k, v) -> {
                tagsToString.append(k).append(v);
                tagsRef.getAndUpdate(currentTags -> currentTags.and(Tag.of(k, v)));
            });
            String timerKey = tags.isEmpty() ? id : id + "-" + tagsToString.toString().hashCode();
            MicrometerMonitoringManagerSettings.TimerSettings timerSettings = settings.getTimerSettings().getOrDefault(id, settings.getDefaultTimersSettings());
            return timersForSession.computeIfAbsent(timerKey, i -> {
                Timer raw = getTimer(id, description, meterRegistry, tagsRef.get(), timerSettings);
                customMeters.merge(id, customMeter(id, description, tags.keySet(), timerSettings), FixSessionEventsListenerImpl::withTagKeysOf);
                return new MicrometerTimerWrapper(raw);
            });
        }

        @Override
        public List<FixMeterDescriptor> getMeterDescriptors() {
            if (customMeters.isEmpty()) {
                return builtInMeters;
            }
            List<FixMeterDescriptor> meters = new ArrayList<>(builtInMeters);
            meters.addAll(customMeters.values());
            return meters;
        }

        private static FixMeterDescriptor customMeter(String id, String description, Collection<String> tagKeys,
                                                      MicrometerMonitoringManagerSettings.TimerSettings timerSettings) {
            return FixMeterDescriptor.builder()
                    .name(id)
                    .type(FixMeterDescriptor.Type.TIMER)
                    .description(description)
                    .tagKeys(tagKeys)
                    .percentiles(timerSettings.isPublishPercentileHistogram())
                    .custom(true)
                    .build();
        }

        private static FixMeterDescriptor withTagKeysOf(FixMeterDescriptor known, FixMeterDescriptor added) {
            Set<String> tagKeys = new LinkedHashSet<>(known.getTagKeys());
            if (!tagKeys.addAll(added.getTagKeys())) {
                return known;
            }
            return known.toBuilder().clearTagKeys().tagKeys(tagKeys).build();
        }

        @Override
        public void onSessionDestroyed(String fixInstanceId, FixSessionId fixSessionId) {
            onSessionDestroyed.accept(fixInstanceId, fixSessionId);
        }

        void destroy(MeterRegistry meterRegistry) {
            meterRegistry.remove(sessionState);
            timersForSession.values().forEach(timer -> timer.destroy(meterRegistry));
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
        public void onLogon() {
            loggedOn.set(true);
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
        public void onLogout() {
            loggedOn.set(false);
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

        @RequiredArgsConstructor
        private static class MicrometerTimerWrapper implements org.lolaf.staffix.api.monitoring.Timer {

            protected final Timer timer;
            protected long startTime;

            @Override
            public void start() {
                startTime = System.nanoTime();
            }

            @Override
            public void stop() {
                timer.record(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
            }

            @Override
            public void record(long duration, TimeUnit unit) {
                timer.record(duration, unit);
            }

            void destroy(MeterRegistry meterRegistry) {
                meterRegistry.remove(timer);
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