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
package org.lolaf.staffix.spring.boot.props;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One acceptor in configuration: where to listen, and the transport settings. The sessions it serves come from
 * the session settings store, not from here.
 */
@Data
public class AcceptorProps {

    /**
     * Names this acceptor. Defaults to the key it is declared under.
     */
    private String instanceId;

    /**
     * Bean name of a ScheduledExecutorService for this acceptor's timers. Omit to use the engine's.
     */
    private String schedulerBean;

    /**
     * Where to listen, as host:port. Omit the host to bind every interface.
     */
    private String bindAddress;

    /**
     * Which session settings stores this acceptor serves sessions from. Empty means all of them.
     */
    private List<String> targetSessionsSettingsStoreInstanceIds = new ArrayList<>();

    /**
     * TLS for inbound connections. Omit to accept them in clear.
     */
    @NestedConfigurationProperty
    private SslProps.ServerSslProps ssl;

    /**
     * IO workers that accept connections. Omit to share the ones that serve them.
     */
    @NestedConfigurationProperty
    private IoWorkersGroupProps acceptorIoWorkers;

    /**
     * IO workers that serve accepted connections.
     */
    @NestedConfigurationProperty
    private IoWorkersGroupProps ioWorkers;

    /**
     * Processes messages off the IO thread. Leave at its defaults to keep them on it, which is the lowest latency.
     */
    @NestedConfigurationProperty
    private MessageExecutorProps messageExecutor = new MessageExecutorProps();

    /**
     * How long a stop waits for sessions to log out before the sockets are closed regardless.
     */
    private Duration shutdownMaxDelay = Duration.ofSeconds(20);

    /**
     * Socket-level settings for accepted connections.
     */
    @NestedConfigurationProperty
    private IoSettingsProps ioSettings;
}
