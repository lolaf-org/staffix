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
package org.lolaf.staffix.plugins.throttling.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.Duration;

/**
 * One configured instance of the throttling session plugin wrapper: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class ThrottlingPluginEntryProps {
    /**
     * Key into any other sessions-plugin contributor (e.g. an entry under
     * {@code staffix.monitoring-micrometer.instances} or {@code staffix.tracing.otel.instances}). The
     * throttling wrapper replaces the wrapped entry in the registry; the wrapped plugin's
     * {@code instance-id} stays in effect, so existing session references keep matching through the wrapper.
     */
    private String wraps;

    /**
     * Max inbound messages forwarded to the delegate per {@link #window}. {@code 0} disables inbound throttling.
     */
    private Integer maxReceivedMessages;

    /**
     * Max outbound messages forwarded to the delegate per {@link #window}. {@code 0} disables outbound throttling.
     */
    private Integer maxSentMessages;

    /**
     * Rolling window over which the max counts are measured.
     */
    private Duration window;
}
