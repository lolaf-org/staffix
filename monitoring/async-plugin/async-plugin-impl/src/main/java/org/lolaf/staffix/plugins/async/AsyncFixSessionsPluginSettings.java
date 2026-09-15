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
package org.lolaf.staffix.plugins.async;

import lombok.Builder;
import lombok.Getter;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.idling.TimerSlackAwareBackoffIdleStrategy;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

import java.util.function.Supplier;

/**
 * Settings for {@link AsyncFixSessionsPlugin}, the wrapper that runs another plugin's fire-and-forget
 * callbacks on a dedicated consumer thread pool off the latency-critical path.
 *
 * <p>Register this <em>instead of</em> the wrapped plugin's own settings on the engine builder, passing
 * the wrapped plugin's settings as {@link #delegateSettings}. The wrapper is transparent to session
 * configuration: it reports the delegate's {@code instanceId} and matches the delegate's plugin class,
 * so existing {@code fixSessionPluginsInstanceId(DelegatePlugin.class, id)} references keep working
 * unchanged.
 *
 * <pre>{@code
 * .fixSessionsPlugin(AsyncFixSessionsPluginSettings.builder()
 *         .delegateSettings(MicrometerMonitoringManagerSettings.builder().build())
 *         .queueSize(2048)
 *         .build())
 * }</pre>
 */
@Getter
@Builder(toBuilder = true)
public class AsyncFixSessionsPluginSettings implements FixSessionsPluginSettings<AsyncFixSessionsPlugin<?>> {

    /**
     * Settings of the plugin being wrapped. Required.
     */
    private final FixSessionsPluginSettings<?> delegateSettings;

    /**
     * Number of consumer threads draining per-session queues, round-robin-assigned. Defaults to {@code 1}.
     */
    @Builder.Default
    private final int consumerThreadPoolSize = 1;

    /**
     * Capacity of each per-session ring buffer. Must be a power of two. Defaults to {@code 1024}.
     */
    @Builder.Default
    private final int queueSize = 1024;

    /**
     * Behaviour when a session queue is full. Defaults to {@link BackpressurePolicy#DROP}.
     */
    @Builder.Default
    private final BackpressurePolicy backpressurePolicy = BackpressurePolicy.DROP;

    /**
     * Idle strategy for the consumer threads.
     */
    @Builder.Default
    private final Supplier<IdleStrategy> consumerIdleStrategySupplier = TimerSlackAwareBackoffIdleStrategy::new;

    /**
     * Idle strategy used by a producing thread to wait for a free slot. Used for every publish under the
     * {@link BackpressurePolicy#BLOCK} policy, and always for the terminal {@code onSessionDestroyed}
     * event (which must never be dropped). Defaults to a busy-spin strategy.
     */
    @Builder.Default
    private final Supplier<IdleStrategy> producerIdleStrategySupplier = TimerSlackAwareBackoffIdleStrategy::new;

    /**
     * The wrapper is transparent to session configuration and always reports the wrapped plugin's
     * instance id, so a session that referenced the delegate keeps matching through the wrapper.
     */
    @Override
    public String getInstanceId() {
        return delegateSettings.getInstanceId();
    }

    public static class AsyncFixSessionsPluginFactoryImpl implements FixSessionsPluginFactory<AsyncFixSessionsPluginSettings> {

        @Override
        public Class<AsyncFixSessionsPluginSettings> getSettingsClass() {
            return AsyncFixSessionsPluginSettings.class;
        }

        @Override
        public FixSessionsPlugin<?> newInstance(AsyncFixSessionsPluginSettings settings) {
            return new AsyncFixSessionsPlugin<>(settings);
        }
    }
}
