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

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.session.FixSession.FixSessionType;
import org.lolaf.staffix.ha.SessionSettingsDigests.Digest;
import org.lolaf.staffix.ha.replication.wire.WireFormatException;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The records of the session settings stream. A payload is the session type 1, then the store, session id, digest,
 * store identity and document, each a length 4 and its UTF-8 bytes; the content type says which kind of record it is.
 */
@UtilityClass
final class SessionSettingsRecords {

    /**
     * A session's digest, with its document when its store is replicated.
     */
    static final byte DIGEST = 1;
    /**
     * The session was removed from the leader's store, for the follower to remove it too.
     */
    static final byte REMOVED = 2;
    /**
     * The leader no longer has the session, without having seen it removed: the follower only stops comparing it.
     */
    static final byte FORGOTTEN = 3;

    private static final int STRINGS = 5;

    static byte contentType(Digest digest) {
        return digest.isRemoved() ? REMOVED : DIGEST;
    }

    static ByteBuffer encode(Digest digest) {
        byte[][] strings = {utf8(digest.getStore()), utf8(digest.getFixSessionId()), utf8(digest.getValue()),
                utf8(digest.getStoreIdentity()), utf8(digest.getDocument())};
        int length = 1;
        for (byte[] string : strings) {
            length += Integer.BYTES + string.length;
        }
        ByteBuffer payload = ByteBuffer.allocate(length).put(digest.getFixSessionType().getCode());
        for (byte[] string : strings) {
            payload.putInt(string.length).put(string);
        }
        return payload.flip().asReadOnlyBuffer();
    }

    /**
     * @param buffer read from {@code index} to {@code index + length} without moving it
     */
    static Digest decode(byte contentType, ByteBuffer buffer, int index, int length) {
        if (contentType != DIGEST && contentType != REMOVED && contentType != FORGOTTEN) {
            throw new WireFormatException("Unknown session settings record type " + contentType);
        }
        int end = index + length;
        if (length < 1) {
            throw new WireFormatException("An empty session settings record");
        }
        FixSessionType fixSessionType = fixSessionType(buffer.get(index));
        String[] strings = new String[STRINGS];
        int p = index + 1;
        for (int i = 0; i < STRINGS; i++) {
            if (end - p < Integer.BYTES) {
                throw new WireFormatException("A session settings record of " + length + " bytes ends before its strings");
            }
            int stringLength = buffer.getInt(p);
            p += Integer.BYTES;
            if (stringLength < 0 || stringLength > end - p) {
                throw new WireFormatException("A session settings record of " + length + " bytes declares a string of "
                        + stringLength);
            }
            strings[i] = utf8(buffer, p, stringLength);
            p += stringLength;
        }
        if (p != end) {
            throw new WireFormatException("A session settings record of " + length + " bytes has " + (end - p)
                    + " bytes past its strings");
        }
        return new Digest(strings[0], fixSessionType, strings[1], strings[2], strings[3], strings[4], contentType == REMOVED);
    }

    private static byte[] utf8(String string) {
        return string.getBytes(StandardCharsets.UTF_8);
    }

    private static String utf8(ByteBuffer buffer, int index, int length) {
        byte[] bytes = new byte[length];
        buffer.duplicate().position(index).get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static FixSessionType fixSessionType(byte code) {
        try {
            return FixSessionType.from(code);
        } catch (IllegalStateException e) {
            throw new WireFormatException(e.getMessage());
        }
    }
}
