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

/**
 * The MsgType(35) codes of the session-layer messages, which are the same in every FIX version.
 *
 * <p>Constants rather than a registry lookup because the session layer needs them before it knows which
 * dictionary a connection is using - a Logon has to be recognised to find that out.
 */
public interface CoreMessageType {

    String LOGON = "A";
    String LOGOUT = "5";
    String HEARTBEAT = "0";
    String REJECT = "3";
    String BUSINESS_MESSAGE_REJECT = "j";
    String TEST_REQUEST = "1";
    String RESEND_REQUEST = "2";
    String SEQUENCE_REQUEST = "4";
    String XML_NON_FIX = "n";


}
