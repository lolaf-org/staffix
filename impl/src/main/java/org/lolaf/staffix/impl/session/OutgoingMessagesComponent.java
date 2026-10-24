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

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.betty.api.io.IOSession;
import org.lolaf.betty.api.io.IOWriter;
import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.ringos.idling.BackoffIdleStrategy;
import org.lolaf.ringos.idling.IdleStrategy;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.ringos.rb.RingBufferFactory;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.ha.FixEngineHa.ReplicatingStore;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.serde.SerDe;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.codec.encoders.DetachedFixMessageEncoder;
import org.lolaf.staffix.codec.encoders.FixMessageEncoderImpl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Everything a message goes through on its way out: the context it is encoded into, the write itself, and what
 * happens once it is on the wire - stored under its MsgSeqNum(34), logged, and the sender told.
 *
 * <p>The contexts are pooled because a send must not allocate. The pool is bounded, which is the backpressure a
 * session applies to an application sending faster than the socket drains, and the IO thread is the one caller that
 * never waits on it: the contexts in flight are given back by that same thread, so waiting there would deadlock.
 */
@Slf4j
public class OutgoingMessagesComponent implements FixSessionLayerComponent {

    private final FixSessionImpl fixSession;
    private final FixSessionLayerComponents fixSessionLayerComponents;
    private final FixApplication fixApplication;
    private final FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore;
    /**
     * A {@code SYNC} session's store, null for any other: its messages are stored as they are built, before the write,
     * so the standby has them before the counterparty does.
     */
    private final ReplicatingStore syncReplicatingStore;
    private final Consumer<FixSessionFixMessageContext> beforeWrite;
    private final Consumer<FixSessionBufferedFixMessageContext> beforeBufferedWrite;
    private final FixMessagesLogger.Logger fixMessagesLogger;
    private final FixSessionId fixSessionId;
    private final Clock clock;
    private final TimeUnit sendingTimeAccuracy;
    private final IdleStrategy pollBlockingIdleStrategy = new BackoffIdleStrategy();
    private final RingBuffer<FixSessionFixMessageContext> messageSendingContexts;
    private final RingBuffer<FixSessionBufferedFixMessageContext> bufferMessageSendingContexts;
    private final List<FixSessionFixMessageContext> bufferedMessageSendingContexts;
    private final RingBuffer<DetachedFixMessageEncoder> detachedEncoders;
    private final IOWriter.MessageSentCallback<FixSessionFixMessageContext> messageSentCallback = this::messageSentCallback;
    private final IOWriter.MessageSentCallback<FixSessionBufferedFixMessageContext> bufferedMessageSentCallback = this::bufferedMessagesSentCallback;
    private IntFunction<ByteBuffer> byteBufferBorrower = ByteBuffer::allocate;

    OutgoingMessagesComponent(FixSessionImpl fixSession, FixSessionLayerComponents fixSessionLayerComponents,
                              FixApplication fixApplication, FixMessagesStore.FixSessionMessagesStore fixSessionMessagesStore,
                              ReplicatingStore syncReplicatingStore, FixMessagesLogger.Logger fixMessagesLogger,
                              FixSessionId fixSessionId, Clock clock, TimeUnit sendingTimeAccuracy, IOSettings ioSettings) {
        this.fixSession = fixSession;
        this.fixSessionLayerComponents = fixSessionLayerComponents;
        this.fixApplication = fixApplication;
        this.fixSessionMessagesStore = fixSessionMessagesStore;
        this.syncReplicatingStore = syncReplicatingStore;
        this.beforeWrite = syncReplicatingStore == null ? null : this::storeAndAwaitReplication;
        this.beforeBufferedWrite = syncReplicatingStore == null ? null : this::storeAndAwaitReplication;
        this.fixMessagesLogger = fixMessagesLogger;
        this.fixSessionId = fixSessionId;
        this.clock = clock;
        this.sendingTimeAccuracy = sendingTimeAccuracy;
        // taken by the application, the scheduler, the IO thread and the owner of a session that is down, and
        // given back by whichever thread the send ends on
        RingBufferFactory.AccessType accessType = RingBufferFactory.AccessType.MULTI_CONSUMER_MULTI_PRODUCER;
        this.messageSendingContexts = RingBufferFactory.build(accessType, ioSettings.getTasksRingBufferSize());
        while (!messageSendingContexts.isFull()) {
            messageSendingContexts.offer(newMessageSendingContext());
        }
        this.bufferMessageSendingContexts = RingBufferFactory.build(accessType, ioSettings.getTasksRingBufferSize());
        while (!bufferMessageSendingContexts.isFull()) {
            bufferMessageSendingContexts.offer(new FixSessionBufferedFixMessageContext(bufferMessageSendingContexts, fixSession, beforeBufferedWrite));
        }
        this.bufferedMessageSendingContexts = new ArrayList<>(messageSendingContexts.getSize());
        // filled as carriers come back, so a session whose sends never wait never builds one
        this.detachedEncoders = RingBufferFactory.build(accessType, ioSettings.getTasksRingBufferSize());
    }

