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


import java.util.function.IntSupplier;

/**
 * One field of a dictionary: its tag, its type, and where in the message it is allowed to appear.
 *
 * <p>{@link #checksum()} is precomputed because every encoded message sums the bytes of every {@code tag=} it
 * writes, and computing that per message per field is work the dictionary already knows the answer to.
 * {@link #getLocation()} distinguishes header, body and trailer, which is what makes a field in the wrong part
 * of a message a reject rather than a value.
 */
public interface FixField extends IntSupplier {

    /**
     * Whether a tag belongs to the user defined range rather than to the standard, which the FIX tag registry
     * closes at both ends:
     * <ul>
     *     <li>1 to 4999 - standard, assigned by the FIX Trading Community;</li>
     *     <li>5000 to 9999 - user defined for inter-firm use, registered with FIX. Exhausted, which is what led to
     *     the range below;</li>
     *     <li>10000 to 19999 - reserved for use inside a single firm, unregistered;</li>
     *     <li>20000 to 39999 - user defined for bilateral use between two parties, unregistered, approved by the
     *     Global Technical Committee Governance Board in December 2009;</li>
     *     <li>40000 and above - standard again. The Global Technical Committee took that range for itself in EP161
     *     "to accommodate the growth" of components and fields, while carrying on assigning low tags to fields "that
     *     may be more commonly applicable" - tags below 4000 were still being minted at EP300.</li>
     * </ul>
     * So the range is bounded above as well as below, and it is the same for every FIX version: a dictionary from
     * before EP161 simply holds no tag of 40000 or more, which is why this needs no version to answer. Testing
     * {@code code >= 5000}, as this used to, calls 3127 standard fields of FIX Latest user defined - half its
     * dictionary, repeating group counters included.
     *
     * @param code the field tag
     */
    static boolean isUserDefined(int code) {
        return code >= 5000 && code <= 39999;
    }

    /**
     * Create an instance of FixField, warning the provided implementation
     * will not match against instances stored into {@link FieldsRegistry} which are singleton by design.
     * This API method should not be used directly, use the FixField return by an {@link FieldsRegistry} instance for your dictionary instead
     */
    static FixField of(int code, FieldType fieldType, FieldLocation location) {
        return new FixFieldImpl(code, fieldType, location);
    }

    /**
     * Whether this field is a user defined one, see {@link #isUserDefined(int)}.
     */
    default boolean isUserDefined() {
        return isUserDefined(getCode());
    }

    int getCode();

    /**
     * Return a byte array with a representation of the string serialized field in the form $fieldCode=
     */
    byte[] serialized();

    /**
     * The checksum of the serialized form of the field
     */
    int checksum();

    FieldType getType();

    FieldLocation getLocation();

    default boolean hasValues() {
        return false;
    }

    default Enum<? extends ValuesEnum>[] getValues() {
        return null;
    }


    interface ValuesEnum {

        String description();
    }

    interface IntValuesEnum extends ValuesEnum {

        int code();

        byte[] serialized();

    }

    interface StringValuesEnum extends ValuesEnum {

        String code();

        byte[] serialized();
    }

    interface CharValuesEnum extends ValuesEnum {

        char code();

    }
}