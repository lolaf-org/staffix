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

import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.plugins.throttling.ThrottlingFixSessionsPluginSettings;
import org.lolaf.staffix.spring.boot.spi.FixSessionsPluginSettingsContributor;

import java.util.Map;

/**
 * Wraps already-contributed sessions plugins with {@link ThrottlingFixSessionsPluginSettings}. Runs after
 * the simple (default-order) plugin contributors so that the {@code wraps} key resolves against an entry
 * they have already registered, then replaces that entry in-place under the same key.
 */
public class ThrottlingPluginContributor implements FixSessionsPluginSettingsContributor {

    private final ThrottlingPluginProps props;

    public ThrottlingPluginContributor(ThrottlingPluginProps props) {
        this.props = props;
    }

    @Override
    public int order() {
        // After the default (0) simple plugin contributors, so their entries exist to be wrapped.
        return 100;
    }

    @Override
    public void contribute(Map<String, FixSessionsPluginSettings<?>> registry) {
        props.getInstances().forEach((key, p) -> {
            if (p.getWraps() == null) {
                throw new IllegalArgumentException("staffix.throttling-plugin.instances." + key + ".wraps is required");
            }
            FixSessionsPluginSettings<?> wrapped = registry.get(p.getWraps());
            if (wrapped == null) {
                throw new IllegalArgumentException("staffix.throttling-plugin.instances." + key + ".wraps='" + p.getWraps()
                        + "' does not reference any sessions plugin contributed by another module");
            }

            ThrottlingFixSessionsPluginSettings.ThrottlingFixSessionsPluginSettingsBuilder b =
                    ThrottlingFixSessionsPluginSettings.builder().delegateSettings(wrapped);
            if (p.getMaxReceivedMessages() != null) b.maxReceivedMessages(p.getMaxReceivedMessages());
            if (p.getMaxSentMessages() != null) b.maxSentMessages(p.getMaxSentMessages());
            if (p.getWindow() != null) b.window(p.getWindow());

            // Wrapper replaces the wrapped entry in-place: the throttling settings report the delegate's
            // instance id (unless overridden), so the registry key stays stable.
            registry.put(p.getWraps(), b.build());
        });
    }
}
