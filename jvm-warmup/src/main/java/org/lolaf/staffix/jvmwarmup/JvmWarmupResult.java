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
package org.lolaf.staffix.jvmwarmup;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

/**
 * Outcome of a warmup run, the value the {@link JvmWarmup#run(JvmWarmupOptions)} future completes with.
 */
@Value
@Builder
public class JvmWarmupResult {

    /**
     * Total {@code JVMWarmup} messages the initiator sent.
     */
    long messagesSent;

    /**
     * Total {@code JVMWarmup} messages the initiator received back from the acceptor.
     */
    long messagesReceived;

    /**
     * Wall-clock time between session-logon and shutdown.
     */
    Duration actualDuration;

    /**
     * True when the run finished early because {@link JvmWarmup.WarmupFuture#cancel(boolean)} was invoked.
     */
    boolean cancelled;
}
