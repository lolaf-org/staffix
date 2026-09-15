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
package org.lolaf.staffix.stores.loggers.core;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lolaf.staffix.api.logging.LogObfuscator;
import org.lolaf.staffix.api.msg.MessageType;

import java.nio.ByteBuffer;

/**
 * Masks Username(553) in a logged message, for deployments where the account itself is sensitive.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UsernameObfuscator extends LogObfuscator.AbstractLogObfuscator {

    private static final UsernameObfuscator INSTANCE = new UsernameObfuscator();

    private static final String LOGON_MESSAGE_TYPE = "A";
    private static final byte FIVE = '5';
    private static final byte THREE = '3';

    public static UsernameObfuscator getInstance() {
        return INSTANCE;
    }

    @Override
    public boolean isForMessageType(MessageType messageType) {
        return messageType.isAdmin() && messageType.code().equals(LOGON_MESSAGE_TYPE);
    }

    @Override
    protected boolean positionMatchesFieldStart(ByteBuffer fixMessageToObfuscate, int position) {
        return fixMessageToObfuscate.get(position) == FIVE
                && fixMessageToObfuscate.get(position + 1) == FIVE
                && fixMessageToObfuscate.get(position + 2) == THREE
                && fixMessageToObfuscate.get(position + 3) == EQUALS_CHAR;
    }

    @Override
    protected int getFieldNumberCharsLen() {
        // 553 = 3 chars
        return 3;
    }
}
