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
package org.lolaf.staffix.api.msg;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.fields.FixField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Which fields each message type admits, and in what order they must appear inside a repeating group.
 *
 * <p>Group field order is here rather than on the field because it is a property of the message: the same tag
 * can sit at different positions in different groups, and a group's first field is what delimits its entries.
 */
public interface MessageFieldsRegistry {

    FixDictionaryId getTargetDictionary();

    Collection<MessageType> getManagedMessageTypes();

    FixMessageFields getFixMessageFields(MessageType messageType);

    interface FixMessageFields {

        Collection<FixField> getGroupFields();

        Collection<FixField> getFields();

        Collection<FixField> getOrderedFields();

        Collection<FixField> getMissingRequiredFields(Collection<FixField> receivedFields);

        Collection<FixField> getGroupOrderedFields(FixField groupField);

        /**
         * Returns the dictionary-declared order position of {@code field} within the repeating group identified by
         * {@code groupField}, or {@code -1} if the field does not belong to that group. Backed by a precomputed
         * lookup so it can be called on the hot parsing path without scanning, see
         * {@code FixSessionSettings.ValidationSettings#isValidateFieldsOutOfOrder()}.
         */
        int getGroupFieldOrder(FixField groupField, FixField field);

        Collection<FixField> getGroupFields(FixField groupField);

        Collection<FixField> getGroupMissingRequiredFields(FixField groupField, Collection<FixField> receivedFields);
    }

    @Slf4j
    @UtilityClass
    class Registry {

        private static final Collection<MessageFieldsRegistry> SPI_INSTANCES = loadInstances();

        public static MessageFieldsRegistry getInstance(FixDictionaryId fixDictionaryId) {
            new ArrayList<>();
            return SPI_INSTANCES.stream().filter(r -> r.getTargetDictionary().equals(fixDictionaryId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unable to find any MessageFieldsRegistry SPI instance for dictionary " + fixDictionaryId + ", please add required fix package jar to your classpath"));
        }

        private static Collection<MessageFieldsRegistry> loadInstances() {
            List<MessageFieldsRegistry> instances = ServiceLoader.load(MessageFieldsRegistry.class)
                    .stream().map(ServiceLoader.Provider::get).collect(Collectors.toList());
            log.info("Loaded MessageFieldsRegistry SPI instances for dictionaries: {}", instances.stream().map(MessageFieldsRegistry::getTargetDictionary).collect(Collectors.toList()));
            return instances;
        }
    }
}
