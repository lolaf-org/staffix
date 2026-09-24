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
package org.lolaf.staffix.examples;

import lombok.Data;
import org.lolaf.staffix.api.http.HttpVersion;
import picocli.CommandLine;

@Data
public class ExampleOptions {

    @CommandLine.Option(names = "-d", description = "Duration in seconds of the example run", defaultValue = "120")
    private long duration;
    @CommandLine.Option(names = "-c", description = "Number of fix clients running the example", defaultValue = "2")
    private int clientsCount;
    @CommandLine.Option(names = "-ll", description = "Enabling low latency settings with busy wait loops", defaultValue = "false")
    private boolean lowLatency;
    @CommandLine.Option(names = "-ca", description = "Enabling cpu affinity", defaultValue = "false")
    private boolean cpuAffinity;
    @CommandLine.Option(names = "-cas", description = "Cpu affinity start cpu", defaultValue = "0")
    private int cpuAffinityStartCpu;
    @CommandLine.Option(names = "-em", description = "Enabled monitoring with OTLP and micrometer", defaultValue = "false")
    private boolean enableMonitoring;
    @CommandLine.Option(names = "-am", description = "Metrics are processed asynchronously", defaultValue = "false")
    private boolean asyncMonitoring;
    @CommandLine.Option(names = "-tc", description = "Throttle cap: max monitoring callbacks per window for BOTH incoming and outgoing messages (requires -em). 0 disables throttling", defaultValue = "0")
    private int throttleCap;
    @CommandLine.Option(names = "-tw", description = "Throttle window in milliseconds over which -tc is counted", defaultValue = "1000")
    private long throttleWindowMillis;
    @CommandLine.Option(names = "-et", description = "Enabled tracing with OTLP and micrometer", defaultValue = "false")
    private boolean enableTracing;
    @CommandLine.Option(names = "-otr", description = "OTLP transport for logs and traces: HTTP or GRPC. "
            + "Metrics stay on HTTP either way - micrometer's OTLP registry has no gRPC sender to plug into. "
            + "GRPC costs more per publish than HTTP and buys nothing but reach; pick it when the collector "
            + "offers no HTTP ingress", defaultValue = "HTTP")
    private OtlpTransport otlpTransport;
    @CommandLine.Option(names = "-ohv", description = "HTTP version the OTLP HTTP senders speak: HTTP_1_1 or "
            + "HTTP_2. On cleartext HTTP_2 is prior knowledge, so it fails against an HTTP/1.1 collector rather "
            + "than degrading to one, and it costs 1.17-1.51x the allocation", defaultValue = "HTTP_1_1")
    private HttpVersion otlpHttpVersion;
    @CommandLine.Option(names = "-oe", description = "Monitoring, traces, logs OTLP endpoint url", defaultValue = "http://localhost:4318")
    private String otlpEndpointUrl;
    @CommandLine.Option(names = "-oa", description = "OTLP endpoint auth header", defaultValue = "")
    private String monitoringAuthHeader;
    @CommandLine.Option(names = "-as", description = "Enabling asynchronous messages store", defaultValue = "false")
    private boolean asyncMessagesStore;
    @CommandLine.Option(names = "-st", description = "Fix messages store type: VOID,MEMORY,FILE,JDBC", defaultValue = "VOID")
    private FixMessageStoreType fixMessageStoreType;
    @CommandLine.Option(names = "-al", description = "Enabling asynchronous messages logger", defaultValue = "false")
    private boolean asyncMessagesLogger;
    @CommandLine.Option(names = "-lt", description = "Fix messages logger type: VOID,SLF4j,FILE,OTLP", defaultValue = "FILE")
    private FixMessageLoggerType fixMessageLoggerType;

    public enum OtlpTransport {
        /**
         * OTLP/HTTP with a protobuf body, which every collector accepts.
         */
        HTTP,
        /**
         * OTLP/gRPC, which needs a client that can read HTTP/2 trailers.
         */
        GRPC
    }

    public enum FixMessageStoreType {
        VOID,
        MEMORY,
        FILE,
        JDBC
    }

    public enum FixMessageLoggerType {
        VOID,
        SLF4J,
        FILE,
        OTLP
    }
}