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

import org.lolaf.staffix.api.serde.SerDe;

/**
 * Basic implementation of a fix field
 */
public class FixFieldImpl implements FixField {

    private final byte[] serialized;
    private final int code;
    private final int index;
    private final int checksum;
    private final FieldType type;
    private final FieldLocation location;

    public FixFieldImpl(int code, FieldType type, FieldLocation location) {
        this(code, -1, type, location);
    }

    public FixFieldImpl(int code, int index, FieldType type, FieldLocation location) {
        this.code = code;
        this.index = index;
        this.type = type;
        this.serialized = (code + "=").getBytes(SerDe.CHARSET);
        int checksumLocal = 0;
        for (byte b : serialized) {
            checksumLocal += b;
        }
        this.checksum = checksumLocal;
        this.location = location;
    }

    @Override
    public int getAsInt() {
        if (index == -1) {
            throw new IllegalStateException("Should never have been called");
        }
        return index;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public byte[] serialized() {
        return serialized;
    }

    @Override
    public int checksum() {
        return checksum;
    }

    @Override
    public FieldType getType() {
        return type;
    }

    @Override
    public FieldLocation getLocation() {
        return location;
    }
}