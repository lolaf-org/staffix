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
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.FixtMessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageFieldsRegistry;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.version.FixVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.collections.ImmutableIndexableList;
import org.lolaf.staffix.collections.IndexableMap;

import java.util.*;
import java.util.stream.Collectors;

/**
 * The per-message field definitions for a FIXT.1.1 session, combining the session layer's with the application
 * dictionary's.
 */
public class FixTMessageFieldsRegistry implements MessageFieldsRegistry {

    private final FixDictionaryId fixDictionaryId;
    private final MessageFieldsRegistry applicationRegistry;
    private final Collection<MessageType> managedMessageTypes;
    private final Map<MessageType, FixMessageFields> transportFields;

    private FixTMessageFieldsRegistry(FixDictionaryId fixDictionaryId, FieldsRegistry mergedFieldsRegistry,
                                      MessageFieldsRegistry applicationRegistry, MessageFieldsRegistry transportRegistry) {
        this.fixDictionaryId = fixDictionaryId;
        this.applicationRegistry = applicationRegistry;

        Collection<MessageType> transportMessageTypes = transportRegistry.getManagedMessageTypes();
        this.transportFields = new IndexableMap<>(MessageType.class);
        for (MessageType messageType : transportMessageTypes) {
            transportFields.put(messageType,
                    new RemappedFixMessageFields(transportRegistry.getFixMessageFields(messageType), mergedFieldsRegistry));
        }

        // union of both composed registries; transport session-layer types take precedence in getFixMessageFields
        List<MessageType> union = applicationRegistry.getManagedMessageTypes().stream().filter(mt -> !mt.isAdmin()).collect(Collectors.toList());
        union.addAll(transportMessageTypes);
        this.managedMessageTypes = Collections.unmodifiableList(union);
    }

    public static MessageFieldsRegistry get(FixDictionaryId fixDictionaryId, FixVersion fixVersion, FieldsRegistry mergedFieldsRegistry) {
        MessageFieldsRegistry applicationRegistry = MessageFieldsRegistry.Registry.getInstance(fixDictionaryId);
        if (fixVersion instanceof FixtVersion) {
            return new FixTMessageFieldsRegistry(fixDictionaryId, mergedFieldsRegistry, applicationRegistry,
                    FixtMessageFieldsRegistry.Registry.getInstance((FixtVersion) fixVersion));
        }
        return applicationRegistry;
    }

    @Override
    public FixDictionaryId getTargetDictionary() {
        return fixDictionaryId;
    }

    @Override
    public Collection<MessageType> getManagedMessageTypes() {
        return managedMessageTypes;
    }

    @Override
    public FixMessageFields getFixMessageFields(MessageType messageType) {
        // FIXT transport (session-layer) definitions take precedence over an application dictionary that also declares
        // session messages (e.g. a combined FIX 4.x dictionary reused under FIXT).
        if (messageType.isAdmin()) {
            return transportFields.get(messageType);
        }
        return applicationRegistry.getFixMessageFields(messageType);
    }


    /**
     * Re-binds an existing {@link MessageFieldsRegistry.FixMessageFields} definition onto the {@link FixField} instances of
     * a target {@link FieldsRegistry}. This is used by {@link FixTMessageFieldsRegistry} so that FIXT session-layer message
     * definitions coming from the transport dictionary reference the very same (merged, collision-free indexed) field
     * instances that the parser resolves during decoding. Without this re-binding the required/defined-field collections
     * would be populated with the transport dictionary field instances while the decoder feeds merged-registry instances,
     * and the identity/index-addressed lookups used on the hot path would not match. One instance per transport-managed
     * message type is built up-front, so the hot path only does an index lookup.
     */
    private static final class RemappedFixMessageFields implements MessageFieldsRegistry.FixMessageFields {

        private final Collection<FixField> orderedFields;
        private final Collection<FixField> fields;
        private final FixField[] requiredFields;
        private final Collection<FixField> groupFields;
        private final Map<FixField, Collection<FixField>> orderedGroupFields;
        private final Map<FixField, Collection<FixField>> groupFieldsMap;
        private final Map<FixField, FixField[]> requiredGroupFields;
        private final Map<FixField, GroupFieldOrder> groupFieldOrders;

