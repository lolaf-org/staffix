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
package org.lolaf.staffix.buildtools.metadata.fixture.generate;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.api.metadata.JvmFlagSet;
import org.lolaf.staffix.api.metadata.RequiresArtifact;
import org.lolaf.staffix.api.metadata.RequiresJvmFlags;
import org.lolaf.staffix.api.metadata.Wrapped;

import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Moves writes off the session thread.
 */
@NullMarked
@Getter
@SuperBuilder
@RequiresJvmFlags(JvmFlagSet.CHRONICLE)
@RequiresArtifact(oneOf = {"sender-http", "org.other:sender-grpc"})
public class WrapperSettings extends BaseSettings implements StoreSettings {

    private final QueueSettings queue;

    /**
     * The store the writes go to.
     */
    @Wrapped
    private final StoreSettings wrapped;

    /**
     * How long start waits.
     */
    @Builder.Default
    private final Duration startDelay = Duration.ofSeconds(120);

    private final boolean syncWrites;

    @Builder.Default
    private final TimeUnit unit = TimeUnit.MICROSECONDS;

    @Builder.Default
    private final Character delimiter = '|';

    private final @Nullable String label;

    @Builder.Default
    private final @Nullable Duration findTimeout = Duration.ofSeconds(1);

    @Builder.Default
    private final LocalTime rollTime = LocalTime.of(17, 0);

    @Builder.Default
    private final TimeZone zone = TimeZone.getDefault();

    @Singular
    private final List<URI> endpoints;

    @Builder.Default
    private final Map<TimeUnit, Integer> thresholds = Map.of(TimeUnit.SECONDS, 3, TimeUnit.MILLISECONDS, 2, TimeUnit.HOURS, 4);

    @Builder.Default
    private final Set<String> tags = Set.of("c", "a", "b");

    @Builder.Default
    private final Supplier<Object> idleStrategy = Object::new;

    private final @Nullable ScheduledExecutorService executor;

    private final int notInBuilder = 0;
}
