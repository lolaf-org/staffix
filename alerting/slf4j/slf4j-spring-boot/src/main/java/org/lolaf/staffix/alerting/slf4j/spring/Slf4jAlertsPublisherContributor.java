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
package org.lolaf.staffix.alerting.slf4j.spring;

import org.lolaf.staffix.alerting.slf4j.Slf4jAlertsPublisherSettings;
import org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings;
import org.lolaf.staffix.api.alerting.ProbeScope;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixAlertsPublisherSettingsContributor;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.function.Function;

/**
 * Contributes the SLF4J alerts publisher's settings to the engine being built, so a session can name it in
 * configuration rather than the application wiring it.
 */
public class Slf4jAlertsPublisherContributor implements FixAlertsPublisherSettingsContributor {

    private final Slf4jAlertsPublisherProps props;
    private final ApplicationContext ctx;

    public Slf4jAlertsPublisherContributor(Slf4jAlertsPublisherProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
    }

    @Override
    public void contribute(Map<String, FixAlertsPublisherSettings> registry) {
        props.getInstances().forEach((key, p) -> {
            if (registry.putIfAbsent(key, build(key, p)) != null) {
                throw new IllegalStateException("staffix.alerts-publishers-slf4j.instances." + key
                        + " collides with another alerts publisher contributor for the same key");
            }
        });
    }

    private FixAlertsPublisherSettings build(String mapKey, Slf4jAlertsPublisherEntryProps p) {
        Slf4jAlertsPublisherSettings.Slf4jAlertsPublisherSettingsBuilder<?, ?> b = Slf4jAlertsPublisherSettings.builder()
                .instanceId(mapKey);
        if (p.getUseMDC() != null) b.useMDC(p.getUseMDC());
        BeanRef.<Function<ProbeScope, String>>resolveOptional(ctx, p.getLoggerNameForScopeBean(), Function.class,
                        "staffix.alerts-publishers-slf4j.instances." + mapKey + ".logger-name-for-scope-bean")
                .ifPresent(b::loggerNameForScope);
        return b.build();
    }
}
