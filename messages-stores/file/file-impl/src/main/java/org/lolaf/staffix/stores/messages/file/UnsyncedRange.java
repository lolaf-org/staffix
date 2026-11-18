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
package org.lolaf.staffix.stores.messages.file;

import lombok.Getter;

/**
 * The bytes of the store's aligned slice written since the last sync, as one span: the store syncs before a roll-over
 * so that it never spans the file.
 */
@Getter
final class UnsyncedRange {

    private int from = Integer.MAX_VALUE;
    private int to;

    void add(int from, int to) {
        this.from = Math.min(this.from, from);
        this.to = Math.max(this.to, to);
    }

    boolean isNotEmpty() {
        return from < to;
    }

    int length() {
        return to - from;
    }

    void clear() {
        from = Integer.MAX_VALUE;
        to = 0;
    }
}
