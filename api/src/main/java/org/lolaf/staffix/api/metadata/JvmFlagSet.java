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
package org.lolaf.staffix.api.metadata;

import java.util.List;

/**
 * The JVM flags a component needs, grouped by what needs them.
 */
public enum JvmFlagSet {

    /**
     * Pads the {@code @Contended} structures and lets the serdes reuse thread-local buffers; without them staffix runs,
     * but slower. {@code --enable-native-access} is refused by JDK 16 and older.
     */
    ENGINE(List.of(
            "--enable-native-access=ALL-UNNAMED",
            "-XX:-RestrictContended",
            "-XX:ContendedPaddingWidth=64",
            "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED")),

    /**
     * Chronicle Queue, behind the async store and logger, fails to start without them.
     */
    CHRONICLE(List.of(
            "--add-opens java.base/jdk.internal.ref=ALL-UNNAMED",
            "--add-exports java.base/jdk.internal.ref=ALL-UNNAMED",
            "--add-opens java.base/java.lang=ALL-UNNAMED",
            "--add-opens java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens java.base/sun.nio.ch=ALL-UNNAMED",
            "--add-opens java.base/java.nio=ALL-UNNAMED",
            "--add-opens java.base/java.nio.channels.spi=ALL-UNNAMED"));

    private final List<String> flags;

    JvmFlagSet(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getFlags() {
        return flags;
    }
}
