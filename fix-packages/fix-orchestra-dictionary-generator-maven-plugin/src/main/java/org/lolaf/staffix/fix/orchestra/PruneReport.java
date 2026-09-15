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
package org.lolaf.staffix.fix.orchestra;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a cut removed and why, which is what a dry run prints and what the tests assert on. Kept per element name and
 * per reason rather than as one total: "40 fields dropped" says nothing useful, while "40 fields dropped by the cut,
 * 3 references dropped because their target went" is a description of the shape of the result.
 */
public final class PruneReport {

    private final Map<Reason, Map<String, Integer>> removed = new LinkedHashMap<>();

    void record(Reason reason, String elementName) {
        removed.computeIfAbsent(reason, r -> new LinkedHashMap<>()).merge(elementName, 1, Integer::sum);
    }

    public int count(Reason reason, String elementName) {
        return removed.getOrDefault(reason, Map.of()).getOrDefault(elementName, 0);
    }

    public int count(Reason reason) {
        return removed.getOrDefault(reason, Map.of()).values().stream().mapToInt(Integer::intValue).sum();
    }

    public int total() {
        int total = 0;
        for (Reason reason : Reason.values()) {
            total += count(reason);
        }
        return total;
    }

    public boolean isEmpty() {
        return total() == 0;
    }

    /**
     * One line per reason, each listing what went, in the order the reasons are declared - which is also the order
     * the passes run in, so the lines read as the story of the cut.
     */
    public String describe() {
        StringBuilder description = new StringBuilder();
        for (Reason reason : Reason.values()) {
            Map<String, Integer> byElement = removed.get(reason);
            if (byElement == null || byElement.isEmpty()) {
                continue;
            }
            if (description.length() > 0) {
                description.append(System.lineSeparator());
            }
            description.append(reason).append(": ");
            boolean first = true;
            for (Map.Entry<String, Integer> entry : byElement.entrySet()) {
                if (!first) {
                    description.append(", ");
                }
                description.append(entry.getValue()).append(' ').append(entry.getKey());
                first = false;
            }
        }
        return description.length() == 0 ? "nothing removed" : description.toString();
    }

    @Override
    public String toString() {
        return describe();
    }

    /**
     * Why an element was removed.
     */
    public enum Reason {
        /**
         * Introduced after the cut.
         */
        AFTER_CUT,
        /**
         * Already deprecated at the cut, and deprecated elements were not asked for.
         */
        DEPRECATED,
        /**
         * A message the selection did not ask for. Only messages are ever removed for this reason: what a dropped
         * message leaves unreferenced is not removed here at all, since a definition nothing points at is still a
         * perfectly formed definition. The dictionary sanitizer takes those out afterwards.
         */
        NOT_SELECTED,
        /**
         * Nothing wrong with it in itself: what it referred to, or what it was made of, is gone.
         */
        DANGLING
    }
}
