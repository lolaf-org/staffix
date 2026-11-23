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
package org.lolaf.staffix.ha.replication;

import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;

/**
 * The leader as its follower sees it, over all lanes.
 */
/**
 * The leader, as its follower sees it.
 */
@Value
@Builder
public class LeaderStatus {

    /**
     * Null until the leader said.
     */
    @Nullable String leaderId;
    String address;
    /**
     * Every lane is streaming.
     */
    boolean connected;
    /**
     * Holds every replicated stream in full, as of the last record applied.
     */
    boolean inSync;
    /**
     * What encrypts and authenticates every lane; null while a lane is not connected or replicates in clear.
     */
    @Nullable String cipher;
}
