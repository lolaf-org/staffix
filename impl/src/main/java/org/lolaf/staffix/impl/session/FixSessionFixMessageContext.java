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
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

@RequiredArgsConstructor
@Getter(AccessLevel.PACKAGE)
class FixSessionFixMessageContext implements IOWriter.ByteBufferBuilder, ReleasableMessageSendingContext {

    private final LongSupplier nextOutgoingSeqNumSupplier;
    private final FixApplication fixApplication;
    private final TimeUnit sendingTimeAccuracy;
    private final Clock clock;
    private final FixSession fixSession;
    private final FixSessionId fixSessionId;
    private final RingBuffer<FixSessionFixMessageContext> ctxPool;

    private IntFunction<ByteBuffer> allocator;
    private UTCTime sendingTime;
    private FixMessageEncoder<?> encoder;
    private FixSession.MessageSendOperationCallback<?, ?> messageSendOperationCallback;
    private Object messageSendOperationCallbackParam1;
    private Object messageSendOperationCallbackParam2;
    private long outgoingSeqNum;
    private ByteBuffer message;
    private int approxMessageSize;

    @Override
    public void release() {
        message = null;
        allocator = null;
        messageSendOperationCallback = null;
        messageSendOperationCallbackParam1 = null;
        messageSendOperationCallbackParam2 = null;
        outgoingSeqNum = 0L;
        approxMessageSize = 0;
        if (!encoder.isReusable()) {
            encoder.destroy();
        }
        encoder.release();
        encoder = null;
        ctxPool.offer(this);
    }

    public FixSessionFixMessageContext setup(IntFunction<ByteBuffer> allocator, FixMessageEncoder<?> encoder, UTCTime sendingTime,
                                             FixSession.MessageSendOperationCallback<?, ?> messageSendOperationCallback,
                                             Object messageSendOperationCallbackParam1, Object messageSendOperationCallbackParam2) {
        this.allocator = allocator;
        this.encoder = encoder;
        this.sendingTime = sendingTime;
        this.messageSendOperationCallback = messageSendOperationCallback;
        this.messageSendOperationCallbackParam1 = messageSendOperationCallbackParam1;
        this.messageSendOperationCallbackParam2 = messageSendOperationCallbackParam2;
        return this;
    }

    @Override
    public ByteBuffer build() throws IOException {
        outgoingSeqNum = nextOutgoingSeqNumSupplier.getAsLong();
        if (sendingTime == null) {
            sendingTime = clock.now();
        }
        return message = encoder.encode(allocator, outgoingSeqNum, fixSessionId, fixApplication, sendingTimeAccuracy, sendingTime, fixSession);
    }

    @Override
    public boolean isPooledByteBuffer() {
        return true;
    }

    @Override
    public int getEstimatedByteBufferSize() {
        if (approxMessageSize == 0) {
            approxMessageSize = encoder.getApproximateEncodedMessageLength(fixSessionId);
        }
        return approxMessageSize;
    }
}
