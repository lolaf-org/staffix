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

import lombok.extern.slf4j.Slf4j;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Generator for Snowflake ids: 64-bit, time-ordered, sortable identifiers that fit in a {@code long}.
 *
 * <p>Where a {@link UUIDv7} carries 128 bits and has to be rendered as 36 characters to travel in a FIX field, a
 * Snowflake id is a single {@code long}: it costs nothing to generate, nothing to store, nothing to compare, and it
 * serializes as a plain integer. That is the trade - a smaller, cheaper id, at the price of a <b>node id you must
 * configure</b>, because uniqueness across machines comes from that number rather than from randomness.
 *
 * <p>The layout is the original Twitter one:
 *
 * <pre>
 *  63   62                                   22        12                    0
 *  +---+------------------------------------+----------+---------------------+
 *  | 0 | timestamp (41 bits, ms since epoch)| node (10)| sequence (12 bits)  |
 *  +---+------------------------------------+----------+---------------------+
 * </pre>
 *
 * <p>The sign bit is always {@code 0}, so ids are positive and their natural {@code long} ordering is their creation
 * ordering. The 41-bit timestamp is milliseconds since {@link #DEFAULT_EPOCH_MILLIS} (or the epoch passed to the
 * constructor), which lasts about 69 years from that epoch. The 10-bit node id allows {@value #MAX_NODE_ID} + 1
 * distinct generators, and the 12-bit sequence allows 4096 ids per millisecond per node.
 *
 * <p><b>Every concurrently running generator must own a distinct node id.</b> Two generators sharing a node id
 * <i>will</i> mint the same value - that is the whole basis of the scheme's uniqueness. Set it explicitly per
 * process, from configuration, an orchestrator-assigned ordinal, or the {@value #NODE_ID_PROPERTY} system property
 * that backs {@link #instance()} (which otherwise defaults to node id {@value #DEFAULT_NODE_ID}, fine for a single
 * process and a duplicate-id generator for anything beyond that). For the same reason there is no per-subsystem
 * instance registry here (unlike {@link UUIDv7#instance(String)}): several sequences on one node id would collide.
 *
 * <p><b>Throughput and the per-millisecond ceiling.</b> A node mints at most 4096 ids per millisecond, i.e. roughly
 * 4 million per second. Generation never blocks: once the sequence is exhausted within a millisecond the overflow
 * borrows into the timestamp, exactly as {@link UUIDv7} does, so ordering and uniqueness always hold but the embedded
 * timestamp runs ahead of the wall clock for as long as the burst lasts.
 *
 * <p><b>Clocks that go backwards.</b> Classic implementations throw, or block until the clock catches up. This one
 * does neither: the counter simply advances, so a backwards step (NTP correction, leap-second smearing, a VM pause)
 * costs a little timestamp accuracy and never costs ordering, uniqueness or availability. An id is always strictly
 * greater than the one before it, from the same generator, across threads.
 *
 * <p>Generation is lock-free: one CAS on a single {@code long} packing the timestamp and the sequence - the
 * {@link TimestampSequence} this shares with {@link UUIDv7}, which is also where the two behaviours above come from.
 */
public final class SnowflakeId {

    /**
     * The largest valid node id ({@code 2^10 - 1}).
     */
    public static final int MAX_NODE_ID = 0x3FF;

    /**
     * Default epoch of the embedded timestamp: 2024-01-01T00:00:00Z. The 41-bit timestamp therefore runs out
     * around 2093.
     */
    public static final long DEFAULT_EPOCH_MILLIS = 1704067200000L;

    /**
     * System property naming the node id of the {@link #instance() shared generator}, as a decimal number in
     * {@code 0..}{@value #MAX_NODE_ID}.
     */
    public static final String NODE_ID_PROPERTY = "org.lolaf.staffix.api.ids.SnowflakeId.nodeId";

    /**
     * Node id the {@link #instance() shared generator} falls back to when {@value #NODE_ID_PROPERTY} is not set.
     */
    public static final int DEFAULT_NODE_ID = 0;

    private static final int SEQUENCE_BITS = 12;
    private static final int NODE_ID_BITS = 10;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private final TimestampSequence sequence;
    private final long epochMillis;
    private final int nodeId;
    // the node id pre-shifted into its place in the id, so generation is one OR
    private final long nodeIdBits;

    /**
     * Creates a generator for {@code nodeId}, on the default epoch and the system wall-clock.
     *
     * @param nodeId the id of this node, in {@code 0..}{@value #MAX_NODE_ID}; must be distinct from every other
     *               generator running at the same time
     */
    public SnowflakeId(int nodeId) {
        this(nodeId, DEFAULT_EPOCH_MILLIS);
    }

    /**
     * Creates a generator for {@code nodeId} on a custom epoch, with the system wall-clock.
     *
     * @param nodeId      the id of this node, in {@code 0..}{@value #MAX_NODE_ID}
     * @param epochMillis the Unix millisecond the embedded timestamp counts from; must be in the past
     */
    public SnowflakeId(int nodeId, long epochMillis) {
        this(nodeId, epochMillis, System::currentTimeMillis);
    }

    /**
     * Creates a generator for {@code nodeId} on a custom epoch and time provider.
     *
     * @param nodeId      the id of this node, in {@code 0..}{@value #MAX_NODE_ID}
     * @param epochMillis the Unix millisecond the embedded timestamp counts from; must be in the past
     * @param clock       the source of the current Unix time in milliseconds
     */
    public SnowflakeId(int nodeId, long epochMillis, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("nodeId " + nodeId + " is out of range 0.." + MAX_NODE_ID);
        }
        Objects.requireNonNull(clock, "clock");
        if (epochMillis < 0 || epochMillis > clock.getAsLong()) {
            throw new IllegalArgumentException("epochMillis " + epochMillis + " is not a past Unix millisecond");
        }
        this.nodeId = nodeId;
        this.nodeIdBits = ((long) nodeId) << SEQUENCE_BITS;
        this.epochMillis = epochMillis;
        this.sequence = new TimestampSequence(clock, epochMillis, SEQUENCE_BITS);
    }

    /**
     * Returns the process-wide generator, whose node id comes from the {@value #NODE_ID_PROPERTY} system property,
     * read on first call.
     *
     * <p>When that property is not set the generator falls back to node id {@value #DEFAULT_NODE_ID} and logs an
     * {@code INFO} line saying so - convenient for a single-process deployment, and <b>wrong for any deployment
     * running more than one</b>, where every process would then mint the same ids. A property that <i>is</i> set
     * but does not hold a node id in {@code 0..}{@value #MAX_NODE_ID} is a configuration mistake rather than an
     * omission, and fails.
     *
     * @throws IllegalStateException    if {@value #NODE_ID_PROPERTY} is set to something that is not a number
     * @throws IllegalArgumentException if it is a number outside {@code 0..}{@value #MAX_NODE_ID}. Both are raised
     *                                  while the shared generator is being built, so the very first call surfaces
     *                                  them wrapped in an {@link ExceptionInInitializerError}
     */
    public static SnowflakeId instance() {
        return Holder.get();
    }

    /**
     * Returns the node id encoded in {@code id}.
     */
    public static int nodeIdOf(long id) {
        return (int) ((id >>> SEQUENCE_BITS) & MAX_NODE_ID);
    }

    /**
     * Returns the per-millisecond sequence number encoded in {@code id}.
     */
    public static int sequenceOf(long id) {
        return (int) (id & MAX_SEQUENCE);
    }

    // parses a node id property value that is present; an absent one is the caller's business, and Holder answers
    // it with DEFAULT_NODE_ID rather than a failure. The range is the constructor's to check.
    static int resolveNodeId(String prop) {
        if (prop == null || prop.trim().isEmpty()) {
            throw new IllegalStateException("System property " + NODE_ID_PROPERTY
                    + " must hold this node's id, in 0.." + MAX_NODE_ID);
        }
        try {
            return Integer.parseInt(prop.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("System property " + NODE_ID_PROPERTY + " is not a number: " + prop, e);
        }
    }

    /**
     * Generates the next id of this generator, strictly greater than the previous one. Allocates nothing and never
     * blocks.
     */
    public long nextId() {
        long timestampAndSequence = sequence.next();
        return (sequence.timestampOf(timestampAndSequence) << (SEQUENCE_BITS + NODE_ID_BITS))
                | nodeIdBits
                | sequence.counterOf(timestampAndSequence);
    }

    /**
     * Returns the node id of this generator.
     */
    public int nodeId() {
        return nodeId;
    }

    /**
     * Returns the epoch this generator's timestamps count from.
     */
    public long epochMillis() {
        return epochMillis;
    }

    /**
     * Returns the creation time of {@code id} as a Unix millisecond, provided it was minted by a generator using
     * this one's epoch.
     */
    public long timestampMillisOf(long id) {
        return (id >>> (SEQUENCE_BITS + NODE_ID_BITS)) + epochMillis;
    }

    // resolves the shared instance on first use, so that merely touching SnowflakeId costs nothing on a process
    // that only uses its own explicitly constructed generators
    @Slf4j
    private static final class Holder {

        private static final SnowflakeId INSTANCE = resolve();

        private Holder() {
        }

        private static SnowflakeId resolve() {
            String prop = System.getProperty(NODE_ID_PROPERTY);
            if (prop == null || prop.trim().isEmpty()) {
                log.info("No {} system property set, the shared SnowflakeId generator defaults to node id {}. "
                                + "Set that property, to a value distinct per process, on every process minting ids: "
                                + "two generators sharing a node id mint identical ids.",
                        NODE_ID_PROPERTY, DEFAULT_NODE_ID);
                return new SnowflakeId(DEFAULT_NODE_ID);
            }
            // a property that is set but unusable is a configuration mistake, not an omission: let it throw out of
            // this initializer straight away rather than hand back a generator built on a guess
            return new SnowflakeId(resolveNodeId(prop));
        }

        static SnowflakeId get() {
            return INSTANCE;
        }
    }
}
