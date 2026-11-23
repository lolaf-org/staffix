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
package org.lolaf.staffix.ha.replication.spi;

import java.util.concurrent.TimeUnit;

/**
 * Where the replication records a duration, such as a follower's round trip. Called on the replication's own threads,
 * so it must not block and should not allocate.
 */
@FunctionalInterface
public interface LatencyRecorder {

    LatencyRecorder NONE = (duration, unit) -> {
    };

    void record(long duration, TimeUnit unit);
}
