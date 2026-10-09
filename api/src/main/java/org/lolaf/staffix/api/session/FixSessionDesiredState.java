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
 * What a session is asked to be, by its settings or an operator; where it actually is, {@link FixSessionStatus} says.
 */
public enum FixSessionDesiredState {

    /**
     * Logs on within session time and stays on: an initiator dials, an acceptor answers a Logon.
     */
    LOGGED_IN,
    /**
     * Out of service with its configuration kept: an initiator does not dial, an acceptor answers a Logon with a
     * Logout saying why.
     */
    LOGGED_OUT
}
