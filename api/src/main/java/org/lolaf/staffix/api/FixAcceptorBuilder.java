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
import org.lolaf.betty.api.settings.ServerSSLSettings;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.api.time.Clock;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds a {@link FixAcceptor}: where to listen, and the transport settings underneath.
 *
 * <p>The sessions it will serve are not declared here - they come from the engine's session settings stores,
 * matched by the CompIDs a counterparty logs on with.
 */
@Getter
@SuperBuilder(toBuilder = true)
public class FixAcceptorBuilder {
    /**
     * Names this acceptor, so several can run in one engine and be told apart.
     */
    @Builder.Default
    private final String instanceId = InstanceProvider.DEFAULT_INSTANCE_ID;
    /**
     * TLS for inbound connections. Null accepts them in clear.
     */
    private final ServerSSLSettings serverSSLSettings;
    /**
     * Where to listen.
     */
    private final InetSocketAddress bindAddress;
    /**
     * Scheduler for session heartbeat scheduling, scheduled management and logon validations tasks, if none provided an embedded scheduler will be used
     */
    private final ScheduledExecutorService scheduledExecutorService;
    /**
     * IO worker group for accepting connections, if null {@link FixAcceptorBuilder#getIoWorkersGroup()} will be used to accept connections.
     * It is encouraged to define one when using SSL (as SSL handshake will be processed into an IO worker) or constant low latency is desired to process IO read/writes
     */
    private final IOWorkersGroup acceptorIoWorkerGroup;
    /**
     * IO worker group for processing connections reads/writes, if null a default one with one thread will be created
     */
    private final IOWorkersGroup ioWorkersGroup;
    /**
     * IO settings for each socket established for a fix session
     */
    @Builder.Default
    private final IOSettings ioSettings = IOSettings.builder().build();

    /**
     * Max delay to wait for an orderly shutdown when calling @{link {@link FixAcceptor#stop()}}
     */
    @Builder.Default
    private final Duration shutdownMaxDelay = Duration.ofSeconds(20);
    /**
     * Told when a session is accepted or refused, and when a TLS handshake fails - the only place a connection
     * that never became a session is visible.
     */
    @Builder.Default
    private final FixAcceptor.FixSessionEventsListener fixSessionEventsListener = new FixAcceptor.FixSessionEventsListener() {
    };

    /**
     * A list of {@link FixEngineBuilder#getFixSessionsSettingsStores()} supported instances ids ({@link FixSessionsSettingsStoreSettings#getInstanceId()}),
     * all sessions defined into those target stores will be added to the FIX acceptor
     * If no target instance id define, it will try to find the first store with instance id "default"
     */
    @Singular
    private List<String> targetFixSessionsSettingsStoreInstancesIds;
    /**
     * Processes messages off the IO thread. Null keeps them on it, which is the lowest latency and the right
     * default unless the application does real work per message.
     */
    @Builder.Default
    private MessageExecutorSettings messageExecutorSettings = MessageExecutorSettings.builder().build();

    /**
     * Clock of the acceptor, will use system default if none provided
     */
    private Clock clock;

}