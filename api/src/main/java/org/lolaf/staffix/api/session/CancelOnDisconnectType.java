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
package org.lolaf.staffix.api.session;

/**
 * What the counterparty should do with this session's resting orders when it goes away - a convention many
 * venues support, declared at Logon.
 *
 * <p>Disconnect and logout are distinguished because they mean different things: a logout is an orderly goodbye
 * and a dropped connection may be a transient network fault the session recovers from moments later. A venue
 * that cancels on both is safer; one that cancels only on disconnect keeps orders across a planned restart.
 */
public enum CancelOnDisconnectType {
    DO_NOT_CANCEL_ON_DISCONNECT_OR_LOGOUT,
    CANCEL_ON_DISCONNECT_ONLY,
    CANCEL_ON_LOGOUT_ONLY,
    CANCEL_ON_DISCONNECT_OR_LOGOUT;
}