    int getWriteTasksQueueCapacity() {
        return messageSendingContexts.getCapacity();
    }

    <P1, P2> void bufferize(FixMessageEncoder<?> encoder, UTCTime sendingTime,
                            FixSession.MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2) {
        FixSessionFixMessageContext ctx = messageSendingContexts.poll();
        if (ctx == null) {
            flush();
            ctx = takeMessageSendingContext(fixSession.currentIOSession());
        }
        synchronized (bufferedMessageSendingContexts) {
            bufferedMessageSendingContexts.add(ctx.setup(byteBufferBorrower, encoder, sendingTime, messageSendOperationCallback, param1, param2));
        }
    }

    /**
     * Writes what was bufferized, or completes it as unsent when the connection went while it waited: the messages
     * hold sequence numbers the session owes its peer either way, so they are numbered and stored rather than
     * dropped. Like {@link #send}, that is done on the thread that owns the session.
     */
    void flush() {
        IOSession currentIOSession = fixSession.currentIOSession();
        if (currentIOSession == null && !fixSession.isWithinSessionOwnerThread(null)) {
            fixSession.runOnSessionOwnerThread(this::flush, null);
            return;
        }
        synchronized (bufferedMessageSendingContexts) {
            if (bufferedMessageSendingContexts.isEmpty()) {
                return;
            }
            if (currentIOSession == null) {
                bufferedMessageSendingContexts.forEach(this::completeWithoutConnection);
            } else {
                FixSessionBufferedFixMessageContext ctx = takeBufferedMessageSendingContext(currentIOSession);
                currentIOSession.send(ctx.setup(byteBufferBorrower, bufferedMessageSendingContexts), ctx, bufferedMessageSentCallback);
            }
            bufferedMessageSendingContexts.clear();
        }
    }

    /**
     * Ends a message that has no connection to go out on: it takes its MsgSeqNum(34) and reaches the store, so the
     * peer asks for it once it logs on, and the sender is told with {@code NO_CONNECTED_SESSION}. One that could not
     * be built or numbered, a stopped store refusing it, never reached the store, and its sender is told why instead.
     */
    private void completeWithoutConnection(FixSessionFixMessageContext ctx) {
        try {
            ByteBuffer message;
            try {
                message = ctx.build().flip(); // very important do not forget to flip message
            } catch (IOException | RuntimeException notStored) {
                // terminal state don't care if we do not return the eventually allocated ByteBuffer to the pool
                messageSentCallback.onMessageWriteCallback(null, notStored, ctx);
                return;
            }
            messageSentCallback.onMessageWriteCallback(message, FixSessionImpl.NO_CONNECTED_SESSION, ctx);
        } finally {
            // what the IO session does after the callback of every message it is handed
            ctx.release();
        }
    }

    /**
     * Sends while the session has no connection on the thread that owns it then, so that the sequence number this
     * message takes and the store write that follows happen in one place: a send that cannot go on the wire still
     * moves the session on, and two threads doing that at once would renumber each other.
     * <p>
     * The message is therefore numbered and stored in the order the owner picks the sends up, not before this
     * method returns.
     */
    <P1, P2> void send(FixMessageEncoder<?> encoder, UTCTime sendingTime,
                       FixSession.MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2) {
        IOSession ioSession = fixSession.currentIOSession();
        // only a session with no connection hands the send over
        if (ioSession == null && !fixSession.isWithinSessionOwnerThread(null)) {
            FixMessageEncoder<?> detached = detach(encoder);
            fixSession.runOnSessionOwnerThread(() -> send(detached, sendingTime, messageSendOperationCallback, param1, param2), null);
            return;
        }
        FixSessionFixMessageContext ctx = takeMessageSendingContext(ioSession);
        IOWriter.ByteBufferBuilder byteBufferBuilder = ctx.setup(byteBufferBorrower, encoder, sendingTime, messageSendOperationCallback, param1, param2);
        if (ioSession != null) {
            ioSession.send(byteBufferBuilder, ctx, messageSentCallback);
        } else {
            completeWithoutConnection(ctx);
        }
    }

