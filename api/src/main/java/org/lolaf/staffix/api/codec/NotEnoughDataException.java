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
 * The network buffer holds only part of a message.
 *
 * <p>Not an error at all - TCP is a stream and a message arrives across as many reads as it takes. The parser
 * unwinds, keeps what it has and decodes again when more arrives, so this is the one exception here that never
 * disconnects and never reaches an application.
 */
public class NotEnoughDataException extends DecodingException {

    public NotEnoughDataException() {
        super("Not enough data in network buffer to fully process FIX message");
    }

    @Override
    public boolean shouldTriggerDisconnect(boolean adminMessage) {
        // the buffer is just short: the message will be reprocessed once more data arrives
        return false;
    }
}