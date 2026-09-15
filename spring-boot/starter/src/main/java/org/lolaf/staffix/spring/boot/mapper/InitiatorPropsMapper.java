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
import org.lolaf.staffix.api.FixInitiatorBuilder;
import org.lolaf.staffix.spring.boot.props.InitiatorProps;
import org.springframework.context.ApplicationContext;

import java.net.InetSocketAddress;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Turns {@link org.lolaf.staffix.spring.boot.props.InitiatorProps} into a builder.
 *
 * <p>The mappers are separate from the props so the property classes stay plain data that Spring can bind and
 * validate without touching the engine's types.
 */
@UtilityClass
public class InitiatorPropsMapper {

    public static FixInitiatorBuilder build(String mapKey, InitiatorProps p, ScheduledExecutorService scheduler, ApplicationContext ctx) {
        if (p.getFixSessionId() == null) {
            throw new IllegalArgumentException("staffix.initiators." + mapKey + ".fix-session-id is required");
        }
        if (p.getConnectAddresses() == null || p.getConnectAddresses().isEmpty()) {
            throw new IllegalArgumentException("staffix.initiators." + mapKey + ".connect-addresses is required");
        }
        String instanceId = p.getInstanceId() == null ? mapKey : p.getInstanceId();
        FixInitiatorBuilder.FixInitiatorBuilderBuilder<?, ?> b = FixInitiatorBuilder.builder()
                .instanceId(instanceId)
                .fixSessionId(p.getFixSessionId().toFixSessionId())
                .scheduledExecutorService(scheduler)
                .ioWorkersGroup(IoWorkersGroupPropsMapper.newInstance(p.getIoWorkers(), instanceId, ctx))
                .messageExecutorSettings(MessageExecutorPropsMapper.toSettings(p.getMessageExecutor(), instanceId))
                .connectionRetry(p.getConnectionRetry())
                .shutdownMaxDelay(p.getShutdownMaxDelay());
        for (InetSocketAddress addr : AddressUtils.parseAll(p.getConnectAddresses())) {
            b.connectAddress(addr);
        }
        if (p.getSsl() != null) {
            b.sslSettings(SslPropsMapper.toSSLSettings(p.getSsl(), ctx));
        }
        return b.build();
    }
}
