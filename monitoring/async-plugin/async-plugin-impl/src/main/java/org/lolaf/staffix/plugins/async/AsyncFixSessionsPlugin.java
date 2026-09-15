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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.ringos.rb.RingBuffer;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.plugins.FixSessionPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.PluginContext;

import java.util.Collection;
import java.util.Optional;

/**
 * A {@link FixSessionsPlugin} that wraps another plugin so that the per-session callbacks it produces are
 * replayed asynchronously on a shared consumer thread pool, off the latency-critical message path.
 *
 * <p>The wrapper is transparent to the engine and to session configuration: it reports the delegate's
 * {@code instanceId} ({@link AsyncFixSessionsPluginSettings#getInstanceId()}) and matches the delegate's
 * plugin class ({@link #matchesPluginClass(Class)}), so a session that referenced the delegate keeps
 * resolving to it through the wrapper. If the delegate is itself {@link Startable}, its lifecycle is
 * driven from here (started before, and stopped after, the consumer pool — so buffered callbacks are
 * fully drained into the delegate before it is stopped).
 *
 * @param <C> the wrapped plugin's context type
 * @see AsyncFixSessionPlugin
 */
@Slf4j
public final class AsyncFixSessionsPlugin<C extends PluginContext>
        extends Startable.SimpleStartable<AsyncFixSessionsPlugin<C>>
        implements FixSessionsPlugin<C> {

    private final AsyncFixSessionsPluginSettings settings;
    private final FixSessionsPlugin<C> delegate;
    private AsyncPluginConsumerPool consumerPool;

    @SuppressWarnings("unchecked")
    public AsyncFixSessionsPlugin(AsyncFixSessionsPluginSettings settings) {
        if (settings.getDelegateSettings() == null) {
            throw new IllegalArgumentException("AsyncFixSessionsPluginSettings.delegateSettings must not be null");
        }
        this.settings = settings;
        this.delegate = (FixSessionsPlugin<C>) settings.getDelegateSettings().instance();
    }

    @Override
    public String getInstanceId() {
        return settings.getInstanceId();
    }

    @Override
    public boolean matchesPluginClass(Class<? extends FixSessionsPlugin<?>> pluginClass) {
        return getClass().equals(pluginClass) || delegate.matchesPluginClass(pluginClass);
    }

    @Override
    public Optional<FixSessionPlugin<C, Object>> onSessionCreated(String fixInstanceId, FixSession fixSession,
                                                                  Collection<MessageType> incomingMessageTypes,
                                                                  Collection<MessageType> outgoingMessageTypes) {
        return delegate.onSessionCreated(fixInstanceId, fixSession, incomingMessageTypes, outgoingMessageTypes)
                .map(sessionPlugin -> {
                    // The delegate's token type is carried opaquely as Object through the async replay path.
                    @SuppressWarnings("unchecked")
                    FixSessionPlugin<C, Object> opaqueDelegate = (FixSessionPlugin<C, Object>) sessionPlugin;
                    RingBuffer<AsyncPluginEvent> queue = consumerPool.register(opaqueDelegate);
                    return new AsyncFixSessionPlugin<>(opaqueDelegate, queue, settings.getBackpressurePolicy(),
                            settings.getProducerIdleStrategySupplier().get());
                });
    }

    @Override
    protected void startMe() throws StartStopException {
        if (delegate instanceof Startable) {
            ((Startable<?>) delegate).start();
        }
        consumerPool = new AsyncPluginConsumerPool(settings.getConsumerThreadPoolSize(), settings.getQueueSize(),
                getInstanceId(), settings.getConsumerIdleStrategySupplier());
        consumerPool.start();
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        // Stop (and fully drain) the consumer pool before stopping the delegate, so every buffered
        // callback — including onSessionDestroyed — has been replayed into the delegate first.
        if (consumerPool != null) {
            consumerPool.stop(stopDeadline);
        }
        if (delegate instanceof Startable) {
            ((Startable<?>) delegate).stop(stopDeadline);
        }
    }
}
