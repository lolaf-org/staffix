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

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.time.Clock;
import org.lolaf.staffix.ha.spi.ReplicationClock;

import java.time.Instant;

/**
 * The engine's clock as the replication reads it, so a test's clock drives both.
 */
@RequiredArgsConstructor
public final class EngineReplicationClock implements ReplicationClock {

    private final Clock clock;

    @Override
    public long nanoTime() {
        return clock.nanoTime();
    }

    @Override
    public Instant now() {
        return clock.now().asInstant();
    }
}
