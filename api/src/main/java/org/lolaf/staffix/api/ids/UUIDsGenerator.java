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
package org.lolaf.staffix.api.ids;

import lombok.experimental.UtilityClass;
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.ringos.unsafe.UnsafeOperations;
import org.lolaf.ringos.unsafe.UnsafeOperationsApi;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One-stop factory for JDK {@link UUID} values, in both flavours the library needs:
 *
 * <ul>
 *     <li>{@link #newV7()} / {@link #newV4()} - a freshly allocated {@link UUID}, as any caller would expect;</li>
 *     <li>{@link #threadLocalV7()} / {@link #threadLocalV4()} - a <b>thread local, reused</b> {@link UUID} whose two
 *     64-bit halves are overwritten in place, so a message path can carry a {@link UUID} without allocating one per
 *     message.</li>
 * </ul>
 *
 * <p><b>Version 7</b> values come from a {@link UUIDv7} sequence (time-ordered and strictly increasing, see that
 * class for the per-millisecond ceiling). <b>Version 4</b> values are the classic random ones: the same bit layout
 * and the same cryptographic strength as {@link UUID#randomUUID()}, drawn from a per-thread {@link SecureRandom}
 * instead of a shared one so that concurrent callers do not contend, and read as one 16-byte draw into a reused
 * buffer rather than two {@code nextLong()} calls. Note that a version 4 UUID is never free of garbage whatever we
 * do: the JDK's {@link SecureRandom} allocates inside every call. Only the version 7 path reaches zero.
 *
 * <p><b>Instances.</b> {@link #instance()} is the process-wide generator, sharing its v7 sequence with
 * {@link UUIDv7#instance()}. {@link #instance(String)} returns a generator dedicated to an id, created on first use
 * and backed by the {@link UUIDv7} of that same id ({@link UUIDv7#instance(String)}): each id owns an independent v7
 * sequence (so two subsystems do not share the 4096/ms counter) and independent thread local slots (so one subsystem
 * never overwrites a {@link UUID} the other is still holding).
 *
 * <p><b>Lifetime of the thread local values.</b> The {@code threadLocal*} methods hand back an instance owned by
 * this generator and by the calling thread. It stays valid until the <b>next call to the same method on the same
 * thread</b>, which mutates it in place. Read it, serialize it, pass it down the stack - but never store it, never
 * hand it to another thread, and never put it in a collection that outlives the call. When a value has to survive,
 * use {@link #newV7()} / {@link #newV4()} instead.
 *
 * <p>The in-place mutation needs {@link UnsafeOperations}; the required {@code --add-opens} is resolved lazily, on
 * the first {@code threadLocal*} call, so merely using {@link #newV7()} / {@link #newV4()} works on any runtime.
 */
public final class UUIDsGenerator {

    // one SecureRandom per thread rather than the shared one UUID.randomUUID() draws from, so senders never contend
    private static final FastThreadLocal<SecureRandom> SECURE_RANDOMS = FastThreadLocal.withInitial(SecureRandom::new);
    private static final RandomBytesSource DEFAULT_V4_RANDOM = dst -> SECURE_RANDOMS.get().nextBytes(dst);

    private static final UUIDsGenerator INSTANCE = new UUIDsGenerator(UUIDv7.instance(), DEFAULT_V4_RANDOM);
    private static final ConcurrentMap<String, UUIDsGenerator> NAMED_INSTANCES = new ConcurrentHashMap<>();

    private final UUIDv7 uuidV7;
    private final RandomBytesSource v4Random;
    // one slot per flavour, so that alternating calls never overwrite each other's value
    private final FastThreadLocal<V7Context> threadLocalV7Uuids;
    private final FastThreadLocal<V4Context> threadLocalV4Contexts;
    private final FastThreadLocal<UUID> threadLocalRawUuids;

    /**
     * Creates a generator owning a brand new {@link UUIDv7} sequence and the default v4 random source, a per-thread
     * {@link SecureRandom}.
     */
    public UUIDsGenerator() {
        this(new UUIDv7(), DEFAULT_V4_RANDOM);
    }

    /**
     * Creates a generator on top of the supplied sources.
     *
     * @param uuidV7   the sequence backing {@link #newV7()} and {@link #threadLocalV7()}
     * @param v4Random the bytes behind {@link #newV4()} and {@link #threadLocalV4()}, filling the 16 bytes of a UUID
     *                 of which 122 bits survive the version and variant nibbles. Any JDK random API fits as a method
     *                 reference - {@code secureRandom::nextBytes}, or {@code randomGenerator::nextBytes} on JDK 17+.
     *                 See {@link RandomBytesSource} for what it owes: thread safety, and strength.
     */
    public UUIDsGenerator(UUIDv7 uuidV7, RandomBytesSource v4Random) {
        this.uuidV7 = Objects.requireNonNull(uuidV7, "uuidV7");
        this.v4Random = Objects.requireNonNull(v4Random, "v4Random");
        this.threadLocalV7Uuids = FastThreadLocal.withInitial(V7Context::new);
        this.threadLocalV4Contexts = FastThreadLocal.withInitial(V4Context::new);
        this.threadLocalRawUuids = FastThreadLocal.withInitial(UUIDsGenerator::newEmptyUuid);
    }

    /**
     * Returns the process-wide generator, whose version 7 sequence is {@link UUIDv7#instance()}.
     */
    public static UUIDsGenerator instance() {
        return INSTANCE;
    }

    /**
     * Returns the generator dedicated to {@code id}, creating it on first call. Its version 7 sequence is
     * {@link UUIDv7#instance(String) UUIDv7.instance(id)} - the very same id - so a caller reaching for the
     * {@link UUIDv7} of an id and a caller reaching for the {@link UUIDsGenerator} of that id draw from one
     * ordered sequence. Two <b>different</b> ids share nothing: neither the version 7 sequence nor the thread
     * local instances.
     *
     * @param id the id of the generator, e.g. the name of the subsystem using it
     */
    public static UUIDsGenerator instance(String id) {
        return NAMED_INSTANCES.computeIfAbsent(Objects.requireNonNull(id, "id"),
                key -> new UUIDsGenerator(UUIDv7.instance(key), DEFAULT_V4_RANDOM));
    }

    private static UUID newEmptyUuid() {
        return new UUID(0, 0);
    }

    // version nibble = 4, the other 60 most significant bits are random
    private static long v4Msb(long random) {
        return (random & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
    }

    // variant bits = 10, the other 62 least significant bits are random
    private static long v4Lsb(long random) {
        return (random & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    }

    /**
     * Generates a new time-ordered version 7 {@link UUID}, freshly allocated.
     */
    public UUID newV7() {
        return uuidV7.generateUuid();
    }

    /**
     * Generates a new random version 4 {@link UUID}, freshly allocated, equivalent to {@link UUID#randomUUID()}.
     */
    public UUID newV4() {
        V4Context context = threadLocalV4Contexts.get();
        context.draw(v4Random);
        return new UUID(v4Msb(context.msb), v4Lsb(context.lsb));
    }

    /**
     * Generates a new time-ordered version 7 {@link UUID} into this thread's reused instance, without allocating.
     *
     * <p><b>The returned instance must NOT be shared with another thread</b>, nor stored beyond the current call:
     * it belongs to the calling thread and is mutated in place by the next call. Another thread reading it sees
     * whatever value this thread last wrote, and may see a torn value - one half of a UUID with the other half of
     * the next. Hand {@link #newV7()} to anything that crosses a thread boundary or outlives the call.
     *
     * @return an instance owned by the calling thread, valid until the next call to this method on that thread
     */
    public UUID threadLocalV7() {
        V7Context context = threadLocalV7Uuids.get();
        uuidV7.generateMsbLsb(context.msbLsb);
        return UuidFields.set(context.uuid, context.msbLsb.getMsb(), context.msbLsb.getLsb());
    }

    /**
     * Generates a new random version 4 {@link UUID} into this thread's reused instance, allocating no {@link UUID} -
     * though the {@link RandomBytesSource} behind it may still allocate internally, see {@link V4Context}.
     *
     * <p><b>The returned instance must NOT be shared with another thread</b>, nor stored beyond the current call:
     * it belongs to the calling thread and is mutated in place by the next call. Another thread reading it sees
     * whatever value this thread last wrote, and may see a torn value - one half of a UUID with the other half of
     * the next. Hand {@link #newV4()} to anything that crosses a thread boundary or outlives the call.
     *
     * @return an instance owned by the calling thread, valid until the next call to this method on that thread
     */
    public UUID threadLocalV4() {
        V4Context context = threadLocalV4Contexts.get();
        context.draw(v4Random);
        return UuidFields.set(context.uuid, v4Msb(context.msb), v4Lsb(context.lsb));
    }

    /**
     * Writes the two halves of an <b>already known</b> UUID into this thread's reused instance, without allocating.
     * This is the deserialization counterpart of the {@code threadLocal*} generators - it generates nothing, it only
     * avoids the {@link UUID} allocation of a decoded value.
     *
     * <p><b>The returned instance must NOT be shared with another thread</b>, nor stored beyond the current call:
     * it belongs to the calling thread and is mutated in place by the next call. Another thread reading it sees
     * whatever value this thread last wrote, and may see a torn value - one half of a UUID with the other half of
     * the next. Copy it into a {@code new UUID(msb, lsb)} for anything that crosses a thread boundary or outlives
     * the call.
     *
     * @param msb the most significant bits, as {@link UUID#getMostSignificantBits()}
     * @param lsb the least significant bits, as {@link UUID#getLeastSignificantBits()}
     * @return an instance owned by the calling thread, valid until the next call to this method on that thread
     */
    public UUID threadLocalUuid(long msb, long lsb) {
        return UuidFields.set(threadLocalRawUuids.get(), msb, lsb);
    }

    /**
     * Writes the two halves of an <b>already known</b> UUID into this thread's reused instance, without allocating.
     * This is the deserialization counterpart of the {@code threadLocal*} generators - it generates nothing, it only
     * avoids the {@link UUID} allocation of a decoded value.
     *
     * <p><b>The returned instance must NOT be shared with another thread</b>, nor stored beyond the current call:
     * it belongs to the calling thread and is mutated in place by the next call. Another thread reading it sees
     * whatever value this thread last wrote, and may see a torn value - one half of a UUID with the other half of
     * the next. Copy it into a {@code new UUID(msb, lsb)} for anything that crosses a thread boundary or outlives
     * the call.
     *
     * @param msbLsb the most significant bits and least significant bits
     * @return an instance owned by the calling thread, valid until the next call to this method on that thread
     */
    public UUID threadLocalUuid(MsbLsb msbLsb) {
        return UuidFields.set(threadLocalRawUuids.get(), msbLsb.getMsb(), msbLsb.getLsb());
    }

    /**
     * A thread's own source of version 4 random bits: its {@link SecureRandom}, the 16-byte buffer it fills and the
     * holder handed back, all reused.
     *
     * <p>A thread's own version 4 scratch: the 16-byte buffer the {@link RandomBytesSource} fills, the two halves
     * read out of it, and the {@link UUID} {@link #threadLocalV4()} hands back. One {@link FastThreadLocal} lookup
     * serves a whole id, and the buffer means the source is asked once rather than twice - which matters, because
     * {@link SecureRandom#nextLong()} goes through {@code next(32)} twice and {@code SecureRandom.next(int)}
     * allocates a {@code byte[]} on every call.
     *
     * <p>None of that makes a version 4 UUID allocation-free, and nothing here can: the JDK's own
     * {@link SecureRandom} implementations allocate internally per call - measured at 64 B/op for {@code NativePRNG}
     * filling 16 bytes, against 112 B/op for a single {@code nextLong()}. A version 4 id costs whatever the entropy
     * source costs; only {@link #threadLocalV7()} and {@link SnowflakeId} reach zero.
     */
    private static final class V4Context {

        private final byte[] randomBytes = new byte[16];
        private final UUID uuid = newEmptyUuid();
        private long msb;
        private long lsb;

        void draw(RandomBytesSource randomBytesSource) {
            randomBytesSource.nextBytes(randomBytes);
            msb = longAt(0);
            lsb = longAt(8);
        }

        private long longAt(int offset) {
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (randomBytes[offset + i] & 0xFFL);
            }
            return value;
        }
    }

    private static class V7Context {

        private final UUID uuid = newEmptyUuid();
        private final MsbLsb msbLsb = new MsbLsb(0, 0);
    }

    /**
     * Holds the offsets of the two {@link UUID} fields. Nested so that the {@link UnsafeOperations} lookup only
     * happens when a thread local UUID is actually asked for: a runtime without the required {@code --add-opens}
     * can still use the allocating generators. The failure is captured rather than thrown from the initializer, so
     * that every attempt reports the same cause instead of a {@link NoClassDefFoundError} after the first one.
     */
    @UtilityClass
    private static final class UuidFields {

        private static final String MOST_SIGNIFICANT_BITS = "mostSigBits";
        private static final String LEAST_SIGNIFICANT_BITS = "leastSigBits";

        private static final UnsafeOperations UNSAFE_OPERATIONS;
        private static final long MSB_OFFSET;
        private static final long LSB_OFFSET;
        private static final RuntimeException UNAVAILABLE_CAUSE;

        static {
            UnsafeOperations unsafeOperations = null;
            long msbOffset = UnsafeOperations.UNKNOWN_FIELD_OFFSET;
            long lsbOffset = UnsafeOperations.UNKNOWN_FIELD_OFFSET;
            RuntimeException unavailableCause = null;
            try {
                unsafeOperations = UnsafeOperationsApi.get();
                msbOffset = unsafeOperations.objectFieldOffset(UUID.class, MOST_SIGNIFICANT_BITS);
                lsbOffset = unsafeOperations.objectFieldOffset(UUID.class, LEAST_SIGNIFICANT_BITS);
                if (msbOffset == UnsafeOperations.UNKNOWN_FIELD_OFFSET) {
                    throw new IllegalStateException("Unable to find UUID field " + MOST_SIGNIFICANT_BITS);
                }
                if (lsbOffset == UnsafeOperations.UNKNOWN_FIELD_OFFSET) {
                    throw new IllegalStateException("Unable to find UUID field " + LEAST_SIGNIFICANT_BITS);
                }
            } catch (RuntimeException ex) {
                unavailableCause = ex;
            }
            UNSAFE_OPERATIONS = unsafeOperations;
            MSB_OFFSET = msbOffset;
            LSB_OFFSET = lsbOffset;
            UNAVAILABLE_CAUSE = unavailableCause;
        }

        static UUID set(UUID uuid, long msb, long lsb) {
            if (UNAVAILABLE_CAUSE != null) {
                throw new IllegalStateException("Thread local UUIDs need the UnsafeOperations API", UNAVAILABLE_CAUSE);
            }
            UNSAFE_OPERATIONS.putLong(uuid, MSB_OFFSET, msb);
            UNSAFE_OPERATIONS.putLong(uuid, LSB_OFFSET, lsb);
            return uuid;
        }
    }
}