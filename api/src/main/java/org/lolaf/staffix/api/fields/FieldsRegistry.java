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
package org.lolaf.staffix.api.fields;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.FixDictionaryId;

import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Every field a dictionary defines, by tag.
 *
 * <p>{@link #addUserDefinedField} exists because a counterparty's custom tags are not in any dictionary and
 * would otherwise be unparseable; registering one at startup is how an application says what a tag above 5000
 * means. Instances come from the generated FIX packages through {@link java.util.ServiceLoader}.
 */
public interface FieldsRegistry {

    FixDictionaryId getTargetDictionary();

    /**
     * Find a fix field instance
     *
     * @param code the field code
     * @return a singleton instance for the given code or null if the registry does not have such field
     */
    FixField find(int code);

    Collection<FixField> getFields();

    /**
     * Adds a user defined field to the registry, the code having to be one - see {@link FixField#isUserDefined(int)}.
     * A tag the standard owns is refused rather than registered as user defined, whether or not this dictionary
     * happens to define it.
     *
     * @param code      the field code
     * @param fieldType the field type
     * @param location  the field location
     * @return the created field
     */
    FixField addUserDefinedField(int code, FieldType fieldType, FieldLocation location);

    @Slf4j
    class Registry {

        private static final Collection<FieldsRegistry> SPI_INSTANCES = loadInstances();

        private Registry() {

        }

        public static FieldsRegistry getInstance(FixDictionaryId fixDictionaryId) {
            return SPI_INSTANCES.stream().filter(r -> r.getTargetDictionary().equals(fixDictionaryId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unable to find any FieldsRegistry SPI instance for dictionary " + fixDictionaryId));
        }

        private static Collection<FieldsRegistry> loadInstances() {
            List<FieldsRegistry> instances = ServiceLoader.load(FieldsRegistry.class)
                    .stream().map(ServiceLoader.Provider::get).collect(Collectors.toList());
            log.info("Loaded FieldsRegistry SPI instances for dictionaries: {}", instances.stream().map(FieldsRegistry::getTargetDictionary).collect(Collectors.toList()));
            return instances;
        }
    }
}
