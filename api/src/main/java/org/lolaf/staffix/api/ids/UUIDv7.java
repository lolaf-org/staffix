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

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Generator for UUID version 7 (RFC 9562 §5.7): time-ordered, sortable UUIDs.
 *
 * <p>Generation is <b>instance based</b>: each {@code UUIDv7} owns its own monotonic counter state and
 * random source. Every UUID produced by a single instance is strictly greater than the previous one
 * (across threads), so the instance defines one totally ordered sequence. Use distinct instances when you
 * want independent sequences.
 *
 * <p>For the common case, {@link #instance()} exposes a process-wide instance and the static
 * {@link #randomUuid()} shortcut delegates to it.
 *
 * <p><b>Throughput and the per-millisecond ceiling.</b> Monotonicity within a millisecond is provided by
 * the 12-bit {@code rand_a} counter (RFC 9562 method 1, see below), which holds {@code 2^12 = 4096} values
 * ({@code 0..4095}). A single instance can therefore mint at most <b>4096 UUIDs per millisecond</b>, i.e.
 * a sustained ceiling of roughly <b>4 million UUIDs/second per instance</b>. Generation never blocks: once
 * that counter is exhausted within the same millisecond, the overflow borrows into the timestamp
 * ({@code state + 1} carries out of the low 12 bits into the timestamp bits). The <b>drawback</b> is that
 * under sustained bursts above ~4M ops/s the <b>embedded timestamp runs ahead of real wall-clock</b> and
 * keeps drifting for as long as the burst exceeds 4096/ms. Ordering and uniqueness are always preserved,
 * but the timestamp embedded in the UUID can no longer be read back as an accurate creation time (it may be
 * minutes or more into the future after a long enough overload). If you genuinely need more than ~4M
 * UUIDs/second with truthful timestamps, shard the load across several instances (each owns an independent
 * sequence) rather than relying on a single instance.
 *
 * <p>This class only generates; it does not read or write the canonical textual form
 * {@code xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}. That conversion lives in the UUID serdes, next to the FIX
 * codec that needs it.
 *
 * <p>Monotonicity within a millisecond uses the 12 {@code rand_a} bits as a counter (RFC 9562 method 1).
 *
 * <p>The default random source for the {@code rand_b} bits is selected once, at class load, from the
 * {@value #RANDOM_PROPERTY} system property:
 * <ul>
 *     <li>absent or {@code "threadLocal"} (default) - {@link ThreadLocalRandom}</li>
 *     <li>{@code "secure"} - a shared {@link SecureRandom}</li>
 *     <li>otherwise - the fully-qualified name of a {@link Random} subclass with a public no-arg constructor</li>
 * </ul>
 * A custom source can also be supplied directly via {@link #UUIDv7(LongSupplier)}.
 */
public final class UUIDv7 {

    /**
     * System property name selecting the default random source for the {@code rand_b} bits.
     */
    public static final String RANDOM_PROPERTY = "org.lolaf.staffix.api.ids.UUIDv7.random";

    // the 12 rand_a bits, used as the per-millisecond counter (RFC 9562 method 1)
    private static final int COUNTER_BITS = 12;

    private static final LongSupplier DEFAULT_RANDOM = resolveRandom(System.getProperty(RANDOM_PROPERTY));

    private static final UUIDv7 INSTANCE = new UUIDv7();
    private static final ConcurrentMap<String, UUIDv7> NAMED_INSTANCES = new ConcurrentHashMap<>();

    private final TimestampSequence sequence;
    private final LongSupplier random;

    /**
     * Creates a generator with the default random source (see {@link #RANDOM_PROPERTY}) and the system
     * wall-clock ({@link System#currentTimeMillis()}).
     */
    public UUIDv7() {
        this(DEFAULT_RANDOM);
    }

    /**
     * Creates a generator with the supplied random source and the system wall-clock
     * ({@link System#currentTimeMillis()}).
     *
     * @param random the source of the 62 {@code rand_b} bits
     */
    public UUIDv7(LongSupplier random) {
        this(random, System::currentTimeMillis);
    }

    /**
     * Creates a generator with the supplied random source and time provider.
     *
     * @param random the source of the 62 {@code rand_b} bits
     * @param clock  the source of the embedded Unix timestamp in milliseconds; defaults to
     *               {@link System#currentTimeMillis()}
     */
    public UUIDv7(LongSupplier random, LongSupplier clock) {
        this.random = Objects.requireNonNull(random, "random");
        this.sequence = new TimestampSequence(clock, 0L, COUNTER_BITS);
    }

    /**
     * Returns the process-wide shared generator used by the static convenience methods.
     */
    public static UUIDv7 instance() {
        return INSTANCE;
    }

    /**
     * Returns the generator dedicated to {@code id}, creating it on first call. Every id owns an independent
     * sequence: values of two ids are unrelated to each other, and each id gets its own per-millisecond counter
     * (so the ~4096/ms ceiling described above is per id, not shared).
     *
     * @param id the id of the generator, e.g. the name of the subsystem using it
     */
    public static UUIDv7 instance(String id) {
        return NAMED_INSTANCES.computeIfAbsent(Objects.requireNonNull(id, "id"), key -> new UUIDv7());
    }

    // ------------------------------------------------------------------------------------------
    // Static convenience (backed by the instance() generator)
    // ------------------------------------------------------------------------------------------

    /**
     * Generates a new v7 UUID from the shared {@link #instance() instance} as a JDK {@link UUID}.
     */
    public static UUID randomUuid() {
        return INSTANCE.generateUuid();
    }

    private static long buildLsb(long randB) {
        return (0b10L << 62) | (randB & 0x3FFFFFFFFFFFFFFFL);
    }

    static LongSupplier resolveRandom(String prop) {
        if (prop == null || prop.isEmpty() || "threadLocal".equalsIgnoreCase(prop)) {
            return () -> ThreadLocalRandom.current().nextLong();
        }
        if ("secure".equalsIgnoreCase(prop)) {
            SecureRandom secureRandom = new SecureRandom();
            return secureRandom::nextLong;
        }
        try {
            Class<?> clazz = Class.forName(prop);
            Random random = (Random) clazz.getDeclaredConstructor().newInstance();
            return random::nextLong;
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException("Unable to instantiate random source '" + prop
                    + "' from system property " + RANDOM_PROPERTY, e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Generation (instance based)
    // ------------------------------------------------------------------------------------------

    /**
     * Generates a new v7 UUID as a JDK {@link UUID}.
     */
    public UUID generateUuid() {
        return new UUID(buildMsb(sequence.next()), buildLsb(random.getAsLong()));
    }

    /**
     * Generates a new v7 UUID in the given MsbLsb context
     *
     * @param msbLsb the context where to write the new UUID Msb and Lsb
     */
    public void generateMsbLsb(MsbLsb msbLsb) {
        msbLsb.msb = buildMsb(sequence.next());
        msbLsb.lsb = buildLsb(random.getAsLong());
    }

    // unix_ts_ms (48) | ver (4) | rand_a (12), where rand_a is the per-millisecond counter
    private long buildMsb(long tsAndCounter) {
        return (sequence.timestampOf(tsAndCounter) << 16) | (0x7L << COUNTER_BITS) | sequence.counterOf(tsAndCounter);
    }
}
