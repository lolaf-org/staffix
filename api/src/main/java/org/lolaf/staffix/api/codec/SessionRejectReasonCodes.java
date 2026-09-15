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
 * This is a copy of FIX SessionRejectReason as we cannot use directly the generated code
 */
public enum SessionRejectReasonCodes {
    /**
     * Either FIX initiator or FIX Acceptor is sending any tag other than specified in FIX Specification for that particular FIX Version
     */
    INVALID_TAG_NUMBER(0),
    REQUIRED_TAG_MISSING(1),
    TAG_NOT_DEFINED_FOR_THIS_MESSAGE_TYPE(2),
    /**
     * In case any of sender FIX engine is sending custom tag and that is not configured or supported by the fix engine.
     */
    UNDEFINED_TAG(3),
    TAG_SPECIFIED_WITHOUT_A_VALUE(4),
    VALUE_IS_INCORRECT(5),
    INCORRECT_DATA_FORMAT_FOR_VALUE(6),
    DECRYPTION_PROBLEM(7),
    SIGNATURE_PROBLEM(8),
    COMPID_PROBLEM(9),
    SENDING_TIME_ACCURACY_PROBLEM(10),
    INVALID_MSGTYPE(11),
    XML_VALIDATION_ERROR(12), // not applicable to tag=value parsing; only meaningful for FIXML or app-level XmlData(213) schema validation
    TAG_APPEARS_MORE_THAN_ONCE(13),
    TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER(14),
    REPEATING_GROUP_FIELDS_OUT_OF_ORDER(15),
    INCORRECT_NUM_IN_GROUP_COUNT_FOR_REPEATING_GROUP(16),
    NON_DATA_VALUE_INCLUDES_FIELD_DELIMITER(17),
    INVALID_UNSUPPORTED_APPL_VER(18),
    OTHER(99);

    @Getter
    private final int code;

    SessionRejectReasonCodes(int code) {
        this.code = code;
    }
}