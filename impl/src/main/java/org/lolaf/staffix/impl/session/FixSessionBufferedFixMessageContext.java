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
package org.lolaf.staffix.impl.session;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lolaf.betty.api.io.IOWriter;
import org.lolaf.betty.api.io.ReleasableMessageSendingContext;
import org.lolaf.ringos.rb.RingBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

@RequiredArgsConstructor
@Getter(AccessLevel.PACKAGE)
class FixSessionBufferedFixMessageContext implements IOWriter.ByteBufferBuilder, ReleasableMessageSendingContext {

    private final RingBuffer<FixSessionBufferedFixMessageContext> ctxPool;
    private final FixSessionImpl fixSession;

    private ByteBuffer[] encodedMessages = new ByteBuffer[8];
    private FixSessionFixMessageContext[] sendingContexts = new FixSessionFixMessageContext[8];
    private int sendingContextsCount;
    private IntFunction<ByteBuffer> allocator;
    private int approxMessageSize;

    @Override
    public void release() {
        // not need to call release on the contexts it's done before calling this method
        approxMessageSize = 0;
        for (int i = 0; i < sendingContextsCount; i++) {
            FixSessionFixMessageContext msc = sendingContexts[i];
            ByteBuffer messageToReturnToPool = msc.getMessage();
            msc.release();
            // ByteBuffers in bufferedWritesContexts needs to be manually returned to the IOBuffers pool
            fixSession.currentIOSession().unborrow(messageToReturnToPool);
        }
        Arrays.fill(sendingContexts, 0, sendingContextsCount, null);
        Arrays.fill(encodedMessages, 0, sendingContextsCount, null);
        sendingContextsCount = 0;
        ctxPool.offer(this);
    }

    public FixSessionBufferedFixMessageContext setup(IntFunction<ByteBuffer> allocator, List<FixSessionFixMessageContext> sendingContexts) {
        this.allocator = allocator;
        sendingContexts.forEach(this::add);
        return this;
    }

    private void add(FixSessionFixMessageContext ctx) {
        if (sendingContexts.length == sendingContextsCount) {
            sendingContexts = Arrays.copyOf(sendingContexts, sendingContexts.length * 2);
            encodedMessages = Arrays.copyOf(encodedMessages, encodedMessages.length * 2);
        }
        sendingContexts[sendingContextsCount++] = ctx;
    }

    @Override
    public ByteBuffer build() throws IOException {
        int requiredTotalSize = 0;
        for (int i = 0; i < sendingContextsCount; i++) {
            FixSessionFixMessageContext ctx = sendingContexts[i];
            ByteBuffer byteBuffer = ctx.build().flip();
            encodedMessages[i] = byteBuffer;
            requiredTotalSize += byteBuffer.remaining();
        }
        ByteBuffer encodedMessagesBuffer = allocator.apply(requiredTotalSize);
        for (int i = 0; i < sendingContextsCount; i++) {
            encodedMessagesBuffer.put(encodedMessages[i]);
        }
        return encodedMessagesBuffer;
    }

    @Override
    public boolean isPooledByteBuffer() {
        return true;
    }

    @Override
    public int getEstimatedByteBufferSize() {
        if (approxMessageSize == 0) {
            for (int i = 0; i < sendingContextsCount; i++) {
                approxMessageSize += sendingContexts[i].getEstimatedByteBufferSize();
            }
        }
        return approxMessageSize;
    }
}
