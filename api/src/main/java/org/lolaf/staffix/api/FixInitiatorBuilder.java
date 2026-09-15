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
package org.lolaf.staffix.api;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.betty.api.settings.SSLSettings;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.Clock;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds a {@link FixInitiator}: the session to run, the addresses to dial, and the transport settings underneath.
 *
 * <p>{@code connectAddresses} is a collection because a counterparty commonly publishes more than one endpoint;
 * they are tried in turn, {@code connectionRetry} apart, until one accepts.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class FixInitiatorBuilder {
    /**
     * Names this initiator, so several can run in one engine and be told apart.
     */
    @Builder.Default
    private final String instanceId = InstanceProvider.DEFAULT_INSTANCE_ID;

    /**
     * Target FixSessionId defined in one of the {@link org.lolaf.staffix.api.FixEngineBuilder} configured {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore}
     */
    private final FixSessionId fixSessionId;

    /**
     * A list to addresses to try to connect to
     */
    @Singular
    private final Collection<InetSocketAddress> connectAddresses;
    /**
     * Scheduler for session heartbeat scheduling, scheduled management and logon validations tasks, if none provided an embedded scheduler will be used
     */
    private final ScheduledExecutorService scheduledExecutorService;
    /**
     * Setting to enable secure connection with remote server
     */
    private final SSLSettings sslSettings;
    /**
     * How long to wait between connection attempts, and between one address and the next.
     */
    @Builder.Default
    private final Duration connectionRetry = Duration.ofSeconds(5);
    /**
     * IO settings for each socket established
     */
    @Builder.Default
    private final IOSettings ioSettings = IOSettings.builder().build();

    /**
     * Max delay to wait for an orderly shutdown when calling @{link {@link FixInitiator#stop()}}
     */
    @Builder.Default
    private final Duration shutdownMaxDelay = Duration.ofSeconds(20);

    /**
     * IO worker group for processing connections reads/writes, if null a default one with one thread will be created
     */
    private final IOWorkersGroup ioWorkersGroup;
    /**
     * Processes messages off the IO thread. Null keeps them on it, which is the lowest latency and the right
     * default unless the application does real work per message.
     */
    @Builder.Default
    private MessageExecutorSettings messageExecutorSettings = MessageExecutorSettings.builder().build();

    /**
     * Clock of the initiator, will use system default if none provided
     */
    private Clock clock;

}