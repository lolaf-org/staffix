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
package org.lolaf.staffix.ha.it;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many nodes of a pair have the session logged on at once, and the most there ever were: more than one is the
 * session up on both, a split brain as the counterparty would live it.
 */
final class LoggedOnNodes {

    private final AtomicInteger now = new AtomicInteger();
    private final AtomicInteger most = new AtomicInteger();

    void loggedOn() {
        most.accumulateAndGet(now.incrementAndGet(), Math::max);
    }

    void loggedOff() {
        now.decrementAndGet();
    }

    int most() {
        return most.get();
    }
}
