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
package org.lolaf.staffix.tests;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Awaitility polls every 100 ms by default, which an await on a loopback round trip spends almost entirely waiting;
 * its 10 s timeout is short for a slow build machine, so it is raised to 30 s.
 * Registered as a service: a module turns it on for all its tests with
 * {@code junit.jupiter.extensions.autodetection.enabled=true} in its {@code junit-platform.properties}.
 */
public class FastAwaitility implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        Awaitility.setDefaultPollDelay(100, TimeUnit.MICROSECONDS);
        Awaitility.setDefaultPollInterval(200, TimeUnit.MICROSECONDS);
        Awaitility.setDefaultTimeout(Duration.ofSeconds(30));
    }
}
