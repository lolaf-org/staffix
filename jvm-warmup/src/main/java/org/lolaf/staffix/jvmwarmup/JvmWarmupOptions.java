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
import lombok.Getter;
import org.lolaf.staffix.api.FixEngineBuilder;

import java.time.Duration;

/**
 * Configuration for {@link JvmWarmup#run(JvmWarmupOptions)}.
 *
 * <p>A zero-arg builder ({@code JvmWarmupOptions.builder().build()}) is enough for a typical warmup:
 * a 5-minute run at 100 msgs/s over a loopback FIX session, using the wait/notify idle strategy so
 * the warmup doesn't pin a core on startup.
 */
@Getter
@Builder(toBuilder = true)
public final class JvmWarmupOptions {

    /**
     * How long the warmup keeps traffic flowing before tear-down.
     */
    @Builder.Default
    private final Duration warmupDuration = Duration.ofMinutes(5);

    /**
     * Inter-message interval on the initiator side. Default is 10ms ≈ 100 msgs/s, which is enough
     * to drive JIT thresholds without saturating a core for a minute. Set to {@link Duration#ZERO}
     * to send as fast as the session can flush (no wheel-timer scheduling).
     */
    @Builder.Default
    private final Duration throttling = Duration.ofMillis(10);

    /**
     * Loopback port the in-process acceptor binds and the initiator connects to.
     */
    @Builder.Default
    private final int port = 7099;

    /**
     * Selects the IO worker and message executor idle strategy. {@code false} uses
     * {@code WaitNotifyIdleStrategy} (good citizen — warmup runs once at startup and shouldn't pin
     * a core for a minute). Flip to {@code true} to also warm the busy-spin path.
     */
    @Builder.Default
    private final boolean lowLatency = false;

    /**
     * Acceptor instance ID; surfaces in logs and JMX names.
     */
    @Builder.Default
    private final String acceptorInstanceId = "jvm-warmup-acceptor";

    /**
     * Initiator instance ID; surfaces in logs and JMX names.
     */
    @Builder.Default
    private final String initiatorInstanceId = "jvm-warmup-initiator";

    /**
     * FIX SenderCompID used by the initiator (and TargetCompID of the acceptor).
     */
    @Builder.Default
    private final String sender = "JVM-WARMUP-INITIATOR";

    /**
     * FIX TargetCompID used by the initiator (and SenderCompID of the acceptor).
     */
    @Builder.Default
    private final String target = "JVM-WARMUP-ACCEPTOR";

    /**
     * When {@code true}, warmup sessions use the FIXT 1.1 transport layer instead of a regular
     * FIX 4.4 session. The application-level dictionary remains the same ({@code jvm-warmup}).
     * Use this to JIT-warm the FIXT codec path.
     */
    @Builder.Default
    private final boolean fixt = false;

    /**
     * Optional engine builder whose store/logger/plugin settings should be exercised during warmup.
     * When provided, the warmup clones the builder and creates fresh instances of the same
     * store, logger and plugin types, ensuring those code paths are JIT-warmed.
     * When null, the warmup uses a void MemoryMessageStore and disabled Slf4jMessagesLogger.
     */
    @Builder.Default
    private final FixEngineBuilder fixEngineBuilder = null;
}
