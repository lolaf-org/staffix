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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * The MsgType(35) codes of the session-layer messages, which are the same in every FIX version.
 *
 * <p>Constants rather than a registry lookup because the session layer needs them before it knows which
 * dictionary a connection is using - a Logon has to be recognised to find that out.
 */
@Getter
@Accessors(fluent = true)
@RequiredArgsConstructor
public enum CoreMessageType {

    LOGON("A"),
    LOGOUT("5"),
    HEARTBEAT("0"),
    REJECT("3"),
    BUSINESS_MESSAGE_REJECT("j"),
    TEST_REQUEST("1"),
    RESEND_REQUEST("2"),
    SEQUENCE_REQUEST("4"),
    XML_NON_FIX("n");

    private final String code;

    /**
     * Compares by code, so a {@link MessageType} from any dictionary matches.
     */
    public boolean matches(MessageType messageType) {
        return code.equals(messageType.code());
    }
}
