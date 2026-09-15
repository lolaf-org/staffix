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

import org.lolaf.staffix.api.serde.SerDe;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode
class MessageTypeImpl implements MessageType {

    private final String code;
    private final boolean admin;
    private final byte[] serialized;

    public MessageTypeImpl(String code, boolean admin) {
        this.code = code;
        this.admin = admin;
        this.serialized = code.getBytes(SerDe.CHARSET);
    }

    @Override
    public int getAsInt() {
        // important indexable collection will simply ignore list or map operations if index is smaller than zero
        return -1;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public boolean isAdmin() {
        return admin;
    }

    @Override
    public boolean isStorable() {
        return !admin || CoreMessageType.REJECT.equals(code);
    }

    @Override
    public byte[] serialized() {
        return serialized;
    }

}
