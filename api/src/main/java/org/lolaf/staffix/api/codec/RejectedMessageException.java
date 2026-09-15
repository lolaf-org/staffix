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

/**
 * A message the session layer refuses at the session level, answered with a Reject(35=3).
 *
 * <p>The session keeps running: a Reject rejects one message, not the connection. The cases that must also
 * disconnect - SendingTime accuracy, CompID mismatches - are driven explicitly by the session layer rather than
 * inferred from the exception.
 */
public class RejectedMessageException extends DecodingException {

    public RejectedMessageException(String rejectionMessage) {
        super(rejectionMessage);
    }

    public RejectedMessageException(String rejectionMessage, Exception cause) {
        super(rejectionMessage, cause);
    }

    @Override
    public boolean shouldTriggerDisconnect(boolean adminMessage) {
        // A session-level Reject(35=3) rejects a single message and the session keeps running. Cases that additionally
        // warrant a disconnect (e.g. SendingTime accuracy or CompID problems) are handled explicitly by sending a
        // Logout after the Reject, not by tearing down the connection here.
        return false;
    }
}
