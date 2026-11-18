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
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.monitoring.*;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    static final String READ_LATENCY_DESCRIPTION = "Time to fully process a received message: from reading its bytes off the socket"
            + " until the engine is done with it, including the application's handling, the message log and the message store.";
    static final String DECODING_LATENCY_DESCRIPTION = "Time to decode a received message and let the application handle it: from"
            + " reading its bytes off the socket until the application's decoder returns; unlike the read latency, without the"
            + " message log and the message store.";
    static final String ENCODING_LATENCY_DESCRIPTION = "Time to encode a sent message: from the moment the application starts"
            + " building it until it is encoded on the session's I/O thread, just before it is written, including the wait in the"
            + " session's outgoing queue.";
    static final String WRITE_LATENCY_DESCRIPTION = "Time to send an encoded message: from handing it to the network layer until"
            + " it is written to the socket, including any wait for the I/O thread; messages sent together in one batch share the"
            + " same start time.";
    static final String RTT_DESCRIPTION = "Round-trip time to the counterparty, measured with TestRequest / Heartbeat exchanges"
            + " and smoothed (exponential moving average); probes run at the session's probe interval, or only when the"
            + " counterparty falls silent if none is set.";
    static final String CLOCK_OFFSET_DESCRIPTION = "The counterparty's clock minus this engine's, estimated NTP-style from the"
            + " SendingTime (52) of the Heartbeats answering TestRequests, and smoothed; positive means the counterparty's clock"
            + " is ahead.";
    static final String LOGON_STATUS_DESCRIPTION = "Where the session stands for an operator: "
            + Arrays.stream(FixSessionStatus.values()).map(status -> status.getCode() + " " + status.name()).collect(Collectors.joining(", "))
            + ".";

    private final MicrometerMonitoringManagerSettings settings;
    private final Map<FixSessionId, SessionMeters> sessionMeters;
    private final Map<FixSessionId, Gauge> destroyedSessionsStatus;
    private final List<FixMeterDescriptor> builtInMeters;
    private final Map<String, MicrometerMeters> engineMeters = new ConcurrentHashMap<>();
    private MeterRegistry meterRegistry;

    public MicrometerMonitoringManager(MicrometerMonitoringManagerSettings settings) {
        this.settings = settings;
        this.sessionMeters = new ConcurrentHashMap<>();
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
        return Optional.of(sessionMeters.computeIfAbsent(fixSession.getFixSessionId(),
                fid -> {
                    removeDestroyedSessionStatus(fid);
                    return new SessionMeters(fixEngineId, fixInstanceId, fixSession, incomingMessageTypes, outgoingMessageTypes, meterRegistry, settings,
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
        SessionMeters meters = sessionMeters.remove(fixSession);
        if (meters != null) {
            meters.destroy(meterRegistry);
            destroyedSessionsStatus.put(fixSession, meters.getSessionStatus());
        }
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
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