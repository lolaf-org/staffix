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
import org.lolaf.staffix.api.fields.FixField;

import java.util.List;

/**
 * A message that parsed but does not satisfy its dictionary: a required field or group missing, or a value the
 * field does not admit.
 *
 * <p>Carries both a {@code SessionRejectReasonCodes} and a {@code BusinessRejectReasonCodes} because the answer
 * depends on the message: a session-level Reject(35=3) for an admin message, a BusinessMessageReject(35=j) for an
 * application one, and the two code sets are not interchangeable.
 *
 * <p>The session survives - see {@link #shouldTriggerDisconnect(boolean)}. The list constructor collects every
 * failure found in one message so the peer is told all of them at once rather than one per round trip.
 */
@Getter
public class ValidationException extends DecodingException {

    private final transient FixField field;
    private final SessionRejectReasonCodes sessionRejectReasonCode;
    private final BusinessRejectReasonCodes businessRejectReasonCode;
    private final List<ValidationException> validationExceptions;

    public ValidationException(List<ValidationException> validationExceptions) {
        super("validationExceptions");
        this.field = null;
        this.sessionRejectReasonCode = null;
        this.businessRejectReasonCode = null;
        this.validationExceptions = validationExceptions;
    }

    public ValidationException(FixField field, String message) {
        super(message);
        this.field = field;
        this.sessionRejectReasonCode = SessionRejectReasonCodes.OTHER;
        this.businessRejectReasonCode = BusinessRejectReasonCodes.OTHER;
        this.validationExceptions = null;
    }

    public ValidationException(FixField field, String message, Exception cause) {
        super(message, cause);
        this.field = field;
        this.sessionRejectReasonCode = SessionRejectReasonCodes.OTHER;
        this.businessRejectReasonCode = BusinessRejectReasonCodes.OTHER;
        this.validationExceptions = null;
    }

    public ValidationException(FixField field, String message, SessionRejectReasonCodes sessionRejectReasonCode, BusinessRejectReasonCodes businessRejectReasonCode) {
        super(message);
        this.field = field;
        this.sessionRejectReasonCode = sessionRejectReasonCode;
        this.businessRejectReasonCode = businessRejectReasonCode;
        this.validationExceptions = null;
    }

    public ValidationException(FixField field, String message, Exception cause, SessionRejectReasonCodes sessionRejectReasonCode, BusinessRejectReasonCodes businessRejectReasonCode) {
        super(message, cause);
        this.field = field;
        this.sessionRejectReasonCode = sessionRejectReasonCode;
        this.businessRejectReasonCode = businessRejectReasonCode;
        this.validationExceptions = null;
    }

    @Override
    public boolean shouldTriggerDisconnect(boolean adminMessage) {
        // a validation failure is answered with a reject, the session stays alive
        return false;
    }
}