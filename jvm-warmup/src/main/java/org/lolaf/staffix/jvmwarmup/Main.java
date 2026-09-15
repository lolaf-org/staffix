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
package org.lolaf.staffix.jvmwarmup;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Standalone runner — useful for ad-hoc warmup runs and the module smoke test.
 * -XX:-RestrictContended -XX:ContendedPaddingWidth=64 --enable-native-access=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
 */
@Slf4j
@UtilityClass
public final class Main {

    public static void main(String[] args) {
        Duration duration = args.length >= 1 ? Duration.parse(args[0]) : Duration.ofSeconds(60);
        JvmWarmupOptions options = JvmWarmupOptions.builder()
                .warmupDuration(duration)
                .throttling(Duration.ofMillis(1))
                .build();

        // join rethrows on failure, so a broken warmup exits non-zero; the cause itself has already been
        // logged by the controller
        JvmWarmupResult result = JvmWarmup.run(options).join();
        log.info("Warmup complete: sent={}, received={}, duration={}, cancelled={}",
                result.getMessagesSent(), result.getMessagesReceived(),
                result.getActualDuration(), result.isCancelled());
    }
}