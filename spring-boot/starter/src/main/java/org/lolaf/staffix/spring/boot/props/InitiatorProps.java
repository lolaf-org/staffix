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
import org.lolaf.staffix.spring.boot.spi.FixSessionIdProps;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * One initiator in configuration: the session to run, the addresses to dial, and the transport settings.
 */
@Data
public class InitiatorProps {

    /**
     * Names this initiator. Defaults to the key it is declared under.
     */
    private String instanceId;

    /**
     * Bean name of a ScheduledExecutorService for this initiator's timers. Omit to use the engine's.
     */
    private String schedulerBean;

    /**
     * The session to run - FIX version and CompIDs.
     */
    @NestedConfigurationProperty
    private FixSessionIdProps fixSessionId;

    /**
     * Addresses to dial, as host:port. Several are tried in turn, which is how a counterparty's failover endpoints
     * are given.
     */
    private List<String> connectAddresses = new ArrayList<>();

    /**
     * TLS for the outbound connection. Omit to connect in clear.
     */
    @NestedConfigurationProperty
    private SslProps ssl;

    /**
     * How long to wait between connection attempts, and between one address and the next.
     */
    private Duration connectionRetry = Duration.ofSeconds(5);

    /**
     * IO workers for this connection.
     */
    @NestedConfigurationProperty
    private IoWorkersGroupProps ioWorkers;

    /**
     * Processes messages off the IO thread. Leave at its defaults to keep them on it, which is the lowest latency.
     */
    @NestedConfigurationProperty
    private MessageExecutorProps messageExecutor = new MessageExecutorProps();

    /**
     * How long a stop waits for the session to log out before the socket is closed regardless.
     */
    private Duration shutdownMaxDelay = Duration.ofSeconds(20);

    /**
     * Socket-level settings for this connection.
     */
    @NestedConfigurationProperty
    private IoSettingsProps ioSettings;
}
