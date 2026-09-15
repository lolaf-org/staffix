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
package org.lolaf.staffix.stores.loggers.otlp;

import org.lolaf.staffix.api.serde.SerDe;
import com.google.protobuf.ByteString;
import com.google.protobuf.PooledBytesString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.*;
import io.opentelemetry.proto.resource.v1.Resource;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.ringos.threading.NamedThreadFactory;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.http.FastByteArrayOutputStream;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.monitoring.FixMonitoringAttributes;
import org.lolaf.staffix.api.monitoring.FixMonitoringConstants;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.collections.IndexableMap;
import org.lolaf.staffix.stores.loggers.core.AbstractLogger;
import org.lolaf.staffix.stores.loggers.core.MessagesCoreBatchingLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ships every message to an OpenTelemetry collector as an OTLP log record, over HTTP or gRPC.
 */
@Slf4j
public class OtlpMessagesLogger extends MessagesCoreBatchingLogger {

    static final String OTLP_CONNECTOR_STATE_TEST_LOG = "OTLP messages logger connection state evaluation test log";
    private final OtlpMessagesLoggerSettings settings;
    private final ScheduledExecutorService logsFlushingExecutorService;
    private final boolean embeddedExecutor;

    protected OtlpMessagesLogger(OtlpMessagesLoggerSettings settings) {
        super(settings);
        this.settings = settings;
        if (settings.getLogsFlushingExecutorService() == null) {
            logsFlushingExecutorService = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("otlp-logs-publisher"));
            embeddedExecutor = true;
        } else {
            embeddedExecutor = false;
            logsFlushingExecutorService = settings.getLogsFlushingExecutorService();
        }
    }

    @Override
    protected void startMe() throws StartStopException {
        // nothing to do
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        if (embeddedExecutor) {
            logsFlushingExecutorService.shutdown();
        }
    }

    @Override
    public BatchingLogger instanciateLogger(String fixInstanceId, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry) {
        return new LoggerImpl(settings, fixSessionId, messageTypeRegistry, logsFlushingExecutorService, fixInstanceId);
    }

    private static class LoggerImpl extends AbstractLogger implements BatchingLogger {

        /**
         * What this logger writes, whatever the sender was configured with.
         */
        private static final String PROTOBUF_CONTENT_TYPE = "application/x-protobuf";

        /**
         * The gRPC method an OTLP collector exposes for logs, as gRPC spells it on the wire.
         */
        private static final String LOGS_SERVICE_EXPORT_METHOD = "/opentelemetry.proto.collector.logs.v1.LogsService/Export";

        private static final String FIX_LOG_TYPE = "fix.log.type";
        private static final KeyValue INCOMING_LOG = createKeyValue(FIX_LOG_TYPE, "in");
        private static final KeyValue OUTGOING_LOG = createKeyValue(FIX_LOG_TYPE, "out");
        private static final KeyValue EVENT_LOG = createKeyValue(FIX_LOG_TYPE, "event");

        private final OtlpMessagesLoggerSettings settings;
        private final OtlpLogsTransport transport;
        private final FastByteArrayOutputStream outputBuffer;
        private final RingBuffer<PooledLogRecord> pooledLogRecords;
        private final RingBuffer<PooledLogRecord> batchedLogRecords;
        private final PooledLogRecord[] pooledLogRecordsToRelease;
        private final LogsData.Builder logsDataBuilder;
        private final ResourceLogs.Builder resourceLogsBuilder;
        private final ScopeLogs.Builder scopeLogsBuilder;
        private final Map<MessageType, KeyValue> messageTypeCache;
        private final ScheduledExecutorService logsFlushingExecutorService;
        private final IdleStrategy batchedLogsFullIdleStrategy;
        private final String otlpEndpoint;
        private final byte fieldDelimiterReplacementChar;
        private final String fixInstanceId;
        private ScheduledFuture<?> scheduledFlush;
        private long totalLogsSent = 0;
        private long cacheHitCount;

        public LoggerImpl(OtlpMessagesLoggerSettings settings, FixSessionId fixSessionId, MessageTypeRegistry messageTypeRegistry,
                          ScheduledExecutorService logsFlushingExecutorService, String fixInstanceId) {
            super(settings, fixSessionId);
            this.fixInstanceId = fixInstanceId;
            this.settings = settings;
            // The endpoint, the method and the content type belong to the logger, not to whoever
            // configured the sender: it publishes protobuf, to its own OTLP logs endpoint, and nowhere
            // else. gRPC addresses a method rather than a path, so the two differ in how they say it.
            if (settings.getTransport() == OtlpMessagesLoggerSettings.OtlpTransport.GRPC) {
                this.otlpEndpoint = stripTrailingSlash(settings.getOtlpEndpointUrl());
                if (settings.getGrpcSenderFactory() == null) {
                    throw new IllegalArgumentException("This logger is configured for OTLP/gRPC but has no"
                            + " grpcSenderFactory. gRPC needs a client that can read HTTP/2 trailers, so"
                            + " set one backed by staffix-http-client-okhttp or staffix-http-client-jetty;"
                            + " the JDK client cannot serve gRPC at all.");
                }
                this.transport = new GrpcLogsTransport(
                        settings.getGrpcSenderFactory().apply(settings.getGrpcSenderSettings().toBuilder()
                                .endpointUrl(otlpEndpoint)
                                .fullMethodName(LOGS_SERVICE_EXPORT_METHOD)
                                .build()),
                        otlpEndpoint, LOGS_SERVICE_EXPORT_METHOD);
            } else {
                String otlpEndpointString = settings.getOtlpEndpointUrl();
                if (!otlpEndpointString.endsWith("/v1/logs")) {
                    otlpEndpointString += "/v1/logs";
                }
                this.otlpEndpoint = otlpEndpointString;
                this.transport = new HttpLogsTransport(
                        settings.getHttpSenderFactory().apply(settings.getHttpSenderSettings().toBuilder()
                                .endpointUrl(otlpEndpoint)
                                .contentType(PROTOBUF_CONTENT_TYPE)
                                .build()),
                        otlpEndpoint);
            }
            this.outputBuffer = new FastByteArrayOutputStream(2048);
            this.pooledLogRecords = RingBufferFactory.build(RingBufferFactory.AccessType.SINGLE_CONSUMER_MULTI_PRODUCER,
                    settings.getLogsBufferSize());
            for (int i = 0; i < settings.getLogsBufferSize(); i++) {
                pooledLogRecords.offer(new PooledLogRecord(settings.getPooledLogsByteBufferSize()));
            }
            this.pooledLogRecordsToRelease = new PooledLogRecord[settings.getLogsBufferSize()];
            this.batchedLogRecords = RingBufferFactory.build(RingBufferFactory.AccessType.SINGLE_CONSUMER_MULTI_PRODUCER,
                    settings.getLogsBufferSize());
            this.logsDataBuilder = LogsData.newBuilder();
            this.resourceLogsBuilder = ResourceLogs.newBuilder()
                    .setResource(Resource.newBuilder()
                            .addAllAttributes(getResourceAttributes())
                            .build());
            this.scopeLogsBuilder = ScopeLogs.newBuilder();
            this.messageTypeCache = new IndexableMap<>(MessageType.class);
            messageTypeRegistry.getMessageTypes().forEach(mt ->
                    messageTypeCache.put(mt, KeyValue.newBuilder()
                            .setKey(FixMonitoringAttributes.FIX_MESSAGE_TYPE)
                            .setValue(AnyValue.newBuilder().setStringValue(mt.code()).build())
                            .build()));
            this.logsFlushingExecutorService = logsFlushingExecutorService;
            this.batchedLogsFullIdleStrategy = settings.getLogsRecordsFullIdleStrategy().get();
            this.fieldDelimiterReplacementChar = settings.getFixMessageFieldsDelimiter() == null ? CoreFields.FIELD_SEPARATOR_BYTE : (byte) settings.getFixMessageFieldsDelimiter().charValue();
        }

        private static KeyValue createKeyValue(String key, String value) {
            return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
        }

        /**
         * gRPC addresses an authority and a method, so a trailing slash on the endpoint would put an
         * empty segment in front of the method path.
         */
        private static String stripTrailingSlash(String endpoint) {
            return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        }

        @Override
        public boolean isUnderlyingStorageResourceAvailable() {
            ScopeLogs.Builder scopeLogs = ScopeLogs.newBuilder()
                    .addLogRecords(LogRecord.newBuilder()
                            .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                            .setTimeUnixNano(System.currentTimeMillis() * 1000000)
                            .setBody(AnyValue.newBuilder().setStringValue(OTLP_CONNECTOR_STATE_TEST_LOG).build())
                            .build());
            ResourceLogs.Builder logsBuilder = ResourceLogs.newBuilder()
                    .setResource(Resource.newBuilder()
                            .addAllAttributes(getResourceAttributes())
                            .build())
                    .addScopeLogs(scopeLogs.build());
            try {
                LogsData.newBuilder().addResourceLogs(logsBuilder.build()).build()
                        .writeTo(outputBuffer.reset());
                transport.send(outputBuffer.getBuffer(), outputBuffer.getWriteOffset());
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public String getUnderlyingStorageResourceDescription() {
            return transport.description();
        }

        @Override
        public void logEvents(LogEvent[] logEvents, int eventsCount) {
            for (int i = 0; i < eventsCount; i++) {
                LogEvent le = logEvents[i];
                switch (le.getLogEventType()) {
                    case OUTGOING_MSG:
                        addToLoggingBatch(le.getLogTime(), OUTGOING_LOG, le.getMessageType(), le.getMessage());
                        break;
                    case INCOMING_MSG:
                        addToLoggingBatch(le.getLogTime(), INCOMING_LOG, le.getMessageType(), le.getMessage());
                        break;
                    case EVENT:
                    case EVENT_WITH_PARAMS:
                        addToLoggingBatch(le.getLogTime(), EVENT_LOG, null, le.getMessage());
                        break;
                }
            }
        }

        @Override
        public void logIncoming(UTCTime logTime, MessageType messageType, ByteBuffer message) {
            addToLoggingBatch(logTime, INCOMING_LOG, messageType, message);
        }

        @Override
        public void logOutgoing(UTCTime logTime, MessageType messageType, ByteBuffer message) {
            addToLoggingBatch(logTime, OUTGOING_LOG, messageType, message);
        }

        @Override
        public void logEvent(UTCTime eventTime, String event) {
            addToLoggingBatch(eventTime, EVENT_LOG, null, ByteBuffer.wrap(event.getBytes(SerDe.CHARSET)));
        }

        private void addToLoggingBatch(UTCTime logTime, KeyValue logType, MessageType messageType, ByteBuffer message) {
            PooledLogRecord pooledLogRecord = getPooledLogRecord();
            LogRecord.Builder logRecordBuilder = pooledLogRecord.getLogRecord();
            logRecordBuilder.setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_INFO)
                    .setTimeUnixNano(logTime.toEpochNanos())
                    .addAttributes(logType);
            pooledLogRecord.setBody(message, fieldDelimiterReplacementChar);
            if (messageType != null) {
                KeyValue messageTypeAttribute = messageTypeCache.get(messageType);
                if (messageTypeAttribute != null) {
                    logRecordBuilder.addAttributes(messageTypeAttribute);
                }
            }
            if (!batchedLogRecords.offer(pooledLogRecord)) {
                logsFlushingExecutorService.submit(this::flushLogs);
                batchedLogsFullIdleStrategy.reset();
                while (!batchedLogRecords.offer(pooledLogRecord)) {
                    batchedLogsFullIdleStrategy.idle();
                }
            }
        }

        private PooledLogRecord getPooledLogRecord() {
            PooledLogRecord logRecord = pooledLogRecords.poll();
            if (logRecord == null) {
                logRecord = new PooledLogRecord();
            }
            return logRecord;
        }

        private synchronized void flushLogs() {
            int batchedLogRecordsCapacity = batchedLogRecords.getCapacity();
            int processedLogRecords = 0;
            PooledLogRecord pooledLogRecord;
            while (processedLogRecords < batchedLogRecordsCapacity
                    && (pooledLogRecord = batchedLogRecords.poll()) != null) {
                scopeLogsBuilder.addLogRecords(pooledLogRecord.getLogRecord());
                pooledLogRecordsToRelease[processedLogRecords++] = pooledLogRecord;
            }
            if (scopeLogsBuilder.getLogRecordsList().isEmpty()) {
                return;
            }
            resourceLogsBuilder.addScopeLogs(scopeLogsBuilder);
            logsDataBuilder.addResourceLogs(resourceLogsBuilder);
            try {
                logsDataBuilder.build().writeTo(outputBuffer.reset());
                // important release all PooledLogRecord only when the message is fully serialized in outputBuffer
                for (int i = 0; i < processedLogRecords; i++) {
                    if (pooledLogRecordsToRelease[i].returnToPoolIfNeeded(pooledLogRecords)) {
                        cacheHitCount++;
                    }
                }
                transport.send(outputBuffer.getBuffer(), outputBuffer.getWriteOffset());
                totalLogsSent += processedLogRecords;
                if (log.isDebugEnabled()) {
                    log.debug("Flushing to OTLP endpoint {} log records, total {}", processedLogRecords, totalLogsSent);
                }
            } catch (IOException e) {
                log.error("Failed to send logs to OTLP endpoint", e);
            } finally {
                scopeLogsBuilder.clearLogRecords();
                resourceLogsBuilder.clearScopeLogs();
                logsDataBuilder.clearResourceLogs();
            }
        }

        @Override
        protected void stopMe(Deadline stopDeadline) throws StartStopException {
            scheduledFlush.cancel(false);
            flushLogs();
            transport.close();
            double hitRatio = totalLogsSent > 0 ? cacheHitCount * 100 / totalLogsSent : 100;
            log.info("OTLP logger for session {} stopped with a total of {} logs sent and a log buffer cache hit ratio of {}%", getFixSessionId(), totalLogsSent, hitRatio);
            totalLogsSent = cacheHitCount = 0;
            pooledLogRecords.clear();
            batchedLogRecords.clear();
        }

        @Override
        protected void startMe() throws StartStopException {
            scheduledFlush = logsFlushingExecutorService.scheduleAtFixedRate(this::flushLogs, calculateInitialDelay(), settings.getLogsFlushDelay().toMillis(), TimeUnit.MILLISECONDS);
            log.info("OTLP logger for session {} started with endpoint {}", getFixSessionId(), otlpEndpoint);
        }

        private long calculateInitialDelay() {
            long stepMillis = settings.getLogsFlushDelay().toMillis();
            return stepMillis - (System.currentTimeMillis() % stepMillis);
        }

        private Iterable<KeyValue> getResourceAttributes() {
            boolean serviceNameProvided = false;
            List<KeyValue> attributes = new ArrayList<>();
            attributes.add(createKeyValue(FixMonitoringConstants.OTLP_TELEMETRY_SDK_NAME, "staffix"));
            attributes.add(createKeyValue(FixMonitoringConstants.OTLP_TELEMETRY_SDK_LANGUAGE, "java"));
            attributes.add(createKeyValue(FixMonitoringConstants.OTLP_TELEMETRY_SDK_VERSION, "1.0.0"));
            for (Map.Entry<String, String> keyValue : this.settings.getResourceAttributes().entrySet()) {
                if (FixMonitoringConstants.OTLP_SERVICE_NAME.equals(keyValue.getKey())) {
                    serviceNameProvided = true;
                }
                attributes.add(createKeyValue(keyValue.getKey(), keyValue.getValue()));
            }
            if (!serviceNameProvided) {
                attributes.add(createKeyValue(FixMonitoringConstants.OTLP_SERVICE_NAME, fixInstanceId));
            }
            attributes.add(KeyValue.newBuilder()
                    .setKey(FixMonitoringAttributes.FIX_INSTANCE_ID)
                    .setValue(AnyValue.newBuilder().setStringValue(fixInstanceId)
                            .build()).build());
            attributes.add(KeyValue.newBuilder()
                    .setKey(FixMonitoringAttributes.FIX_SESSION_ID)
                    .setValue(AnyValue.newBuilder().setStringValue(getFixSessionId().getId())
                            .build()).build());
            attributes.add(KeyValue.newBuilder()
                    .setKey(FixMonitoringAttributes.FIX_SESSION_GROUP_ID)
                    .setValue(AnyValue.newBuilder().setStringValue(getFixSessionId().getGroup())
                            .build()).build());
            return attributes;
        }
    }

    @Slf4j
    @Getter
    private static class PooledLogRecord {

        private static final AnyValue VOID_BODY = AnyValue.newBuilder().build();

        private final LogRecord.Builder logRecord;
        private final PooledBytesString pooledBytesString;
        private final AnyValue.Builder anyValueBuilder;
        private boolean bytesBufferCacheHit;

        PooledLogRecord() {
            this.logRecord = LogRecord.newBuilder();
            this.anyValueBuilder = AnyValue.newBuilder();
            this.pooledBytesString = null;
        }

        PooledLogRecord(int pooledByteBufferSize) {
            this.logRecord = LogRecord.newBuilder();
            this.anyValueBuilder = AnyValue.newBuilder();
            this.pooledBytesString = new PooledBytesString(pooledByteBufferSize);
        }

        void setBody(ByteBuffer message, byte fieldDelimiterReplacementChar) {
            if (pooledBytesString != null) {
                bytesBufferCacheHit = pooledBytesString.setBytes(message, CoreFields.FIELD_SEPARATOR_BYTE, fieldDelimiterReplacementChar);
                //this is allocating one object each time build is called, no other choice unfortunatley
                logRecord.setBody(anyValueBuilder.clear().setStringValueBytes(pooledBytesString).build());
                return;
            }
            bytesBufferCacheHit = false;
            logRecord.setBody(anyValueBuilder.clear().setStringValueBytes(ByteString.copyFrom(message)).build());
        }

        boolean returnToPoolIfNeeded(RingBuffer<PooledLogRecord> logRecordPool) {
            if (pooledBytesString != null) {
                // calling logRecord.clear() is very bad for memory allocation especially when removing attributes
                for (int i = 0; i < logRecord.getAttributesCount(); i++) {
                    logRecord.removeAttributes(i);
                }
                logRecord.setBody(VOID_BODY); // logRecord.clearBody() is also allocating memory
                logRecord.clearSeverityNumber();
                logRecord.clearTimeUnixNano();
                if (!logRecordPool.offer(this)) {
                    log.info("Abnormal situation, unable to return PooledLogRecord to the pool");
                }
            }
            return bytesBufferCacheHit;
        }
    }

    public static class OtlpMessagesLoggerFactoryImpl implements FixMessagesLoggerSettings.FixMessagesLoggerFactory<OtlpMessagesLoggerSettings> {

        @Override
        public FixMessagesLogger newInstance(OtlpMessagesLoggerSettings settings) {
            return new OtlpMessagesLogger(settings);
        }

        @Override
        public Class<OtlpMessagesLoggerSettings> getSettingsClass() {
            return OtlpMessagesLoggerSettings.class;
        }
    }
}