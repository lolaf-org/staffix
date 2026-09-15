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
 * A message that could not be decoded, and the root of the hierarchy the session layer decides on.
 *
 * <p>{@link #shouldTriggerDisconnect(boolean)} is what that decision reads, and it is the only thing separating
 * these subclasses from each other: the default is to tear the connection down, and each subclass that can be
 * answered without doing so overrides it. A validation failure earns a Reject and the session lives; a too-low
 * MsgSeqNum without PossDupFlag does not.
 */
public class DecodingException extends Exception {

    public DecodingException(String msg) {
        super(msg);
    }

    public DecodingException(String msg, Exception cause) {
        super(msg, cause);
    }

    /**
     * Whether a decoding failure caused by this exception should tear down the session.
     * The default is {@code true}: an unknown/structural decoding failure is treated as fatal.
     *
     * @param adminMessage {@code true} if the message that failed to decode was a session/admin message
     * @return {@code true} if the session should be disconnected, {@code false} to keep it alive
     */
    public boolean shouldTriggerDisconnect(boolean adminMessage) {
        return true;
    }
}