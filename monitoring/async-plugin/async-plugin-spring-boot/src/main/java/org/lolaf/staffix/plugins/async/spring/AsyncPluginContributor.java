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
package org.lolaf.staffix.plugins.async.spring;

import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.plugins.async.AsyncFixSessionsPluginSettings;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.lolaf.staffix.spring.boot.spi.FixSessionsPluginSettingsContributor;
import org.springframework.context.ApplicationContext;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps already-contributed sessions plugins with {@link AsyncFixSessionsPluginSettings}. Runs after the
 * simple (default-order) plugin contributors so that the {@code wraps} key resolves against an entry they
 * have already registered, then replaces that entry in-place under the same key.
 */
public class AsyncPluginContributor implements FixSessionsPluginSettingsContributor {

    private final AsyncPluginProps props;
    private final ApplicationContext ctx;

    public AsyncPluginContributor(AsyncPluginProps props, ApplicationContext ctx) {
        this.props = props;
        this.ctx = ctx;
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
                throw new IllegalArgumentException("staffix.async-plugin.instances." + key + ".wraps is required");
            }
            FixSessionsPluginSettings<?> wrapped = registry.get(p.getWraps());
            if (wrapped == null) {
                throw new IllegalArgumentException("staffix.async-plugin.instances." + key + ".wraps='" + p.getWraps()
                        + "' does not reference any sessions plugin contributed by another module");
            }

            AsyncFixSessionsPluginSettings.AsyncFixSessionsPluginSettingsBuilder b = AsyncFixSessionsPluginSettings.builder()
                    .delegateSettings(wrapped);
            if (p.getConsumerThreadPoolSize() != null) b.consumerThreadPoolSize(p.getConsumerThreadPoolSize());
            if (p.getQueueSize() != null) b.queueSize(p.getQueueSize());
            if (p.getBackpressurePolicy() != null) b.backpressurePolicy(p.getBackpressurePolicy());

            String path = "staffix.async-plugin.instances." + key;
            BeanRef.<Supplier<IdleStrategy>>resolveOptional(ctx, p.getConsumerIdleStrategySupplierBean(), Supplier.class, path + ".consumer-idle-strategy-supplier-bean")
                    .ifPresent(b::consumerIdleStrategySupplier);
            BeanRef.<Supplier<IdleStrategy>>resolveOptional(ctx, p.getProducerIdleStrategySupplierBean(), Supplier.class, path + ".producer-idle-strategy-supplier-bean")
                    .ifPresent(b::producerIdleStrategySupplier);

            // Wrapper replaces the wrapped entry in-place: the async settings report the delegate's
            // instance id (unless overridden), so the registry key stays stable.
            registry.put(p.getWraps(), b.build());
        });
    }
}
