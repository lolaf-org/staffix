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

/**
 * Where a session is between having a socket and being able to trade on it; operators see {@link
 * org.lolaf.staffix.api.session.FixSessionStatus} instead. Only {@code LOGGED_IN} admits application traffic, and
 * {@code LOGGED_OUT} is a Logout exchanged on a connection not yet closed.
 */
public enum FixSessionState {

    LOGGED_IN,
    LOGGED_OUT,
    CONNECTED,
    DISCONNECTED
}
