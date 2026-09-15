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
package org.lolaf.staffix.api.codec;

import lombok.Getter;

/**
 * This is a copy of FIX BusinessRejectReason as we cannot use directly the generated code
 */
public enum BusinessRejectReasonCodes {
    OTHER(0),
    UNKNOWN_ID(1),
    UNKNOWN_SECURITY(2),
    UNSUPPORTED_MESSAGE_TYPE(3),
    APPLICATION_NOT_AVAILABLE(4),
    CONDITIONALLY_REQUIRED_FIELD_MISSING(5),
    NOT_AUTHORIZED(6),
    DELIVER_TO_FIRM_NOT_AVAILABLE_AT_THIS_TIME(7),
    THROTTLE_LIMIT_EXCEEDED(8),
    THROTTLE_LIMIT_EXCEEDED_SESSION_DISCONNECTED(9),
    THROTTLED_MESSAGE_REJECTED_ON_REQUEST(10),
    INVALID_PRICE_INCREMENT(18);

    @Getter
    private final int code;

    BusinessRejectReasonCodes(int code) {
        this.code = code;
    }
}