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
package org.lolaf.staffix.tests;

import lombok.Builder;
import lombok.Getter;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.nio.ByteBuffer;
import java.util.function.BiPredicate;

@Getter
@Builder(toBuilder = true)
public class TestingFixMessagesStoreSettings implements FixMessagesStoreSettings {

    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;

    private FixMessagesStore.FixSessionMessagesStore testingFixSessionMessagesStore;

    @Builder.Default
    private BiPredicate<MessageType, ByteBuffer> messageFilter = (messageType, direction) -> false;

}