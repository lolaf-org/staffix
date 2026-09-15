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
import org.lolaf.staffix.api.monitoring.FixMonitoringAttributes;
import org.lolaf.staffix.api.monitoring.FixSessionMonitoringManagerSettings;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringManager;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.collections.IndexableMap;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
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

    private final MicrometerMonitoringManagerSettings settings;
    private final Map<FixSessionId, FixSessionEventsListenerImpl> listeners;
    private MeterRegistry meterRegistry;

    public MicrometerMonitoringManager(MicrometerMonitoringManagerSettings settings) {
        this.settings = settings;
        this.listeners = new ConcurrentHashMap<>();
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
    public Optional<FixSessionPlugin<FixSessionsMonitoringContext, Void>> onSessionCreated(String fixInstanceId, FixSession fixSession, Collection<MessageType> incomingMessageTypes, Collection<MessageType> outgoingMessageTypes) {
        return Optional.of(listeners.computeIfAbsent(fixSession.getFixSessionId(),
                fid -> new FixSessionEventsListenerImpl(fixInstanceId, fid, incomingMessageTypes, outgoingMessageTypes, meterRegistry, settings, this::onSessionDestroyed)));
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

    private static class FixSessionEventsListenerImpl implements FixSessionPlugin<FixSessionsMonitoringContext, Void> {

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

        FixSessionEventsListenerImpl(String fixInstanceId, FixSessionId fixSessionId, Collection<MessageType> incomingMessageTypes,
                                     Collection<MessageType> outgoingMessageTypes, MeterRegistry meterRegistry, MicrometerMonitoringManagerSettings micrometerMonitoringManagerSettings,
                                     BiConsumer<String, FixSessionId> onSessionDestroyed) {
            this.meterRegistry = meterRegistry;
            this.settings = micrometerMonitoringManagerSettings;
            this.timersForSession = new ConcurrentHashMap<>();
            this.loggedOn = new AtomicBoolean(false);
            this.onSessionDestroyed = onSessionDestroyed;
            Tags tags = Tags.of(Tag.of(FixMonitoringAttributes.FIX_INSTANCE_ID, fixInstanceId),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId()),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_GROUP_ID, fixSessionId.getGroup()));
            this.sessionState = Gauge.builder(SESSION_LOGON_STATE, loggedOn, value -> loggedOn.get() ? 1d : 0d)
                    .description("FIX session logon state gauge")
                    .tags(tags).register(meterRegistry);

            boolean readLatencyEnabled = micrometerMonitoringManagerSettings.isReadLatencyEnabled();
            this.readsTimersPerMsgType = readLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            boolean writeLatencyEnabled = micrometerMonitoringManagerSettings.isWriteLatencyEnabled();
            this.writesTimersPerMsgType = writeLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            boolean decodingLatencyEnabled = micrometerMonitoringManagerSettings.isDecodingLatencyEnabled();
            this.decodingTimersPerMsgType = decodingLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            boolean encodingLatencyEnabled = micrometerMonitoringManagerSettings.isEncodingLatencyEnabled();
            this.encodingTimersPerMsgType = encodingLatencyEnabled ? new IndexableMap<>(MessageType.class) : null;
            this.pluginContext = Optional.of((id, description, tags1) ->
                    getCustomTimer(fixInstanceId, fixSessionId, id, description, tags1));
            registerTimers("in", fixInstanceId, fixSessionId, meterRegistry, micrometerMonitoringManagerSettings,
                    incomingMessageTypes, readLatencyEnabled, writeLatencyEnabled, decodingLatencyEnabled, encodingLatencyEnabled);
            registerTimers("out", fixInstanceId, fixSessionId, meterRegistry, micrometerMonitoringManagerSettings,
                    outgoingMessageTypes, readLatencyEnabled, writeLatencyEnabled, decodingLatencyEnabled, encodingLatencyEnabled);

            this.rttTimer = micrometerMonitoringManagerSettings.isRttLatencyEnabled()
                    ? getTimer(SESSION_RTT, "FIX session round-trip-time (EMA)", meterRegistry, tags, micrometerMonitoringManagerSettings.getRttTimerSettings())
                    : null;
            if (micrometerMonitoringManagerSettings.isClockOffsetEnabled()) {
                this.clockOffsetNanos = new AtomicLong(0L);
                this.clockOffsetGauge = Gauge.builder(SESSION_CLOCK_OFFSET, clockOffsetNanos, AtomicLong::doubleValue)
                        .description("FIX session clock offset (remote − local, EMA, nanoseconds; signed)")
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
                Tags tagsForTimer = Tags.of(Tag.of(FixMonitoringAttributes.FIX_INSTANCE_ID, fixInstanceId),
                        Tag.of(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId()),
                        Tag.of(FixMonitoringAttributes.FIX_MESSAGE_TYPE, mt.code()),
                        Tag.of(FixMonitoringAttributes.FIX_SESSION_GROUP_ID, fixSessionId.getGroup()),
                        Tag.of(FixMonitoringAttributes.FIX_MESSAGE_DIRECTION, msgDirection));
                if (readLatencyEnabled) {
                    readsTimersPerMsgType.put(mt, getTimer(MESSAGES_READ_LATENCY,
                            "FIX messages read latency", meterRegistry, tagsForTimer, timerSettings));
                }
                if (writeLatencyEnabled) {
                    writesTimersPerMsgType.put(mt, getTimer(MESSAGES_WRITE_LATENCY,
                            "FIX messages write latency", meterRegistry, tagsForTimer, timerSettings));
                }
                if (decodingLatencyEnabled) {
                    decodingTimersPerMsgType.put(mt, getTimer(MESSAGES_DECODING_LATENCY,
                            "FIX messages decoding latency", meterRegistry, tagsForTimer, timerSettings));
                }
                if (encodingLatencyEnabled) {
                    encodingTimersPerMsgType.put(mt, getTimer(MESSAGES_ENCODING_LATENCY,
                            "FIX messages encoding latency", meterRegistry, tagsForTimer, timerSettings));
                }
            });
        }

        @Override
        public boolean requiresTimeMeasurement() {
            return true;
        }

        @Override
        public boolean isForPluginContext(Class<? extends PluginContext> pluginClass) {
            return pluginClass.equals(FixSessionsMonitoringContext.class);
        }

        @Override
        public Optional<FixSessionsMonitoringContext> getPluginContext() {
            return pluginContext;
        }

        private org.lolaf.staffix.api.monitoring.Timer getCustomTimer(String fixInstanceId, FixSessionId fixSessionId, String id, String description, Map<String, String> tags) {
            Tags tagsForTimer = Tags.of(
                    Tag.of(FixMonitoringAttributes.FIX_INSTANCE_ID, fixInstanceId),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_ID, fixSessionId.getId()),
                    Tag.of(FixMonitoringAttributes.FIX_SESSION_GROUP_ID, fixSessionId.getGroup()));

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
                return new MicrometerTimerWrapper(raw);
            });
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