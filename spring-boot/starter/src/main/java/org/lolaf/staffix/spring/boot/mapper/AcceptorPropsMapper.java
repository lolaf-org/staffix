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
import org.lolaf.staffix.api.FixAcceptorBuilder;
import org.lolaf.staffix.spring.boot.props.AcceptorProps;
import org.springframework.context.ApplicationContext;

import java.util.concurrent.ScheduledExecutorService;

/**
 * Turns {@link org.lolaf.staffix.spring.boot.props.AcceptorProps} into a builder.
 */
@UtilityClass
public class AcceptorPropsMapper {

    public static FixAcceptorBuilder build(String mapKey, AcceptorProps p, ScheduledExecutorService scheduler, ApplicationContext ctx) {
        if (p.getBindAddress() == null) {
            throw new IllegalArgumentException("staffix.acceptors." + mapKey + ".bind-address is required");
        }
        String instanceId = p.getInstanceId() == null ? mapKey : p.getInstanceId();
        FixAcceptorBuilder.FixAcceptorBuilderBuilder<?, ?> b = FixAcceptorBuilder.builder()
                .instanceId(instanceId)
                .bindAddress(AddressUtils.parse(p.getBindAddress()))
                .scheduledExecutorService(scheduler)
                .ioWorkersGroup(IoWorkersGroupPropsMapper.newInstance(p.getIoWorkers(), instanceId, ctx))
                .messageExecutorSettings(MessageExecutorPropsMapper.toSettings(p.getMessageExecutor(), instanceId))
                .shutdownMaxDelay(p.getShutdownMaxDelay());
        if (p.getAcceptorIoWorkers() != null) {
            b.acceptorIoWorkerGroup(IoWorkersGroupPropsMapper.newInstance(p.getAcceptorIoWorkers(), instanceId + "-acceptor", ctx));
        }
        if (p.getSsl() != null) {
            b.serverSSLSettings(SslPropsMapper.toServerSSLSettings(p.getSsl(), ctx));
        }
        for (String storeId : p.getTargetSessionsSettingsStoreInstanceIds()) {
            b.targetFixSessionsSettingsStoreInstancesId(storeId);
        }
        return b.build();
    }
}
