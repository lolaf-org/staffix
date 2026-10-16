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
package org.lolaf.staffix.api.stores;

import org.lolaf.staffix.api.Factory;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.metadata.ComponentKind;
import org.lolaf.staffix.api.metadata.SelectedBy;
import org.lolaf.staffix.api.msg.MessageType;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.function.BiPredicate;

/**
 * Settings for a message store, implemented by each store module and resolved through the
 * {@link org.lolaf.staffix.api.Factory} SPI.
 *
 * <p>{@link #getMessageFilter()} decides what is not worth keeping: a store only has to hold what could be asked
 * for again, so filtering out what will never be resent is the cheapest way to bound it.
 */
@ComponentKind("messages-store")
@SelectedBy(sessionSetting = "fixMessageStoreInstanceId", whenUnnamed = SelectedBy.WhenUnnamed.DEFAULT_INSTANCE,
        whenUnknown = SelectedBy.WhenUnknown.REFUSED)
public interface FixMessagesStoreSettings extends InstanceProvider<FixMessagesStore> {

    Duration DEFAULT_RESOURCE_CHECK_INTERVAL = Duration.ofSeconds(5);
    Duration DEFAULT_ALERT_CLEAR_DELAY = Duration.ofSeconds(30);

    /**
     * Returns true for an outgoing message that is not stored, such as market data that will never be resent. Such a
     * message cannot be replayed on a ResendRequest.
     */
    BiPredicate<MessageType, ByteBuffer> getMessageFilter();

    /**
     * How often a store whose calls fail is checked for its resource coming back. While it is down the engine skips
     * the store's calls, rather than block the session on each one. Zero sends every call to the store.
     */
    default Duration getResourceCheckInterval() {
        return DEFAULT_RESOURCE_CHECK_INTERVAL;
    }

    /**
     * How long the store must keep succeeding before the session's store alert clears, so a resource dropping in and
     * out keeps one alert. Zero clears it on the first success.
     */
    default Duration getAlertClearDelay() {
        return DEFAULT_ALERT_CLEAR_DELAY;
    }

    @Override
    default FixMessagesStore instance() {
        return (FixMessagesStore) InstanceProvider.getSpiInstance(this, FixMessagesStoreFactory.class);
    }

    /**
     * The service provider that builds a message store from its settings class; see {@link Factory}.
     *
     * @param <S> the settings class it serves
     */
    interface FixMessagesStoreFactory<S> extends Factory<FixMessagesStore, S> {

    }
}