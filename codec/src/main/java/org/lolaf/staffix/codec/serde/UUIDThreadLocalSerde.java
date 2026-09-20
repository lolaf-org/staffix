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
package org.lolaf.staffix.codec.serde;

import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.ids.MsbLsb;
import org.lolaf.staffix.api.ids.UUIDsGenerator;

import java.util.UUID;

/**
 * UUID serde returning thread local instances of deserialized UUIDs. Serialization and the canonical text
 * conversion are {@link UUIDSerde}'s; this class only replaces the {@link UUID} a decoded value lands in, parsing
 * into a reused instance instead of allocating one per message.
 *
 * <p>The reused instances belong to the {@code staffix-codec} {@link UUIDsGenerator}, so a decoded UUID never
 * overwrites one handed out by {@link UUIDsGenerator#instance()}. As with every thread local UUID, the value
 * returned by {@link #deserialize(DeserializationContext)} is only valid until the next deserialization on the
 * same thread. Use {@link UUIDsGenerator} directly to generate UUIDs.
 */
public class UUIDThreadLocalSerde extends UUIDSerde {

    private static final UUIDThreadLocalSerde INSTANCE = new UUIDThreadLocalSerde();
    private static final UUIDsGenerator UUIDS = UUIDsGenerator.instance("staffix-codec");

    // scratch holder of the two halves of the value being decoded, so that parsing allocates nothing
    private final FastThreadLocal<MsbLsb> threadLocalMsbLsb = FastThreadLocal.withInitial(MsbLsb::new);

    public static UUIDThreadLocalSerde instance() {
        return INSTANCE;
    }

    /**
     * Parses the 36 ASCII characters at {@code offset} into the {@code dst} holder, in place (no allocation).
     */
    public static MsbLsb parse(MsbLsb dst, byte[] src, int offset) {
        ensureCapacity(src, offset, TEXT_LENGTH);
        ensureHyphenPositions(src, offset);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < BINARY_LENGTH; i++) {
            if (isSeparatorIndex(i)) {
                offset++; // skip the '-'
            }
            int b = (hexValue(src[offset++]) << 4) | hexValue(src[offset++]);
            if (i < 8) {
                msb = (msb << 8) | b;
            } else {
                lsb = (lsb << 8) | b;
            }
        }
        dst.setMsb(msb);
        dst.setLsb(lsb);
        return dst;
    }

    @Override
    public UUID deserialize(DeserializationContext serdeContext) {
        return UUIDS.threadLocalUuid(
                parse(threadLocalMsbLsb.get(), serdeContext.getDeserializationBuffer(), serdeContext.getStartOffset()));
    }
}