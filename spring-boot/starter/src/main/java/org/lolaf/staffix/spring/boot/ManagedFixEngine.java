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
package org.lolaf.staffix.spring.boot;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixEngine;

import java.io.Closeable;
import java.time.Duration;

/**
 * Holder around a started {@link FixEngine} that adapts the engine's typed shutdown signature
 * ({@code stop(Deadline)}) to a no-arg {@link Closeable#close()} so Spring's auto-detected
 * destroy method handling can manage engine shutdown without ceremony.
 */
@RequiredArgsConstructor
public class ManagedFixEngine implements Closeable {

    @Getter
    private final FixEngine engine;
    private final Duration shutdownMaxDelay;

    @Override
    public void close() {
        engine.stop(Deadline.of(shutdownMaxDelay));
    }
}
