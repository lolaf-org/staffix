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
package org.lolaf.staffix.impl;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncoderFactory;
import org.lolaf.staffix.api.codec.FixMessageEncodersPool;
import org.lolaf.staffix.api.codec.FixMessageEncodingListener;
import org.lolaf.staffix.api.time.Clock;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * A pool of reusable encoders, so the message path does not allocate one per message.
 *
 * <p>A pool told it has a single borrower skips the guarding a shared one needs, which is why that is a
 * constructor choice and not something inferred.
 */
@Slf4j
public class FixMessageEncodersPoolImpl<T extends FixMessageEncoder<?>> implements FixMessageEncodersPool<T> {

    private final RingBuffer<T> encodersPool;
    private final Consumer<FixMessageEncodersPool<T>> releaser;
    private final AtomicBoolean destroyed = new AtomicBoolean();

    public FixMessageEncodersPoolImpl(Consumer<FixMessageEncodersPool<T>> releaser, boolean multiThreadedAccess, int size, Class<T> encoderClass,
                                      FixMessageEncoderFactory fixMessageEncoderFactory, FixMessageEncodingListener listener, boolean useDirectByteBuffers,
                                      Clock clock) {
        this.releaser = releaser;
        this.encodersPool = RingBufferFactory.build(multiThreadedAccess
                ? RingBufferFactory.AccessType.MULTI_CONSUMER_SINGLE_PRODUCER : RingBufferFactory.AccessType.SINGLE_CONSUMER_SINGLE_PRODUCER, size);

        Consumer<FixMessageEncoder<?>> returnToPool = this::returnToPool;
        IntFunction<ByteBuffer> pooledEncodersAllocator = useDirectByteBuffers ? ByteBuffer::allocateDirect : ByteBuffer::allocate;
        while (!encodersPool.isFull()) {
            encodersPool.offer(fixMessageEncoderFactory.newInstance(encoderClass, returnToPool, pooledEncodersAllocator, listener, clock));
        }
    }

    private void returnToPool(FixMessageEncoder<?> fixMessageEncoder) {
        if (!encodersPool.offer((T) fixMessageEncoder)) {
            throw new IllegalStateException("Unable to put back buffer in pool, not normal");
        }
    }

    @Override
    public T borrow() {
        return encodersPool.poll();
    }

    @Override
    public T borrowBlocking(IdleStrategy idleStrategy, Duration maxWaitTime) {
        return encodersPool.pollBlocking(idleStrategy, maxWaitTime);
    }

    @Override
    public T borrowBlocking(IdleStrategy idleStrategy) {
        return encodersPool.pollBlocking(idleStrategy);
    }

    @Override
    public void destroy() {
        if (!destroyed.getAndSet(true)) {
            if (releaser != null) {
                releaser.accept(this);
            }
            encodersPool.forEachEntry(FixMessageEncoder::destroy);
            encodersPool.clear();
        }
    }
}