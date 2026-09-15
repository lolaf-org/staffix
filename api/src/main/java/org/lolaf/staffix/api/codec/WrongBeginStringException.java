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
 * BeginString(8) is not the FIX version this session was configured for.
 *
 * <p>The session still ends, but not immediately: the specification requires a Logout naming the offending
 * BeginString before the connection is dropped, so this does not ask for a disconnect of its own.
 */
@Getter
public class WrongBeginStringException extends DecodingException {

    private final String expectedBeginString;
    private final String receivedBeginString;

    public WrongBeginStringException(String expectedBeginString, String receivedBeginString) {
        super("Wrong BeginString received, expecting " + expectedBeginString + " but got " + receivedBeginString);
        this.expectedBeginString = expectedBeginString;
        this.receivedBeginString = receivedBeginString;
    }

    @Override
    public boolean shouldTriggerDisconnect(boolean adminMessage) {
        // handled by sending a Logout referencing the incorrect BeginString and then disconnecting, not by tearing the
        // connection down straight away
        return false;
    }
}
