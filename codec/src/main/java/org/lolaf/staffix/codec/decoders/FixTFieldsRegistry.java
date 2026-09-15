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
import org.lolaf.staffix.api.fields.*;
import org.lolaf.staffix.api.version.FixVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.lolaf.staffix.collections.Int2ObjectHashMap;

import java.util.Collection;
import java.util.function.IntSupplier;

/**
 * Serves a FIXT.1.1 session's fields, looking in the session-layer registry first and the application
 * dictionary second.
 *
 * <p>Needed because from FIX.5.0 the two are versioned separately and a message's fields may come from either.
 */
public class FixTFieldsRegistry implements FieldsRegistry {

    private final Int2ObjectHashMap<FixField> fixFields;
    private final FixDictionaryId fixDictionaryId;

    private FixTFieldsRegistry(FixDictionaryId fixDictionaryId, FieldsRegistry dictionaryFieldsRegistry, FieldsRegistry fixtFieldsRegistry) {
        this.fixDictionaryId = fixDictionaryId;
        this.fixFields = new Int2ObjectHashMap<>(10, 0.1f);
        // A field code (e.g. EncodedTextLen 354, EncodedText 355, Text 58) can be defined in both the FIXT transport
        // dictionary and the application dictionary. Application defined field must take precedence and keeps its
        // original (dense) index from the application dictionary registry.
        int maxIndex = -1;
        for (FixField f : dictionaryFieldsRegistry.getFields()) {
            fixFields.put(f.getCode(), f);
            maxIndex = Math.max(maxIndex, f.getAsInt());
        }
        // FIXT transport-only fields (codes absent from the application dictionary) are re-indexed into a range
        // disjoint from the application dictionary indices. Both registries index their own fields densely starting
        // at 0, so without this a session-layer message that mixes application-dictionary session fields (as happens
        // when a combined FIX 4.x dictionary is reused under FIXT) with a FIXT-only field such as DefaultApplVerID
        // (1137) would see two distinct fields collapse onto the same slot in the index-addressed received/required
        // field collections, corrupting required-field validation.
        int nextIndex = maxIndex + 1;
        for (FixField f : fixtFieldsRegistry.getFields()) {
            if (!fixFields.containsKey(f.getCode())) {
                fixFields.put(f.getCode(), new FixFieldImpl(f.getCode(), nextIndex++, f.getType(), f.getLocation()));
            }
        }
        this.fixFields.compact();
    }

    public static FieldsRegistry get(FixDictionaryId fixDictionaryId, FixVersion fixVersion) {
        FieldsRegistry dictionaryFieldsRegistry = FieldsRegistry.Registry.getInstance(fixDictionaryId);
        if (fixVersion instanceof FixtVersion) {
            return new FixTFieldsRegistry(fixDictionaryId, dictionaryFieldsRegistry, FixtFieldsRegistry.Registry.getInstance((FixtVersion) fixVersion));
        }
        return dictionaryFieldsRegistry;
    }

    @Override
    public FixDictionaryId getTargetDictionary() {
        return fixDictionaryId;
    }

    @Override
    public FixField find(int code) {
        return fixFields.get(code);
    }

    @Override
    public Collection<FixField> getFields() {
        return fixFields.values();
    }

    @Override
    public FixField addUserDefinedField(int code, FieldType fieldType, FieldLocation location) {
        if (!FixField.isUserDefined(code)) {
            throw new IllegalArgumentException("Field code " + code + " is outside the user defined tags range, see FixField.isUserDefined(int)");
        }
        synchronized (fixFields) {
            FixField instance = fixFields.get(code);
            if (instance != null) {
                return instance;
            }
            int nextIndexableFieldIndex = getFields().stream().map(IntSupplier::getAsInt).max(Integer::compareTo).orElseThrow() + 1;
            instance = new FixFieldImpl(code, nextIndexableFieldIndex, fieldType, location);
            fixFields.put(code, instance);
            fixFields.compact();
            return instance;
        }
    }
}