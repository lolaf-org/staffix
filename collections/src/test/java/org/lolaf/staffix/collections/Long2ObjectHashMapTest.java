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
package org.lolaf.staffix.collections;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class Long2ObjectHashMapTest {

    @Test
    void putAndGetPrimitiveKeys() {
        Long2ObjectHashMap<String> map = new Long2ObjectHashMap<>(8);
        assertNull(map.put(1L, "one"));
        assertNull(map.put(2L, "two"));

        assertEquals("one", map.get(1L));
        assertEquals("two", map.get(2L));
        assertNull(map.get(3L));
        assertEquals(2, map.size());
        assertFalse(map.isEmpty());
    }

    @Test
    void putOverwriteReturnsPreviousValue() {
        Long2ObjectHashMap<String> map = new Long2ObjectHashMap<>(8);
        map.put(42L, "a");
        assertEquals("a", map.put(42L, "b"));
        assertEquals("b", map.get(42L));
        assertEquals(1, map.size());
    }

    @Test
    void supportsZeroAndExtremeKeys() {
        Long2ObjectHashMap<String> map = new Long2ObjectHashMap<>(8);
        map.put(0L, "zero");
        map.put(Long.MIN_VALUE, "min");
        map.put(Long.MAX_VALUE, "max");
        assertEquals("zero", map.get(0L));
        assertEquals("min", map.get(Long.MIN_VALUE));
        assertEquals("max", map.get(Long.MAX_VALUE));
        assertTrue(map.containsKey(0L));
    }

    @Test
    void nullValuesAreRejected() {
        Long2ObjectHashMap<String> map = new Long2ObjectHashMap<>(8);
        assertThrows(NullPointerException.class, () -> map.put(1L, null));
    }

    @Test
    void growsAndRetainsAllEntries() {
        Long2ObjectHashMap<Long> map = new Long2ObjectHashMap<>(4);
        for (long i = 0; i < 1_000; i++) {
            map.put(i, Long.valueOf(i * 3));
        }
        assertEquals(1_000, map.size());
        for (long i = 0; i < 1_000; i++) {
            assertEquals(i * 3, map.get(i));
            assertTrue(map.containsKey(i));
        }
        assertFalse(map.containsKey(1_000L));
    }

    @Test
    void compactKeepsAllEntriesReachable() {
        Long2ObjectHashMap<Long> map = new Long2ObjectHashMap<>(4);
        for (long i = 0; i < 500; i++) {
            map.put(i, Long.valueOf(i));
        }
        map.compact();
        assertEquals(500, map.size());
        for (long i = 0; i < 500; i++) {
            assertEquals(i, map.get(i));
        }
    }

    @Test
    void valuesReturnsAllValuesAndViewIsCached() {
        Long2ObjectHashMap<String> map = new Long2ObjectHashMap<>(8);
        map.put(1L, "a");
        map.put(2L, "b");

        List<String> collected = new ArrayList<>(map.values());
        collected.sort(String::compareTo);
        assertEquals(List.of("a", "b"), collected);
        assertSame(map.values(), map.values(), "expected the same cached instance");
    }

    @Test
    void boxedMapInterfaceAccessDelegatesToPrimitive() {
        Map<Long, String> map = new Long2ObjectHashMap<>(8);
        assertNull(map.put(7L, "seven"));
        assertEquals("seven", map.get(Long.valueOf(7L)));
        assertTrue(map.containsKey(Long.valueOf(7L)));
        assertFalse(map.containsKey("not a long"));
        assertNull(map.get("not a long"));
    }

    @Test
    void unusedOptionalOperationsThrow() {
        Long2ObjectHashMap<String> map = new Long2ObjectHashMap<>(8);
        assertThrows(UnsupportedOperationException.class, () -> map.remove(Long.valueOf(1L)));
        assertThrows(UnsupportedOperationException.class, map::clear);
        assertThrows(UnsupportedOperationException.class, map::keySet);
        assertThrows(UnsupportedOperationException.class, map::entrySet);
    }

    @Test
    void behavesLikeReferenceHashMapUnderRandomOperations() {
        Random random = new Random(20260716L);
        Long2ObjectHashMap<Long> map = new Long2ObjectHashMap<>(8);
        HashMap<Long, Long> reference = new HashMap<>();

        for (int op = 0; op < 50_000; op++) {
            long key = random.nextLong() % 4_000;
            long value = random.nextLong();
            assertEquals(reference.put(key, value), map.put(key, Long.valueOf(value)));
        }

        assertEquals(reference.size(), map.size());
        for (Map.Entry<Long, Long> entry : reference.entrySet()) {
            assertEquals(entry.getValue(), map.get(entry.getKey().longValue()));
            assertTrue(map.containsKey(entry.getKey().longValue()));
        }
    }
}
