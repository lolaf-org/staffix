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
package org.lolaf.staffix.api.codec;

import org.lolaf.ringos.idling.IdleStrategy;

import java.time.Duration;

/**
 * Pool of FixMessageEncoders instances
 */
public interface FixMessageEncodersPool<T extends FixMessageEncoder<?>> {

    /**
     * Borrow an instance from the pool
     *
     * @return an instance or null if the pool is exhausted (all instance are currently queued to be sent)
     */
    T borrow();

    /**
     * Borrow an instance from the pool, if the pool is exhausted, blocks for max maxWaitTime
     *
     * @param idleStrategy the idle strategy to use to wait when the pool is empty
     * @param maxWaitTime  the maximum wait time to retrieve an instance from the pool
     * @return an instance or null if the pool was still empty with the given time period
     */
    T borrowBlocking(IdleStrategy idleStrategy, Duration maxWaitTime);

    /**
     * Borrow an instance from the pool and block the current thread until an instance is available
     *
     * @param idleStrategy the idle strategy to use to wait when the pool is empty
     * @return an instance or null if the pool was still empty with the given time period
     */
    T borrowBlocking(IdleStrategy idleStrategy);


    /**
     * Destroy the pool, release all underlying encoders resources especially eventual direct byte buffers,
     * watch out to make sure that NO message to be sent are in flight when destroying the pool.
     */
    void destroy();
}
