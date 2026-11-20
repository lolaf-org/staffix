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
package org.lolaf.staffix.ha;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.lolaf.staffix.api.msg.CoreMessageType;
import org.lolaf.staffix.api.serde.Hashing;
import org.lolaf.staffix.api.session.FixSessionSettings.HaSessionSettings;
import org.lolaf.staffix.ha.wire.WireFormat;

import java.util.Arrays;
import java.util.List;

/**
 * The message types a session's encrypted replication sends unencrypted, looked up by the MsgType(35) hash a frame
 * carries, on the sender thread: a sorted array, so a lookup allocates nothing.
 */
@EqualsAndHashCode
@ToString
public final class UnencryptedMessageTypes {

    static final UnencryptedMessageTypes NONE = new UnencryptedMessageTypes(false, new int[0]);

    private static final int LOGON = Hashing.hash(CoreMessageType.LOGON.code());

    private final boolean all;
    private final int[] hashes;

    private UnencryptedMessageTypes(boolean all, int[] hashes) {
        this.all = all;
        this.hashes = hashes;
    }

    /**
     * @param codes as {@link HaSessionSettings#getUnencryptedMessageTypes()}, validated
     */
    static UnencryptedMessageTypes of(List<String> codes) {
        if (codes.isEmpty()) {
            return NONE;
        }
        if (codes.contains(HaSessionSettings.ALL_MESSAGE_TYPES)) {
            return new UnencryptedMessageTypes(true, new int[0]);
        }
        int[] hashes = codes.stream().mapToInt(Hashing::hash).sorted().distinct().toArray();
        return new UnencryptedMessageTypes(false, hashes);
    }

    /**
     * False for a message of unknown type and for a Logon, whatever the settings say.
     */
    public boolean isUnencrypted(int messageTypeHash) {
        if (messageTypeHash == WireFormat.NO_MESSAGE_TYPE || messageTypeHash == LOGON) {
            return false;
        }
        return all || Arrays.binarySearch(hashes, messageTypeHash) >= 0;
    }
}
