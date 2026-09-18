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


import org.lolaf.staffix.api.serde.SerDe;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;

import java.util.function.Function;

/**
 * Allows to deserialize a String type field into a cached String from a ThreadLocal and using Unsafe to avoid any object allocation
 * The returned String of the {@link #deserialize(DeserializationContext)} can only be used in the scope of the current processing thread
 * You can create your own instance to use with your decoder or use the global static instance trough {@link #instance()}
 *
 * <p><b>Degraded mode.</b> The zero-allocation trick overwrites a thread-local {@code String}'s internal
 * fields via Unsafe, which requires {@code --add-opens java.base/jdk.internal.misc=ALL-UNNAMED}. When that
 * flag is absent this serde still works, but {@link #deserialize(DeserializationContext)} falls back to
 * allocating a fresh {@code String} on every call (and its result is then a normal, thread-independent
 * instance). A warning is logged once at startup; throughput and GC pressure will be significantly worse.
 */
@Slf4j
public class StringThreadLocalSerde extends StringBaseSerde {

    private static final UnsafeOperations UNSAFE_OPERATIONS = resolveUnsafeOperations();
    private static final int MAX_CACHED_BYTE_ARRAY_FOR_STRING_SIZE = Integer.parseInt(System.getProperty("org.lolaf.staffix.codec.serde.StringThreadLocalSerde.cached.bytes.array.max.size", "32"));
    private static final StringThreadLocalSerde INSTANCE = new StringThreadLocalSerde();

    private final FastThreadLocal<ThreadLocalStringContext> threadLocalStringContexts;
    private final long stringValueFieldOffset;
    private final long hashFieldOffset;
    private final Function<DeserializationContext, String> deserializer;

    public StringThreadLocalSerde() {
        super();
        threadLocalStringContexts = FastThreadLocal.withInitial(ThreadLocalStringContext::new);
        if (UNSAFE_OPERATIONS != null) {
            stringValueFieldOffset = UNSAFE_OPERATIONS.objectFieldOffset(String.class, "value");
            hashFieldOffset = UNSAFE_OPERATIONS.objectFieldOffset(String.class, "hash");
            if (stringValueFieldOffset == UnsafeOperations.UNKNOWN_FIELD_OFFSET) {
                throw new IllegalStateException("Unable to find String value field offset");
            }
            if (hashFieldOffset == UnsafeOperations.UNKNOWN_FIELD_OFFSET) {
                throw new IllegalStateException("Unable to find String hash field offset");
            }
            deserializer = this::deserializeReusingThreadLocalString;
        } else {
            stringValueFieldOffset = UnsafeOperations.UNKNOWN_FIELD_OFFSET;
            hashFieldOffset = UnsafeOperations.UNKNOWN_FIELD_OFFSET;
            deserializer = this::deserializeAllocatingNewString;
        }
    }


    private static UnsafeOperations resolveUnsafeOperations() {
        if (!UnsafeOperationsApi.isAvailable()) {
            log.warn("StringThreadLocalSerde is running WITHOUT Unsafe (missing --add-opens "
                    + "java.base/jdk.internal.misc=ALL-UNNAMED): its zero-allocation optimization is DISABLED and "
                    + "every deserialize allocates a new String — throughput and GC pressure will be significantly "
                    + "degraded. Add --add-opens java.base/jdk.internal.misc=ALL-UNNAMED to restore it.");
            return null;
        }
        return UnsafeOperationsApi.get();
    }

    public static StringThreadLocalSerde instance() {
        return INSTANCE;
    }

    @Override
    public String deserialize(DeserializationContext serdeContext) {
        return deserializer.apply(serdeContext);
    }

    // Zero-allocation: overwrites a thread-local String's internal byte[] and hash with the wire bytes. The
    // returned String is only valid on the current thread until the next deserialize (see class javadoc).
    private String deserializeReusingThreadLocalString(DeserializationContext serdeContext) {
        ThreadLocalStringContext threadLocalStringContext = threadLocalStringContexts.get();
        byte[] cachedByteArray = threadLocalStringContext.byteArraysCache.forSize(serdeContext.getLength());
        System.arraycopy(serdeContext.getDeserializationBuffer(), serdeContext.getStartOffset(), cachedByteArray, 0, serdeContext.getLength());
        String tlString = threadLocalStringContext.tlString;
        UNSAFE_OPERATIONS.putReference(tlString, stringValueFieldOffset, cachedByteArray);
        UNSAFE_OPERATIONS.putInt(tlString, hashFieldOffset, 0);
        return tlString;
    }

    // Degraded fallback when Unsafe is unavailable: allocates a fresh String each call. Unlike the
    // zero-allocation path the result is a normal, independent instance (safe to keep beyond the current
    // thread), just far more costly. Bytes are decoded as ISO-8859-1 to match the Unsafe path, which reuses a
    // LATIN1 thread-local String's backing array.
    private String deserializeAllocatingNewString(DeserializationContext serdeContext) {
        return new String(serdeContext.getDeserializationBuffer(), serdeContext.getStartOffset(),
                serdeContext.getLength(), SerDe.CHARSET);
    }

    private static class ThreadLocalStringContext {
        // VERY IMPORTANT assign a string that cannot be taken from the internal JVM string pool !
        final String tlString = "ThreadLocalStringContext" + System.nanoTime();
        final ByteArraysCache byteArraysCache = new ByteArraysCache(MAX_CACHED_BYTE_ARRAY_FOR_STRING_SIZE);
    }
}