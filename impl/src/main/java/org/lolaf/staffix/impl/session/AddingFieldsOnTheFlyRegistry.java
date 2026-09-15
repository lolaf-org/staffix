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

import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.fields.*;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.collections.Int2ObjectHashMap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

class AddingFieldsOnTheFlyRegistry implements FieldsRegistry {

    private final FieldsRegistry fieldsRegistry;
    private final Int2ObjectHashMap<FixField> allowedUnknownFields;
    // User defined fields are kept in this session scoped map rather than delegating their registration to the shared,
    // SPI singleton dictionary registry, so that fields added on the fly (or auto-registered as UNKNOWN while parsing)
    // never leak across sessions and cannot poison a tag another session wants to map with a concrete type.
    private final Int2ObjectHashMap<FixField> userDefinedFields;
    private final boolean allowUserDefinedFields;

    public AddingFieldsOnTheFlyRegistry(FixSessionSettings.ValidationSettings validationSettings, FieldsRegistry fieldsRegistry) {
        this.fieldsRegistry = fieldsRegistry;
        this.allowUserDefinedFields = validationSettings.isAllowUserDefinedFields();
        this.allowedUnknownFields = validationSettings.isAllowUnknownFields() ? new Int2ObjectHashMap<>(8) : null;
        this.userDefinedFields = new Int2ObjectHashMap<>(8);
    }

    @Override
    public FixDictionaryId getTargetDictionary() {
        return fieldsRegistry.getTargetDictionary();
    }

    @Override
    public FixField find(int code) {
        FixField match = fieldsRegistry.find(code);
        if (match != null) {
            return match;
        }
        if (FixField.isUserDefined(code)) {
            FixField userDefined = userDefinedFields.get(code);
            if (userDefined != null) {
                return userDefined;
            }
            if (allowUserDefinedFields) {
                // API user has not registered a user defined field for parsing but allows them
                // create the instance of the field on the fly with an unknown type
                return addUserDefinedField(code, FieldType.UNKNOWN, FieldLocation.BODY);
            }
            return null;
        }
        if (allowedUnknownFields != null) {
            return getOrAddFieldIfNeeded(code);
        }
        return null;
    }

    @Override
    public FixField addUserDefinedField(int code, FieldType fieldType, FieldLocation location) {
        if (!FixField.isUserDefined(code)) {
            throw new IllegalArgumentException("Field code " + code + " is outside the user defined tags range, see FixField.isUserDefined(int)");
        }
        // a real dictionary field always wins over a session scoped user defined field for the same code
        FixField dictionaryField = fieldsRegistry.find(code);
        if (dictionaryField != null) {
            return dictionaryField;
        }
        synchronized (userDefinedFields) {
            FixField instance = userDefinedFields.get(code);
            if (instance != null) {
                return instance;
            }
            int nextIndexableFieldIndex = getFields().stream().mapToInt(FixField::getAsInt).max().orElseThrow() + 1;
            instance = new FixFieldImpl(code, nextIndexableFieldIndex, fieldType, location);
            userDefinedFields.put(code, instance);
            userDefinedFields.compact();
            return instance;
        }
    }

    private FixField getOrAddFieldIfNeeded(int code) {
        FixField match = allowedUnknownFields.get(code);
        if (match == null) {
            synchronized (allowedUnknownFields) {
                match = FixField.of(code, FieldType.UNKNOWN, FieldLocation.BODY);
                allowedUnknownFields.put(code, match);
            }
        }
        return match;
    }

    @Override
    public Collection<FixField> getFields() {
        Collection<FixField> dictionaryFields = fieldsRegistry.getFields();
        if (userDefinedFields.isEmpty()) {
            return dictionaryFields;
        }
        List<FixField> allFields = new ArrayList<>(dictionaryFields.size() + userDefinedFields.size());
        allFields.addAll(dictionaryFields);
        allFields.addAll(userDefinedFields.values());
        return allFields;
    }
}