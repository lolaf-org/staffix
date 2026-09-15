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
package org.lolaf.staffix.codec.decoders;

import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.version.FixVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.collections.Int2ObjectHashMap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * The message-type counterpart of {@link FixTFieldsRegistry}: FIXT session types first, then the application
 * dictionary's.
 */
public class FixTMessageTypeRegistry implements MessageTypeRegistry {

    private final Map<String, MessageType> messageTypes;
    private final Int2ObjectHashMap<MessageType> messageTypesByHash;
    private final FixDictionaryId fixDictionaryId;

    private FixTMessageTypeRegistry(FixDictionaryId fixDictionaryId, MessageTypeRegistry... registries) {
        this.fixDictionaryId = fixDictionaryId;
        this.messageTypes = new HashMap<>();
        this.messageTypesByHash = new Int2ObjectHashMap<>(10, 0.1f);
        for (MessageTypeRegistry r : registries) {
            r.getMessageTypes().forEach(mt -> {
                messageTypes.put(mt.code(), mt);
                messageTypesByHash.put(Hashing.hash(mt.code()), mt);
            });
        }
        this.messageTypesByHash.compact();
    }

    public static MessageTypeRegistry get(FixDictionaryId fixDictionaryId, FixVersion fixVersion) {
        MessageTypeRegistry dictionaryMessageTypeRegistry = MessageTypeRegistry.Registry.getInstance(fixDictionaryId);
        if (fixVersion instanceof FixtVersion) {
            return new FixTMessageTypeRegistry(fixDictionaryId, dictionaryMessageTypeRegistry, org.lolaf.staffix.api.msg.FixtMessageTypeRegistry.Registry.getInstance((FixtVersion) fixVersion));
        }
        return dictionaryMessageTypeRegistry;
    }

    @Override
    public FixDictionaryId getTargetDictionary() {
        return fixDictionaryId;
    }

    @Override
    public MessageType find(String messageType) {
        return messageTypes.get(messageType);
    }

    @Override
    public MessageType find(int messageTypeHash) {
        return messageTypesByHash.get(messageTypeHash);
    }

    @Override
    public Collection<MessageType> getMessageTypes() {
        return messageTypesByHash.values();
    }
}