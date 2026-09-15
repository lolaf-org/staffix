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
package org.lolaf.staffix.api.serde;

import lombok.Getter;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;

/**
 * A field whose bytes are not a value of its type - letters in an int, a malformed timestamp.
 *
 * <p>Unchecked because it is thrown from the serdes, which sit under every generated encoder and decoder;
 * the session layer catches it and answers with a Reject.
 */
public class IllegalFieldValueException extends RuntimeException {

    @Getter
    private final SessionRejectReasonCodes sessionRejectReasonCode;

    public IllegalFieldValueException(String msg) {
        this(msg, SessionRejectReasonCodes.INCORRECT_DATA_FORMAT_FOR_VALUE);
    }

    public IllegalFieldValueException(String msg, SessionRejectReasonCodes sessionRejectReasonCode) {
        super(msg);
        this.sessionRejectReasonCode = sessionRejectReasonCode;
    }

    public IllegalFieldValueException(String msg, Exception cause) {
        super(msg, cause);
        sessionRejectReasonCode = SessionRejectReasonCodes.INCORRECT_DATA_FORMAT_FOR_VALUE;
    }
}