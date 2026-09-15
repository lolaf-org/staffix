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
package org.lolaf.staffix.impl.session;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.api.time.UTCTime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.time.Instant;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The system clock, reading wall time and the monotonic counter separately - the first stamps SendingTime(52),
 * the second measures durations and must not move when the machine's clock is corrected.
 */
@Slf4j
public class ClockImpl implements Clock {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long OFFSET_SEED = System.currentTimeMillis() / 1000 - 1024;
    private static final MethodHandle NANO_TIME_ADJUSTMENT_HANDLE = getNanoTimeAdjustmentHandle();
    // important keep this order, INSTANCE must be called after NANO_TIME_ADJUSTMENT_HANDLE assignation
    private static final ClockImpl INSTANCE = new ClockImpl(true);
    private static long offset = OFFSET_SEED;
    private final FastThreadLocal<UTCTime.TimeImpl> tlTime;
    private final Supplier<UTCTime> timeSupplier;
    private final LongSupplier epochNanosTimeSupplier;

    ClockImpl(boolean tryUseVMNanoTimeAdjustment) {
        tlTime = FastThreadLocal.withInitial(UTCTime.TimeImpl::new);
        boolean optimizedClock = tryUseVMNanoTimeAdjustment && NANO_TIME_ADJUSTMENT_HANDLE != null;
        log.info("Using {} memory allocation clock", optimizedClock ? "optimized" : "non optimized");
        timeSupplier = optimizedClock ? this::getTimeUsingVMNanoTime : this::getTimeUsingInstant;
        epochNanosTimeSupplier = optimizedClock ? this::getEpochNanosUsingVMNanoTime : this::getEpochNanosUsingInstant;
    }

    public static Clock get() {
        return INSTANCE;
    }

    private static MethodHandle getNanoTimeAdjustmentHandle() {
        try {
            return MethodHandles.lookup().findStatic(Class.forName("jdk.internal.misc.VM"),
                    "getNanoTimeAdjustment", MethodType.methodType(long.class, long.class));
        } catch (Exception e) {
            log.info("Unable to uses directly jdk.internal.misc.VM.getNanoTimeAdjustment for clock implementation, this will induce increased memory allocation. " +
                    "Add JVM arg --add-opens java.base/jdk.internal.misc=ALL-UNNAMED to use optimized clock impl: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public UTCTime now() {
        return timeSupplier.get();
    }

    @Override
    public long nowEpochMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nowEpochNanos() {
        return epochNanosTimeSupplier.getAsLong();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    private UTCTime getTimeUsingInstant() {
        return tlTime.get().from(Instant.now());
    }

    private long getEpochNanosUsingInstant() {
        Instant now = Instant.now();
        return now.getEpochSecond() * NANOS_PER_SECOND + now.getNano();
    }

    private long getEpochNanosUsingVMNanoTime() {
        long localOffset = offset;
        long adjustment = getAdjustment(localOffset);
        if (adjustment == -1) {
            // see java original code comments for more infos
            localOffset = System.currentTimeMillis() / 1000 - 1024;
            adjustment = getAdjustment(localOffset);
            if (adjustment == -1) {
                throw new InternalError("Offset " + localOffset + " is not in range");
            } else {
                offset = localOffset;
            }
        }
        long epochSeconds = Math.addExact(localOffset, Math.floorDiv(adjustment, NANOS_PER_SECOND));
        long nanosOfSeconds = Math.floorMod(adjustment, NANOS_PER_SECOND);
        return epochSeconds * NANOS_PER_SECOND + nanosOfSeconds;
    }

    private UTCTime getTimeUsingVMNanoTime() {
        long localOffset = offset;
        long adjustment = getAdjustment(localOffset);
        if (adjustment == -1) {
            // see java original code comments for more infos
            localOffset = System.currentTimeMillis() / 1000 - 1024;
            adjustment = getAdjustment(localOffset);
            if (adjustment == -1) {
                throw new InternalError("Offset " + localOffset + " is not in range");
            } else {
                offset = localOffset;
            }
        }
        return tlTime.get().from(Math.addExact(localOffset, Math.floorDiv(adjustment, NANOS_PER_SECOND)),
                (int) Math.floorMod(adjustment, NANOS_PER_SECOND));
    }

    private long getAdjustment(long localOffset) {
        try {
            return (long) NANO_TIME_ADJUSTMENT_HANDLE.invoke(localOffset);
        } catch (Throwable e) {
            throw new IllegalStateException(e);
        }
    }
}