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
import org.lolaf.staffix.api.time.Clock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Builds a {@link FixInitiator}: the session to run and the addresses to dial for it, the sessions it can be
 * {@link FixInitiator#switchTo switched} to, and the transport settings underneath.
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
     * The session the initiator runs when it starts, which must have initiator settings in one of the engine's
     * {@link org.lolaf.staffix.api.session.FixSessionsSettingsStore}s.
     */
    private final FixInitiatorTarget mainTarget;

    /**
     * Sessions this initiator can be switched to; it never moves to one on its own. Session ids must differ from
     * each other and from the main target's: the same session on another IP is another address, not another target.
     */
    @Singular
    private final List<FixInitiatorTarget> backupTargets;
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

    /**
     * The main target followed by the backup targets.
     */
    public List<FixInitiatorTarget> getTargets() {
        List<FixInitiatorTarget> targets = new ArrayList<>(backupTargets.size() + 1);
        targets.add(mainTarget);
        targets.addAll(backupTargets);
        return targets;
    }
}