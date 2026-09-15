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
import org.lolaf.ringos.idling.*;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;
import org.lolaf.staffix.spring.boot.props.MessageExecutorProps;

import java.util.function.Supplier;

/**
 * Builds a message executor's settings from its properties.
 */
@UtilityClass
public class MessageExecutorPropsMapper {

    public static MessageExecutorSettings toSettings(MessageExecutorProps props, String instanceId) {
        if (props == null) {
            props = new MessageExecutorProps();
        }
        return MessageExecutorSettings.builder()
                .instanceId(instanceId)
                .executorsThreadsCount(props.getExecutorsThreadsCount())
                .queueSizePerThread(props.getQueueSizePerThread())
                .idleStrategy(toIdleStrategy(props.getIdleStrategy()))
                .build();
    }

    private static Supplier<IdleStrategy> toIdleStrategy(MessageExecutorProps.IdleStrategy s) {
        if (s == null) {
            return WaitNotifyIdleStrategy::new;
        }
        return switch (s) {
            case BUSY_SPIN -> BusySpinIdleStrategy::getInstance;
            case YIELDING -> YieldingIdleStrategy::getInstance;
            case BACKOFF -> BackoffIdleStrategy::new;
            default -> WaitNotifyIdleStrategy::new;
        };
    }
}