    /**
     * Moves a message whose send has to wait out of the caller's encoder, which is then ended as a sent one is, so the
     * caller can begin the next message at once. An encoder the session cannot read the pending message from, or one
     * it already owns, is kept until sent instead.
     */
    FixMessageEncoder<?> detach(FixMessageEncoder<?> encoder) {
        if (!(encoder instanceof FixMessageEncoderImpl) || encoder instanceof DetachedFixMessageEncoder) {
            return encoder;
        }
        DetachedFixMessageEncoder carrier = detachedEncoders.poll();
        if (carrier == null) {
            carrier = new DetachedFixMessageEncoder(released -> detachedEncoders.offer((DetachedFixMessageEncoder) released));
        }
        ((FixMessageEncoderImpl<?>) encoder).transferPendingMessageTo(carrier);
        if (!encoder.isReusable()) {
            encoder.destroy();
        }
        encoder.release();
        return carrier;
    }

    /**
     * Sends on the connection given rather than the current one: a retransmission answers the connection that asked
     * for it, and must never reach the one that replaced it.
     */
    public void sendWithSeqNum(IOSession connection, FixMessageEncoder<?> encoder, long outgoingSequenceNumber) {
        FixSessionFixMessageSendingContext ctx = new FixSessionFixMessageResendingContext(encoder.getMessageType(), clock.now().asImmutable());
        connection.send(encoder.encode(connection::borrow, outgoingSequenceNumber, fixSessionId, fixApplication,
                        sendingTimeAccuracy, ctx.getSendingTime(), fixSession), ctx,
                (byteBuffer, e, messageType) -> logOutgoingFixMessage(byteBuffer.position(0), ctx, e), true);
    }

    void callOnMessageCallbackIfNeeded(Exception sendingError, FixSession.MessageSendOperationCallback callback,
                                       Object messageSendOperationCallbackParam1, Object messageSendOperationCallbackParam2) {
        if (callback != null) {
            try {
                callback.onMessageCallback(sendingError, messageSendOperationCallbackParam1, messageSendOperationCallbackParam2);
            } catch (Exception ex) {
                log.error("Failed to call MessageSendOperationCallback on FIX session {}", fixSessionId, ex);
            }
        }
    }

    /**
     * A connection lends its own buffers, which is what keeps a send off the heap; without one there is nowhere to
     * borrow from and a message sent while disconnected is allocated instead.
     */
    @Override
    public void onConnected() {
        byteBufferBorrower = fixSession.currentIOSession()::borrow;
    }

    @Override
    public void onConnectionClosed() {
        // important keep an allocator since we can also send a message when the session is offline
        byteBufferBorrower = ByteBuffer::allocate;
    }

    /**
     * The session's owner never waits for a context: the ones in flight are given back by the IO thread once it
     * writes them, so an owner waiting here would deadlock, and the offline owner is shared with every other
     * disconnected session of the engine. It gets a new one instead, which the full pool then drops.
     */
    private FixSessionFixMessageContext takeMessageSendingContext(IOSession currentSession) {
        FixSessionFixMessageContext ctx = messageSendingContexts.poll();
        if (ctx != null) {
            return ctx;
        }
        if (fixSession.isWithinSessionOwnerThread(currentSession)) {
            return newMessageSendingContext();
        }
        return messageSendingContexts.pollBlocking(pollBlockingIdleStrategy);
    }

    private FixSessionBufferedFixMessageContext takeBufferedMessageSendingContext(IOSession currentIOSession) {
        FixSessionBufferedFixMessageContext ctx = bufferMessageSendingContexts.poll();
        if (ctx != null) {
            return ctx;
        }
        if (fixSession.isWithinSessionOwnerThread(currentIOSession)) {
            return new FixSessionBufferedFixMessageContext(bufferMessageSendingContexts, fixSession, beforeBufferedWrite);
        }
        return bufferMessageSendingContexts.pollBlocking(pollBlockingIdleStrategy);
    }

