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
package org.lolaf.staffix.plugins.async;

/**
 * How an {@code AsyncFixSessionPlugin} reacts when its ring buffer is full because the consumer thread
 * cannot keep up with the producing (latency-critical) threads.
 */
public enum BackpressurePolicy {

    /**
     * Never block the producing thread: drop the event, increment the dropped-events counter and emit a
     * throttled warning. The safe default for latency-critical paths — losing a monitoring/logging
     * callback under overload is preferable to stalling message processing.
     */
    DROP,

    /**
     * Never lose an event: block the producing thread on the configured producer idle strategy until a
     * slot is free. This stalls the latency-critical thread when the consumer falls behind and should
     * only be chosen when the wrapped plugin's callbacks must not be dropped.
     */
    BLOCK
}
