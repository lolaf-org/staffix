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
package org.lolaf.staffix.spring.boot.props;

import lombok.Data;

/**
 * Socket-level settings for a connection - buffer sizes, TCP options - as properties.
 */
@Data
public class IoSettingsProps {

    /**
     * Bytes read from the socket at a time. Larger reads cost memory per connection and help only a session that
     * actually receives that much.
     */
    private Integer readBufferSize;
    /**
     * Whether the read buffer is off-heap, which avoids a copy at the socket at the cost of slower allocation.
     */
    private Boolean readDirectBuffer;
    /**
     * Depth of the queue of pending writes. A full queue makes the caller wait.
     */
    private Integer tasksRingBufferSize;
    /**
     * Whether more than one thread may send on a session. False is cheaper and correct when only the session
     * thread sends.
     */
    private Boolean multiThreadedWriteAPICalls;
    /**
     * How much may be written in one turn, which bounds how long one busy session can hold an IO thread.
     */
    private Integer maxBytesCountPerWriteCycle;
    /**
     * Pending bytes at which the session is reported as backing up.
     */
    private Integer writeHighWatermark;
    /**
     * Pending bytes at which it is reported as recovered.
     */
    private Integer writeLowWatermark;
    /**
     * Whether the socket read is timestamped, which latency metrics need and which costs a clock read per read.
     */
    private Boolean trackReceiveTime;
}
