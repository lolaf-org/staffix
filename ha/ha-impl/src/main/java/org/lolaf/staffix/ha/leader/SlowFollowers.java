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
package org.lolaf.staffix.ha.leader;

import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.ha.HaStatus;

import org.lolaf.staffix.api.time.Clock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The followers dropped as too slow, by node id, each held off before it may resync, since its catching up adds to the
 * load that made it too slow. The hold-off doubles at each drop up to {@link #MAX_HOLD_OFF}, and starts over once the
 * follower stayed in sync that long. Any thread.
 */
public final class SlowFollowers {

    static final Duration MAX_HOLD_OFF = Duration.ofMinutes(1);

    private final Duration initialHoldOff;
    private final Clock clock;
    private final Map<String, Drop> drops;

    /**
     * @param initialHoldOff zero never holds a follower off
     */
    public SlowFollowers(Duration initialHoldOff, Clock clock) {
        this.initialHoldOff = initialHoldOff;
        this.clock = clock;
        this.drops = new ConcurrentHashMap<>();
    }

    void dropped(String followerId) {
        Instant now = now();
        drops.compute(followerId, (id, previous) -> new Drop(now, nextHoldOff(previous, now), null));
    }

    private Duration nextHoldOff(@Nullable Drop previous, Instant now) {
        if (previous == null || previous.backInSyncAt != null && !now.isBefore(previous.backInSyncAt.plus(MAX_HOLD_OFF))) {
            return initialHoldOff;
        }
        Duration doubled = previous.holdOff.multipliedBy(2);
        return doubled.compareTo(MAX_HOLD_OFF) > 0 ? MAX_HOLD_OFF : doubled;
    }

    boolean isNotBackInSync(String followerId) {
        Drop drop = drops.get(followerId);
        return drop != null && drop.backInSyncAt == null;
    }

    void backInSync(String followerId) {
        Instant now = now();
        drops.computeIfPresent(followerId, (id, drop) -> drop.backInSyncAt == null
                ? new Drop(drop.droppedAt, drop.holdOff, now) : drop);
    }

    /**
     * When the follower may connect again, empty when it may now.
     */
    Optional<Instant> heldOffUntil(String followerId) {
        Drop drop = drops.get(followerId);
        return drop == null ? Optional.empty() : drop.heldOffUntil(now());
    }

    /**
     * Those not back in sync since their last drop, by node id.
     */
    public List<HaStatus.DroppedFollower> notBackInSync() {
        Instant now = now();
        return drops.entrySet().stream()
                .filter(entry -> entry.getValue().backInSyncAt == null)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> HaStatus.DroppedFollower.builder().nodeId(entry.getKey())
                        .droppedAt(entry.getValue().droppedAt)
                        .heldOffUntil(entry.getValue().heldOffUntil(now).orElse(null)).build())
                .collect(Collectors.toList());
    }

    private Instant now() {
        return clock.now().asInstant();
    }

    @Value
    private static class Drop {
        Instant droppedAt;
        Duration holdOff;
        @Nullable Instant backInSyncAt;

        Optional<Instant> heldOffUntil(Instant now) {
            Instant until = droppedAt.plus(holdOff);
            return now.isBefore(until) ? Optional.of(until) : Optional.empty();
        }
    }
}
