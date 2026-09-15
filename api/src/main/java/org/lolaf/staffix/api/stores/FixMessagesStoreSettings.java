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
import org.lolaf.staffix.api.msg.MessageType;

import java.nio.ByteBuffer;
import java.util.function.BiPredicate;

/**
 * Settings for a message store, implemented by each store module and resolved through the
 * {@link org.lolaf.staffix.api.Factory} SPI.
 *
 * <p>{@link #getMessageFilter()} decides what is worth keeping: a store only has to hold what could be asked
 * for again, so filtering out what will never be resent is the cheapest way to bound it.
 */
public interface FixMessagesStoreSettings extends InstanceProvider<FixMessagesStore> {

    BiPredicate<MessageType, ByteBuffer> getMessageFilter();

    @Override
    default FixMessagesStore instance() {
        return (FixMessagesStore) InstanceProvider.getSpiInstance(this, FixMessagesStoreFactory.class);
    }

    interface FixMessagesStoreFactory<S> extends Factory<FixMessagesStore, S> {

    }
}