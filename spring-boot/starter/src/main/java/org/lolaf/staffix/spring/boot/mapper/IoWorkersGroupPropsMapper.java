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
package org.lolaf.staffix.spring.boot.mapper;

import lombok.experimental.UtilityClass;
import org.lolaf.betty.api.io.IOWorkerLoadBalancer;
import org.lolaf.betty.api.io.IOWorkersGroup;
import org.lolaf.betty.api.lb.MinIOThreadLoadSessionLoadBalancer;
import org.lolaf.betty.api.lb.MinRegisteredSessionLoadBalancer;
import org.lolaf.betty.api.lb.NapIdLoadBalancer;
import org.lolaf.betty.api.settings.IOWorkersGroupSettings;
import org.lolaf.betty.api.ss.IdleStrategySelectStrategy;
import org.lolaf.betty.api.ss.SelectStrategy;
import org.lolaf.betty.api.ss.WakeupSelectStrategy;
import org.lolaf.betty.api.stats.IOWorkerStats;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.BusySpinIdleStrategy;
import org.lolaf.ringos.idling.YieldingIdleStrategy;
import org.lolaf.staffix.spring.boot.props.IoWorkersGroupProps;
import org.lolaf.staffix.spring.boot.spi.BeanRef;
import org.springframework.context.ApplicationContext;

import java.nio.channels.spi.SelectorProvider;

/**
 * Builds the shared IO worker group from its properties.
 */
@UtilityClass
public class IoWorkersGroupPropsMapper {

    public static IOWorkersGroup newInstance(IoWorkersGroupProps props, String fallbackId, ApplicationContext ctx) {
        if (props == null) {
            return IOWorkersGroupSettings.builder().id(fallbackId).build().newInstance();
        }
        IOWorkersGroupSettings.IOWorkersGroupSettingsBuilder builder = IOWorkersGroupSettings.builder()
                .id(props.getId() == null ? fallbackId : props.getId());

        if (props.getOptimizedSelector() != null) {
            builder.optimizedSelector(props.getOptimizedSelector());
        }
        if (props.getWorkersLoadEMATimeWindow() != null) {
            builder.workersLoadEMATimeWindow(props.getWorkersLoadEMATimeWindow());
        }
        if (props.getIoWorkersRebalanceInterval() != null) {
            builder.ioWorkersRebalanceInterval(props.getIoWorkersRebalanceInterval());
        }
        IOWorkerLoadBalancer loadBalancer = resolveLoadBalancer(props, ctx);
        if (loadBalancer != null) {
            builder.ioWorkerLoadBalancer(loadBalancer);
        }
        BeanRef.<SelectorProvider>resolveOptional(ctx, props.getSelectorProviderBean(), SelectorProvider.class,
                        "staffix.<...>.io-workers.selector-provider-bean")
                .ifPresent(builder::selectorProvider);
        BeanRef.<IOWorkerStats.IOWorkerStatsProvider>resolveOptional(ctx, props.getIoWorkerStatisticsProviderBean(),
                        IOWorkerStats.IOWorkerStatsProvider.class,
                        "staffix.<...>.io-workers.io-worker-statistics-provider-bean")
                .ifPresent(builder::ioWorkerStatisticsProvider);
        for (IoWorkersGroupProps.IoThreadGroupProps tg : props.getThreadGroups()) {
            builder.ioThreadGroup(IOWorkersGroupSettings.IOThreadGroup.builder()
                    .name(tg.getName())
                    .ioThreadCount(tg.getIoThreadCount())
                    .selectStrategy(toSelectStrategy(tg.getSelectStrategy(), tg.getWakeupCount()))
                    .build());
        }
        return builder.build().newInstance();
    }

    private static IOWorkerLoadBalancer resolveLoadBalancer(IoWorkersGroupProps props, ApplicationContext ctx) {
        return BeanRef.<IOWorkerLoadBalancer>resolveOptional(ctx, props.getIoWorkerLoadBalancerBean(),
                        IOWorkerLoadBalancer.class, "staffix.<...>.io-workers.io-worker-load-balancer-bean")
                .orElseGet(() -> fromEnum(props.getIoWorkerLoadBalancer()));
    }

    private static IOWorkerLoadBalancer fromEnum(IoWorkersGroupProps.IoWorkerLoadBalancer e) {
        if (e == null) {
            return null;
        }
        return switch (e) {
            case MIN_REGISTERED_SESSIONS -> MinRegisteredSessionLoadBalancer.getInstance();
            case MIN_IO_THREAD_LOAD -> MinIOThreadLoadSessionLoadBalancer.getInstance();
            case NAPI_ID -> NapIdLoadBalancer.getInstance();
        };
    }

    private static SelectStrategy toSelectStrategy(IoWorkersGroupProps.IoThreadGroupProps.SelectStrategy s, int wakeupCount) {
        if (s == null) {
            return new WakeupSelectStrategy(wakeupCount);
        }
        return switch (s) {
            case BUSY_SPIN -> new IdleStrategySelectStrategy(BusySpinIdleStrategy.getInstance());
            case YIELDING -> new IdleStrategySelectStrategy(YieldingIdleStrategy.getInstance());
            case BACKOFF -> new IdleStrategySelectStrategy(new BackoffIdleStrategy());
            default -> new WakeupSelectStrategy(wakeupCount);
        };
    }
}
