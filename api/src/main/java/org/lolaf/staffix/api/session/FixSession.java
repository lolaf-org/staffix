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
package org.lolaf.staffix.api.session;

import org.lolaf.betty.api.settings.IOSettings;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.codec.FixMessageEncoder;
import org.lolaf.staffix.api.codec.FixMessageEncodersPool;
import org.lolaf.staffix.api.executor.MessageExecutor;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;

import javax.security.auth.Subject;
import java.security.cert.Certificate;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * One FIX session: the handle an application sends on, and the reason most of the engine exists.
 *
 * <p>The three ways of getting an encoder differ only in what they cost. {@link #newEncoder(Class)} builds one
 * per call and is the right choice outside the message path; the pools are for the path itself, where allocating
 * an encoder per message is the allocation worth removing. {@code multiThreadedBorrows} says whether the pool
 * must be safe for more than one borrower, and a pool told it needs no such guard is cheaper.
 *
 * <p>A session instance outlives the connection under it: it survives a disconnect and the reconnect that
 * follows, so it is safe to hold.
 */
public interface FixSession {

    /**
     * Created an encoders pool, the pool size will be the same as {@link #getWriteTasksQueueCapacity()}
     * It does not need to be bigger than the IO thread writing tasks queue size.
     * The allocated encoder will be automatically destroyed if needed when the fix session
     * is stopped due to a client or server shutdown.
     * Disconnections or logout will keep the FixMessageEncodersPool in memory
     *
     * @param id                   id of the encoder instance, calling this method 2 times with the same id will return the same FixMessageEncodersPool instance
     * @param multiThreadedBorrows indicates of the {@link FixMessageEncodersPool#borrow()} methods will be called from a single thread or multiple threads
     * @param encoderClass         the FixMessageEncoder class to pool
     * @return a pool of FixMessageEncoders
     */
    <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, boolean multiThreadedBorrows, Class<T> encoderClass);

    /**
     * Created an encoders pool for a given size
     */
    <T extends FixMessageEncoder<?>> FixMessageEncodersPool<T> newEncodersPool(String id, int size, boolean multiThreadedBorrows, Class<T> encoderClass);

    /**
     * Creates a new unpooled encoder instance
     *
     * @param encoderClass the encoder class to return
     * @param <T>          the type of encoder
     * @return an instance of the target encoder
     */
    <T extends FixMessageEncoder<?>> T newEncoder(Class<T> encoderClass);

    /**
     * The queue capacity for write IO operations to be processed by the IO thread,
     * typically when calling the {@link #send(FixMessageEncoder, UTCTime)} method outside the session IO thread,
     * a message send task will be enqueued, and will eventually block the current thread if the queue is full until
     * some space is freed by the IO thread processing IO tasks
     */
    int getWriteTasksQueueCapacity();

    FixSessionId getFixSessionId();

    FieldsRegistry getFieldsRegistry();

    FixSessionSettings getFixSessionSettings();

    /**
     * Will immediately disconnect the FIX session and reject any new connection attempts.
     * Session will be allowed to be logged in again only if {@link #logon()} method is called
     */
    void disconnect(String message);

    /**
     * Logs the session out permanently, session will be allowed to be logged in again only if {@link #logon()} method is called
     * <p>
     * The connection is not held down with it: an initiator disconnects, then dials again and stays there logged out,
     * ready for the {@link #logon()} that reopens the session. Use {@link #disconnect(String)} for a session that
     * should stop connecting altogether.
     *
     * @param message the logout message
     */
    void logoutPermanently(String message);

    /**
     * Logs the session out, session will be logged in again automatically if within its configured session time boundaries
     *
     * @param message the logout message
     */
    void logout(String message);

    void logon();

    boolean isLoggedIn();

    boolean isConnected();

    /**
     * The current desired (not actual) fix session state
     */
    FixSessionState getDesiredState();

    /**
     * Indicates if the current session is withing is schedules session time or not (EOD or weekend pause)
     * See {@link FixSessionSettings.SessionScheduleSettings} for session schedule configuration
     */
    boolean isWithinSessionTime();

    /**
     * Retrieves the authenticated subject if any
     */
    Subject getAuthenticatedSubject();

    /**
     * Sets an eventual authenticated Subject that can be computed during logon validation phase {@link FixApplication#validateLogon(FixSession, DecodedFixMessage, Executor)}
     */
    void setAuthenticatedSubject(Subject subject);

    /**
     * Get the clock that can be used to retrieve time efficiently (with no memory allocation)
     */
    Clock getClock();

    /**
     * Same version as {@link #bufferize(FixMessageEncoder, UTCTime, MessageSendOperationCallback, Object, Object)} but without any callback
     *
     * @param encoder     the FIX message encoder
     * @param sendingTime the message sending time or null to use the system current time
     */
    void bufferize(FixMessageEncoder<?> encoder, UTCTime sendingTime);

    /**
     * Puts a FixMessageEncoder into a local queue of encoders that can be sent as a single big messages over the FIX session socket when {@link #flush()} is called.
     * If the queue is full (see {@link IOSettings#getTasksRingBufferSize()}) an
     * automatic {@link #flush()} will be called to write messages in the session socket.
     * This is typically a better option to send multiple messages in a row than calling {@link #send(FixMessageEncoder, UTCTime)} in a loop.
     * It will make a better use of tcp window mechanism and BDP vs slightly increased latency.
     * {@link #flush()} must be called at the end to actually send the serialized messages on the FIX session socket.
     * The implementation must be multi threading access safe
     *
     * @param encoder                      the FIX message encoder
     * @param sendingTime                  the message sending time or null to use the system current time
     * @param messageSendOperationCallback a callback when the message has been sent or not, the callback is called in
     *                                     the IO thread and should minimize the amount of taken CPU time to process it,
     *                                     any blocking IO methods calls in  this callback should be completely avoided to
     *                                     avoid destroying IO thread throughput
     * @param param1                       first param of the messageSendOperationCallback
     * @param param2                       second param of the messageSendOperationCallback
     */
    <P1, P2> void bufferize(FixMessageEncoder<?> encoder, UTCTime sendingTime, MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2);

    /**
     * Flush to the session socket all the messages accumulated using {@link #bufferize(FixMessageEncoder, UTCTime)}
     * The implementation must be multi threading access safe
     */
    void flush();

    /**
     * Retrieves a plugin context for a given plugin class, you must setup your plugin for your session first,
     * see {@link FixEngineBuilder#getFixSessionsPlugins()} and {@link FixSessionSettings#getFixSessionPluginsInstanceIds()}
     *
     * @param pluginContextClass the plugin context class to lookup
     * @param <C>                the plugin context class to cast
     * @return the plugin context for the plugin implementation see {@link FixSessionPlugin#getPluginContext()}
     * or Optional.empty() if no context has been found
     */
    <C extends PluginContext> Optional<C> getPluginContext(Class<C> pluginContextClass);

    /**
     * Send the FixMessageEncoder immediately if the methods is called within the IO thread or enqueue the encoder
     * to be processed and sent over the wire by the IO thread
     * The implementation must be multi threading access safe
     *
     * @param encoder     the FIX message encoder
     * @param sendingTime the message sending time or null to use the system current time
     */
    void send(FixMessageEncoder<?> encoder, UTCTime sendingTime);

    /**
     * Send the FixMessageEncoder immediately if the methods is called within the IO thread or enqueue the encoder
     * to be processed and sent over the wire by the IO thread
     * The implementation must be multi threading access safe
     *
     * @param encoder                      the FIX message encoder
     * @param sendingTime                  the message sending time or null to use the system current time
     * @param messageSendOperationCallback a callback when the message has been sent or not, the callback is called in
     *                                     the IO thread and should minimize the amount of taken CPU time to process it,
     *                                     any blocking IO methods calls in this callback should be completely avoided to
     *                                     avoid destroying IO thread throughput
     * @param param1                       first param of the messageSendOperationCallback
     * @param param2                       second param of the messageSendOperationCallback
     */
    <P1, P2> void send(FixMessageEncoder<?> encoder, UTCTime sendingTime, MessageSendOperationCallback<P1, P2> messageSendOperationCallback, P1 param1, P2 param2);

    void sendBusinessMessageReject(String rejectText, int businessRejectReason, String businessRejectRefId, MessageType refMsgType);

    /**
     * Process a task within the FIX session IO thread
     * <p>
     * A session with no connection has no IO thread to run it on, so the task is refused rather than run on the
     * calling thread: the confinement is the reason to use this at all. Use
     * {@link #processTask(Runnable, BiConsumer)} to be told when that happens.
     *
     * @param task the task to process
     */
    void processTask(Runnable task);

    /**
     * Process a task within the FIX session IO thread
     *
     * @param task     the task to process
     * @param callback a callback to be called when the task is processed, successfully or with an error, the error
     *                 being an {@link java.io.IOException} when the session has no connection IO thread to run the task on
     */
    void processTask(Runnable task, BiConsumer<Runnable, Exception> callback);

    /**
     * Get a message executor for a given routing key, this allows to process a message within another Thread than the FIX session IO thread and
     * improve scalability. The routing key is the combination of a {@code routingNamespace} and an {@code index}: the {@code routingNamespace}
     * namespaces the index space so that the same index used under two different namespaces resolves to two distinct executors (and therefore two
     * independent per-key ordering domains). The index is a simple int supplier which can be manually computed or automatically generated for a
     * FIX field present in the message by assigning {@link org.lolaf.staffix.api.codec.FixFieldsDecoderMapper#indexer(FixField, Consumer)} to the
     * desired field. For a given {@code routingNamespace} the index must start from zero and increment by one for each different value of the field.
     * <p>
     * The executors belong to this session: the same routing key on another session resolves to another executor, so
     * ordering is preserved per key <b>within</b> a session and two sessions never share a queue. They are released
     * when the session stops, so an executor obtained here must not be held across a session's life; calling
     * {@link MessageExecutor#release()} before that is only needed for a routing key that has become useless while the
     * session goes on. Executors of every session are spread over the connector's executor threads
     * ({@code MessageExecutorSettings.executorsThreadsCount}), so more sessions mean more executors sharing those
     * threads rather than more threads.
     *
     * @param routingNamespace the class that namespaces the index space; the same index under different namespaces yields different executors
     * @param index            the index of the MessageExecutor to use for the message processing, start from zero and increments by one within the {@code routingNamespace}
     * @param <M>              the message to process
     * @param <P1>             the first parameter
     * @param <P2>             the second parameter
     * @param <P3>             the third parameter
     * @return MessageExecutor instance for the given routing namespace and index
     */
    <M, P1, P2, P3> MessageExecutor<M, P1, P2, P3> getMessageExecutor(Class<?> routingNamespace, IntSupplier index);

    void testRequest(String testRequest);

    /**
     * Snapshot of the continuous RTT measurement state: EMA-smoothed round-trip time,
     * remote-vs-local clock offset, and the wall-clock instant of the most recent sample.
     * <p>
     * Returns {@link Optional#empty()} until the first sample has been recorded, or if continuous
     * RTT measurement is disabled and no heartbeat-driven TestRequest has been answered yet.
     *
     * @see org.lolaf.staffix.api.session.FixSessionSettings.RttMeasurementSettings
     */
    Optional<RttMeasurement> getRttMeasurement();

    Collection<Certificate> getRemoteCertificates();

    /**
     * The sessions of the engine this one belongs to, so that a {@link FixApplication} can reach a sibling session -
     * every one of its callbacks is handed a {@link FixSession}, which makes this the way in.
     * <p>
     * Scoped to the engine, not the JVM: another engine in the same process keeps its own sessions, even when the two
     * are configured with the same {@link FixSessionId}.
     * <p>
     * A session joins the registry once its initiator/acceptor starts managing it, which happens <em>after</em>
     * {@link FixApplication#setup} and {@link FixApplication#onSessionCreated} have run. Called from either of those,
     * this therefore does not yet find the calling session itself, though sessions configured before it are already
     * there. From {@link FixApplication#onLogon} onwards the registry is complete.
     *
     * @return the registry of the engine managing this session
     */
    FixSessionRegistry getFixSessionRegistry();

    <T extends FixApplication> T getApplication();

    void logEvent(String event);

    void logEvent(String event, Object... params);

    /**
     * Indicates whether a FIX session is operated as an {@link #INITIATOR} (it establishes the connection) or an
     * {@link #ACCEPTOR} (it listens for and accepts incoming connections). The same {@link FixSessionId} may be
     * configured for both roles, so this type is required to disambiguate a session's settings.
     */
    enum FixSessionType {
        INITIATOR,
        ACCEPTOR
    }

    interface MessageSendOperationCallback<P1, P2> {

        /**
         * Callback when the message has been sent or an error occurred when trying to send it
         *
         * @param sendingError   and exception in case of a sending error or null if the message has been written correctly to the remote socket
         * @param callbackParam1 the first provided callback
         * @param callbackParam2 the second provided callback
         */
        void onMessageCallback(Exception sendingError, P1 callbackParam1, P2 callbackParam2);
    }
}