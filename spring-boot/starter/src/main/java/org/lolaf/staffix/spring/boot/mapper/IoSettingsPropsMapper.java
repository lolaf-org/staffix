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
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.staffix.spring.boot.props.IoSettingsProps;

/**
 * Builds a connection's socket-level settings from its properties.
 */
@UtilityClass
public class IoSettingsPropsMapper {

    /**
     * Unset properties keep the {@link IOSettings} defaults, and so does a missing section.
     */
    public static IOSettings toSettings(IoSettingsProps props) {
        IOSettings.IOSettingsBuilder builder = IOSettings.builder();
        if (props == null) {
            return builder.build();
        }
        if (props.getReadBufferSize() != null) {
            builder.readBufferSize(props.getReadBufferSize());
        }
        if (props.getReadDirectBuffer() != null) {
            builder.readDirectBuffer(props.getReadDirectBuffer());
        }
        if (props.getTasksRingBufferSize() != null) {
            builder.tasksRingBufferSize(props.getTasksRingBufferSize());
        }
        if (props.getMultiThreadedWriteApiCalls() != null) {
            builder.multiThreadedWriteAPICalls(props.getMultiThreadedWriteApiCalls());
        }
        if (props.getMaxBytesCountPerWriteCycle() != null) {
            builder.maxBytesCountPerWriteCycle(props.getMaxBytesCountPerWriteCycle());
        }
        if (props.getWriteHighWatermark() != null) {
            builder.writeHighWatermark(props.getWriteHighWatermark());
        }
        if (props.getWriteLowWatermark() != null) {
            builder.writeLowWatermark(props.getWriteLowWatermark());
        }
        if (props.getTrackReceiveTime() != null) {
            builder.trackReceiveTime(props.getTrackReceiveTime());
        }
        return builder.build();
    }
}
