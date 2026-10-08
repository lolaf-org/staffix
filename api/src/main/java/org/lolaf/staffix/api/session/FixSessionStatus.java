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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Where a session stands for an operator. Each value is the first that applies, in the order declared.
 */
@Getter
@RequiredArgsConstructor
public enum FixSessionStatus {

    LOGGED_IN(1),
    /**
     * Held logged out by its desired state, inside session time or not.
     */
    LOGGED_OUT_BY_OPERATOR(3),
    /**
     * Logged out because its session schedule is closed, as planned.
     */
    LOGGED_OUT_OUTSIDE_SESSION_TIME(2),
    /**
     * Meant to be logged in and is not, including while it connects: the status worth an alert.
     */
    LOGGED_OUT_INSIDE_SESSION_TIME(0);

    /**
     * The value the {@code session.logon.status} gauge exports, fixed whatever the declaration order; 1 is logged in.
     */
    private final int code;
}
