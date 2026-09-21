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
package org.lolaf.staffix.spring.boot;

import org.lolaf.staffix.api.FixEngine;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.admin.AdminApiExporterSettings;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.application.factories.spring.SpringApplicationFactorySettings;
import org.lolaf.staffix.spring.boot.props.StaffixProperties;
import org.lolaf.staffix.spring.boot.spi.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * Assembles the engine itself, gathering the stores, loggers, plugins and admin APIs each integration module
 * contributed through the SPI.
 *
 * <p>Which contributors are present is decided by what is on the classpath, which is how adding a module is all
 * it takes to make its store or logger nameable from configuration.
 */
@Configuration(proxyBeanMethods = false)
public class StaffixEngineConfiguration {

    @Bean
    public ManagedFixEngine staffixManagedFixEngine(StaffixProperties props,
                                                    ApplicationContext ctx,
                                                    SpringApplicationFactorySettings springApplicationFactorySettings,
                                                    ObjectProvider<FixSessionsSettingsStoreSettingsContributor> sessionsSettingsStoreContributors,
                                                    ObjectProvider<FixMessagesLoggerSettingsContributor> loggerContributors,
                                                    ObjectProvider<FixMessagesStoreSettingsContributor> storeContributors,
                                                    ObjectProvider<FixSessionsPluginSettingsContributor> pluginContributors,
                                                    ObjectProvider<AdminApiExporterSettingsContributor> adminContributors,
                                                    ObjectProvider<FixSessionSettingsPostProcessor> sessionSettingsPostProcessors) {

        FixEngineBuilder.FixEngineBuilderBuilder<?, ?> b = FixEngineBuilder.builder().instanceId(props.getEngine().getInstanceId());

        BeanRef.<ExecutorService>resolveOptional(ctx, props.getEngine().getDisconnectedSessionsExecutorBean(),
                        ExecutorService.class, "staffix.engine.disconnected-sessions-executor-bean")
                .ifPresent(b::disconnectedSessionsExecutor);

        List<AdminApiExporterSettings> collectedAdmin = new ArrayList<>();
        adminContributors.orderedStream()
                .sorted(Comparator.comparingInt(AdminApiExporterSettingsContributor::order))
                .forEach(c -> c.contribute(collectedAdmin::add));
        if (collectedAdmin.size() > 1) {
            throw new IllegalStateException("Multiple admin API contributors registered (" + collectedAdmin.size()
                    + "); FixEngine accepts at most one. Disable all but one.");
        }
        if (!collectedAdmin.isEmpty()) {
            b.adminApiExporter(collectedAdmin.get(0));
        }

        b.fixApplicationFactory(springApplicationFactorySettings);

        Map<String, FixSessionsSettingsStoreSettings> sessionsSettingsStores = new LinkedHashMap<>();
        sessionsSettingsStoreContributors.orderedStream()
                .sorted(Comparator.comparingInt(FixSessionsSettingsStoreSettingsContributor::order))
                .forEach(c -> c.contribute(sessionsSettingsStores));

        List<FixSessionSettingsPostProcessor> orderedPostProcessors = sessionSettingsPostProcessors.orderedStream()
                .sorted(Comparator.comparingInt(FixSessionSettingsPostProcessor::order))
                .toList();
        sessionsSettingsStores.values().forEach(s -> {
            FixSessionsSettingsStoreSettings wrapped = orderedPostProcessors.isEmpty()
                    ? s : new PostProcessingSessionsSettingsStore.Settings(s, orderedPostProcessors);
            b.fixSessionsSettingsStore(wrapped);
        });

        Map<String, FixMessagesStoreSettings> stores = new LinkedHashMap<>();
        storeContributors.orderedStream()
                .sorted(Comparator.comparingInt(FixMessagesStoreSettingsContributor::order))
                .forEach(c -> c.contribute(stores));
        stores.values().forEach(b::fixMessagesStore);

        Map<String, FixMessagesLoggerSettings> loggers = new LinkedHashMap<>();
        loggerContributors.orderedStream()
                .sorted(Comparator.comparingInt(FixMessagesLoggerSettingsContributor::order))
                .forEach(c -> c.contribute(loggers));
        loggers.values().forEach(b::fixMessagesLogger);

        Map<String, FixSessionsPluginSettings<?>> plugins = new LinkedHashMap<>();
        pluginContributors.orderedStream()
                .sorted(Comparator.comparingInt(FixSessionsPluginSettingsContributor::order))
                .forEach(c -> c.contribute(plugins));

        plugins.values().forEach(b::fixSessionsPlugin);

        return new ManagedFixEngine(b.build().instance().start(), Duration.ofSeconds(20));
    }

    @Bean
    public FixEngine fixEngine(ManagedFixEngine managed) {
        return managed.getEngine();
    }
}
