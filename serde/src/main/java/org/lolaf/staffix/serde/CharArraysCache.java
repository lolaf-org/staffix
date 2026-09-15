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
 * The {@code char[]} counterpart of {@link ByteArraysCache}, with the same single-thread, single-call contract.
 */
public class CharArraysCache {
    private final char[][] arraysCache;

    public CharArraysCache(int maxArrayLen) {
        this.arraysCache = new char[maxArrayLen][];
        for (int i = 0; i < arraysCache.length; i++) {
            this.arraysCache[i] = new char[i + 1];
        }
    }

    public char[] forSize(int arrayLen) {
        if (arrayLen <= arraysCache.length) {
            return arraysCache[arrayLen - 1];
        }
        return new char[arrayLen];
    }
}
