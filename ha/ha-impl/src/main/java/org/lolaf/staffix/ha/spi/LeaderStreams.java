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
package org.lolaf.staffix.ha.spi;

/**
 * The streams a leader replicates. Keys are given out from 0 in registration order and never reused, so a stream
 * keeps its key, and its lane, for the leader's lifetime. Any thread.
 */
public interface LeaderStreams {

    /**
     * Sent in HELLO and WELCOME: a peer replicating another application is refused.
     */
    int getApplicationId();

    /**
     * How many streams were registered; keys run from 0 to this, excluded.
     */
    int count();

    LeaderStream get(int key);
}
