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
package org.lolaf.staffix.serde;

/**
 * Pre-allocated byte arrays by length, so a serde needing scratch space of a known size does not allocate one.
 *
 * <p>Holds one array of each length up to its maximum and hands the same instance back every time, which makes
 * it safe only for a single thread within one call - callers hold it in a thread local. A request above the
 * maximum allocates, so an unusually long field degrades rather than fails.
 */
public class ByteArraysCache {
    private final byte[][] arraysCache;

    public ByteArraysCache(int maxArrayLen) {
        this.arraysCache = new byte[maxArrayLen][];
        for (int i = 0; i < arraysCache.length; i++) {
            this.arraysCache[i] = new byte[i + 1];
        }
    }

    public byte[] forSize(int arrayLen) {
        if (arrayLen <= arraysCache.length) {
            return arraysCache[arrayLen - 1];
        }
        return new byte[arrayLen];
    }
}