    private FixSessionFixMessageContext newMessageSendingContext() {
        return new FixSessionFixMessageContext(fixSessionMessagesStore::getNextOutgoingSeqNum, fixApplication,
                sendingTimeAccuracy, clock, fixSession, fixSessionId, messageSendingContexts, this::detach, beforeWrite);
    }

    private void messageSentCallback(ByteBuffer message, Exception sendingError, FixSessionFixMessageContext context) {
        MessageType sentMessageType = context.getEncoder().getMessageType();
        long outgoingSeqNum = context.getOutgoingSeqNum();
        FixSession.MessageSendOperationCallback<?, ?> callback = context.getMessageSendOperationCallback();
        Object messageSendOperationCallbackParam1 = context.getMessageSendOperationCallbackParam1();
        Object messageSendOperationCallbackParam2 = context.getMessageSendOperationCallbackParam2();
        if (message == null) {
            callOnMessageCallbackIfNeeded(sendingError, callback, messageSendOperationCallbackParam1, messageSendOperationCallbackParam2);
            return;
        }
        if (sendingError != null) {
            fixApplication.onMessageSendingFailure(fixSession, sentMessageType, message.position(0), sendingError);
        }
        if (syncReplicatingStore == null) {
            store(fixSessionMessagesStore, sentMessageType, outgoingSeqNum, message);
        }

        logOutgoingFixMessage(message.position(0), context, sendingError);

        callOnMessageCallbackIfNeeded(sendingError, callback, messageSendOperationCallbackParam1, messageSendOperationCallbackParam2);
        fixSessionLayerComponents.onMessageSent(context.getSendingTime());
    }

    private void storeAndAwaitReplication(FixSessionFixMessageContext ctx) {
        storeBeforeWrite(ctx);
        syncReplicatingStore.awaitReplicated();
    }

    private void storeAndAwaitReplication(FixSessionBufferedFixMessageContext batch) {
        FixSessionFixMessageContext[] sendingContexts = batch.getSendingContexts();
        for (int i = 0; i < batch.getSendingContextsCount(); i++) {
            storeBeforeWrite(sendingContexts[i]);
        }
        syncReplicatingStore.awaitReplicated();
    }

    /**
     * The message is written up to its position, not yet flipped by the IO session.
     */
    private void storeBeforeWrite(FixSessionFixMessageContext ctx) {
        ByteBuffer message = ctx.getMessage();
        int end = message.position();
        int limit = message.limit();
        store(syncReplicatingStore, ctx.getEncoder().getMessageType(), ctx.getOutgoingSeqNum(), message.limit(end).position(0));
        message.limit(limit).position(end);
    }

    private static void store(FixMessagesStore.FixSessionMessagesStore store, MessageType sentMessageType, long outgoingSeqNum, ByteBuffer message) {
        if (sentMessageType.isStorable() && !store.filter(sentMessageType, message)) {
            store.storeMessageSent(outgoingSeqNum, message.position(0));
        } else {
            store.storeNextOutgoingSeqNum(outgoingSeqNum + 1);
        }
    }

    private void bufferedMessagesSentCallback(ByteBuffer bufferedWritesMessage, Exception sendingError, FixSessionBufferedFixMessageContext bufferedMessagesSendingContext) {
        int sendingContextsCount = bufferedMessagesSendingContext.getSendingContextsCount();
        FixSessionFixMessageContext[] sendingContexts = bufferedMessagesSendingContext.getSendingContexts();
        for (int i = 0; i < sendingContextsCount; i++) {
            FixSessionFixMessageContext msc = sendingContexts[i];
            messageSentCallback.onMessageWriteCallback(msc.getMessage(), sendingError, msc);
        }
    }

    private void logOutgoingFixMessage(ByteBuffer message, FixSessionFixMessageSendingContext ctx, Exception sendingError) {
        if (fixMessagesLogger.isLoggingOutgoing()) {
            if (sendingError == null) {
                try {
                    fixMessagesLogger.logOutgoing(clock.now(), ctx.getMessageType(), message);
                } catch (Exception ex) {
                    log.warn("Failed to log message", ex);
                }
            } else {
                byte[] dst = new byte[message.limit()];
                message.get(dst);
                fixSession.logEvent("Failed to send message: (%s), will be eventually resent on remote session reconnection: %s",
                        sendingError.getMessage(), new String(dst, SerDe.CHARSET));
            }
        }
    }

    @Value
    private static class FixSessionFixMessageResendingContext implements FixSessionFixMessageSendingContext {
        MessageType messageType;
        UTCTime sendingTime;
    }
}
