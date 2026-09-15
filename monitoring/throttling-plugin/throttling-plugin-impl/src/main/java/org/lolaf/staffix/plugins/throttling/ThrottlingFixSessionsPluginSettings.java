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
package org.lolaf.staffix.plugins.throttling;

import lombok.Builder;
import lombok.Getter;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

import java.time.Duration;

/**
 * Settings for {@link ThrottlingFixSessionsPlugin}, the wrapper that rate-limits (samples) another
 * plugin's per-message callbacks so a delegate is not overwhelmed by high message throughput.
 *
 * <p>Register this <em>instead of</em> the wrapped plugin's own settings on the engine builder, passing
 * the wrapped plugin's settings as {@link #delegateSettings}. The wrapper is transparent to session
 * configuration: it reports the delegate's {@code instanceId} and matches the delegate's plugin class, so
 * existing {@code fixSessionPluginsInstanceId(DelegatePlugin.class, id)} references keep working unchanged.
 *
 * <p>Throttling is applied per session and independently per direction:
 * <ul>
 *   <li>the inbound callbacks ({@code onMessageDecodingStarted} / {@code onMessageDecodingFinished} /
 *       {@code onMessageReceived}) are admitted or dropped as a group, capped at {@link #maxReceivedMessages}
 *       per {@link #window};</li>
 *   <li>the I/O-thread encoding callbacks ({@code onMessageEncodedBody} / {@code onMessageEncodingFinished})
 *       are admitted or dropped as a group; {@code onMessageEncodingStarted} (which runs on the producing
 *       application thread, not the I/O thread) and {@code onMessageSent} are each throttled independently —
 *       all capped at {@link #maxSentMessages} per {@link #window}.</li>
 * </ul>
 *
 * <p>A max of {@code 0} (or negative) disables throttling for that direction, so all of its callbacks are
 * forwarded.
 *
 * <pre>{@code
 * .fixSessionsPlugin(ThrottlingFixSessionsPluginSettings.builder()
 *         .delegateSettings(MicrometerMonitoringManagerSettings.builder().build())
 *         .maxReceivedMessages(1000)
 *         .maxSentMessages(1000)
 *         .window(Duration.ofSeconds(1))
 *         .build())
 * }</pre>
 */
@Getter
@Builder(toBuilder = true)
public class ThrottlingFixSessionsPluginSettings implements FixSessionsPluginSettings<ThrottlingFixSessionsPlugin<?>> {

    /**
     * Settings of the plugin being wrapped. Required.
     */
    private final FixSessionsPluginSettings<?> delegateSettings;

    /**
     * Maximum number of inbound messages whose callbacks are forwarded to the delegate per {@link #window}.
     * {@code 0} or negative disables inbound throttling. Defaults to {@code 0} (unthrottled).
     */
    @Builder.Default
    private final int maxReceivedMessages = 0;

    /**
     * Maximum number of outbound messages whose callbacks are forwarded to the delegate per {@link #window},
     * applied independently to the encoding callbacks and to {@code onMessageSent}. {@code 0} or negative
     * disables outbound throttling. Defaults to {@code 0} (unthrottled).
     */
    @Builder.Default
    private final int maxSentMessages = 0;

    /**
     * The rolling time window over which {@link #maxReceivedMessages} and {@link #maxSentMessages} are
     * counted. Defaults to one second.
     */
    @Builder.Default
    private final Duration window = Duration.ofSeconds(1);

    /**
     * The wrapper is transparent to session configuration and always reports the wrapped plugin's
     * instance id, so a session that referenced the delegate keeps matching through the wrapper.
     */
    @Override
    public String getInstanceId() {
        return delegateSettings.getInstanceId();
    }

    public static class ThrottlingFixSessionsPluginFactoryImpl
            implements FixSessionsPluginFactory<ThrottlingFixSessionsPluginSettings> {

        @Override
        public Class<ThrottlingFixSessionsPluginSettings> getSettingsClass() {
            return ThrottlingFixSessionsPluginSettings.class;
        }

        @Override
        public FixSessionsPlugin<?> newInstance(ThrottlingFixSessionsPluginSettings settings) {
            return new ThrottlingFixSessionsPlugin<>(settings);
        }
    }
}
