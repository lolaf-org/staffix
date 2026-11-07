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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.monitoring.FixMessageDirection;
import org.lolaf.staffix.api.monitoring.FixMeterDescriptor;
import org.lolaf.staffix.api.monitoring.FixMonitoringAttributes;
import org.lolaf.staffix.api.monitoring.FixSessionsMonitoringContext;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionStatus;
import org.lolaf.staffix.api.session.RttMeasurement;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.collections.IndexableMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.CLOCK_OFFSET_DESCRIPTION;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.DECODING_LATENCY_DESCRIPTION;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.ENCODING_LATENCY_DESCRIPTION;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.LOGON_STATUS_DESCRIPTION;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.MESSAGES_DECODING_LATENCY;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.MESSAGES_ENCODING_LATENCY;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.MESSAGES_READ_LATENCY;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.MESSAGES_WRITE_LATENCY;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.READ_LATENCY_DESCRIPTION;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.RTT_DESCRIPTION;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.SESSION_CLOCK_OFFSET;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.SESSION_LOGON_STATUS;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.SESSION_RTT;
import static org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManager.WRITE_LATENCY_DESCRIPTION;

/**
 * A session's meters: its built-in latency timers, RTT, clock offset and status, and the application's own.
 */
@Slf4j
final class SessionMeters implements FixSessionPlugin<FixSessionsMonitoringContext, Void>, FixSessionsMonitoringContext {

    private final Map<MessageType, Timer> readsTimersPerMsgType;
    private final Map<MessageType, Timer> writesTimersPerMsgType;
    private final Map<MessageType, Timer> decodingTimersPerMsgType;
    private final Map<MessageType, Timer> encodingTimersPerMsgType;
    private final Timer rttTimer;
    private final AtomicLong clockOffsetNanos;
    private final Gauge clockOffsetGauge;
    @Getter
    private final Gauge sessionStatus;
    private final BiConsumer<String, FixSessionId> onSessionDestroyed;
    private final MicrometerMeters customMeters;
    private final Optional<FixSessionsMonitoringContext> pluginContext;
    private final String fixEngineId;
    private final List<FixMeterDescriptor> builtInMeters;
    private boolean destroyed;

    SessionMeters(String fixEngineId, String fixInstanceId, FixSession fixSession, Collection<MessageType> incomingMessageTypes,
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
                logNoTimerForReceived(messageType);
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
                logNoTimerForReceived(messageType);
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
                logNoTimerForSent(messageType);
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
                logNoTimerForSent(messageType);
            }
        }
    }

    private void logNoTimerForReceived(MessageType messageType) {
        if (!messageType.isAdmin()) {
            // we may have for some reason a message type that has been created on the fly
            log.warn("Abnormal: unable to find a timer for FIX received message type {}", messageType);
        }
    }

    private void logNoTimerForSent(MessageType messageType) {
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