        RemappedFixMessageFields(MessageFieldsRegistry.FixMessageFields source, FieldsRegistry targetRegistry) {
            this.orderedFields = remap(source.getOrderedFields(), targetRegistry);
            this.fields = new ImmutableIndexableList<>(FixField.class, orderedFields);
            this.requiredFields = remap(source.getMissingRequiredFields(Collections.emptyList()), targetRegistry).toArray(new FixField[0]);

            this.orderedGroupFields = new IndexableMap<>(FixField.class);
            this.groupFieldsMap = new IndexableMap<>(FixField.class);
            this.requiredGroupFields = new IndexableMap<>(FixField.class);
            this.groupFieldOrders = new IndexableMap<>(FixField.class);
            Collection<FixField> groupFieldsLocal = new ArrayList<>();
            for (FixField sourceGroupField : source.getGroupFields()) {
                FixField groupField = remap(sourceGroupField, targetRegistry);
                groupFieldsLocal.add(groupField);
                Collection<FixField> members = remap(source.getGroupOrderedFields(sourceGroupField), targetRegistry);
                orderedGroupFields.put(groupField, members);
                groupFieldsMap.put(groupField, new ImmutableIndexableList<>(FixField.class, members));
                requiredGroupFields.put(groupField,
                        remap(source.getGroupMissingRequiredFields(sourceGroupField, Collections.emptyList()), targetRegistry).toArray(new FixField[0]));
                groupFieldOrders.put(groupField, buildOrderIndex(members));
            }
            this.groupFields = new ImmutableIndexableList<>(FixField.class, groupFieldsLocal);
        }

        private static Collection<FixField> remap(Collection<FixField> source, FieldsRegistry targetRegistry) {
            Collection<FixField> remapped = new ArrayList<>(source.size());
            for (FixField field : source) {
                remapped.add(remap(field, targetRegistry));
            }
            return remapped;
        }

        private static FixField remap(FixField field, FieldsRegistry targetRegistry) {
            FixField target = targetRegistry.find(field.getCode());
            return target != null ? target : field;
        }

        private static GroupFieldOrder buildOrderIndex(Collection<FixField> orderedMembers) {
            int n = orderedMembers.size();
            long[] packed = new long[n];
            int declaredRank = 0;
            for (FixField member : orderedMembers) {
                packed[declaredRank] = ((long) member.getAsInt() << 32) | declaredRank;
                declaredRank++;
            }
            Arrays.sort(packed);
            int[] memberIndexAsc = new int[n];
            int[] orderAtSorted = new int[n];
            for (int k = 0; k < n; k++) {
                memberIndexAsc[k] = (int) (packed[k] >>> 32);
                orderAtSorted[k] = (int) packed[k];
            }
            return new GroupFieldOrder(memberIndexAsc, orderAtSorted);
        }

        private static Collection<FixField> getMissingFields(FixField[] fields, Collection<FixField> receivedFields) {
            if (fields == null) {
                return Collections.emptyList();
            }
            Collection<FixField> missingFields = null;
            for (FixField requiredField : fields) {
                if (!receivedFields.contains(requiredField)) {
                    if (missingFields == null) {
                        missingFields = new ArrayList<>();
                    }
                    missingFields.add(requiredField);
                }
            }
            return missingFields == null ? Collections.emptyList() : missingFields;
        }

        @Override
        public Collection<FixField> getGroupFields() {
            return groupFields;
        }

        @Override
        public Collection<FixField> getFields() {
            return fields;
        }

        @Override
        public Collection<FixField> getOrderedFields() {
            return orderedFields;
        }

        @Override
        public Collection<FixField> getMissingRequiredFields(Collection<FixField> receivedFields) {
            return getMissingFields(requiredFields, receivedFields);
        }

        @Override
        public Collection<FixField> getGroupOrderedFields(FixField groupField) {
            return orderedGroupFields.get(groupField);
        }

        @Override
        public int getGroupFieldOrder(FixField groupField, FixField field) {
            GroupFieldOrder order = groupFieldOrders.get(groupField);
            if (order == null) {
                return -1;
            }
            int k = Arrays.binarySearch(order.memberIndexAsc, field.getAsInt());
            return k < 0 ? -1 : order.orderAtSorted[k];
        }

        @Override
        public Collection<FixField> getGroupFields(FixField groupField) {
            return groupFieldsMap.get(groupField);
        }

        @Override
        public Collection<FixField> getGroupMissingRequiredFields(FixField groupField, Collection<FixField> receivedFields) {
            return getMissingFields(requiredGroupFields.get(groupField), receivedFields);
        }

        private static final class GroupFieldOrder {
            private final int[] memberIndexAsc;
            private final int[] orderAtSorted;

            GroupFieldOrder(int[] memberIndexAsc, int[] orderAtSorted) {
                this.memberIndexAsc = memberIndexAsc;
                this.orderAtSorted = orderAtSorted;
            }
        }
    }
}