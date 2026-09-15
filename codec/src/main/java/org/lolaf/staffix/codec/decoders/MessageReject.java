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
package org.lolaf.staffix.codec.decoders;

import lombok.Builder;
import lombok.Value;
import org.lolaf.staffix.api.codec.BusinessRejectReasonCodes;
import org.lolaf.staffix.api.codec.SessionRejectReasonCodes;

/**
 * One reason a message is being rejected: the text, the offending tag, and a code from each of the two sets.
 *
 * <p>Both codes are carried because the answer depends on the message - a session-level Reject(35=3) for an
 * admin message, a BusinessMessageReject(35=j) for an application one - and the parser does not decide which.
 */
@Value
@Builder(toBuilder = true)
public class MessageReject {

    String message;
    int refTagId;
    SessionRejectReasonCodes sessionRejectReasonCode;
    BusinessRejectReasonCodes businessRejectReasonCode;

}