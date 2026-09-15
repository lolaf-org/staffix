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

class Int2ObjectHashMapTest {

    private static void assertSameInstance(Object expected, Object actual) {
        assertSame(expected, actual, "expected the same cached instance");
    }

    @Test
    void putAndGetPrimitiveKeys() {
        Int2ObjectHashMap<String> map = new Int2ObjectHashMap<>(8);
        assertNull(map.put(1, "one"));
        assertNull(map.put(2, "two"));

        assertEquals("one", map.get(1));
        assertEquals("two", map.get(2));
        assertNull(map.get(3));
        assertEquals(2, map.size());
        assertFalse(map.isEmpty());
    }

    @Test
    void putOverwriteReturnsPreviousValue() {
        Int2ObjectHashMap<String> map = new Int2ObjectHashMap<>(8);
        map.put(42, "a");
        assertEquals("a", map.put(42, "b"));
        assertEquals("b", map.get(42));
        assertEquals(1, map.size());
    }

    @Test
    void supportsZeroKey() {
        Int2ObjectHashMap<String> map = new Int2ObjectHashMap<>(8);
        map.put(0, "zero");
        assertTrue(map.containsKey(0));
        assertEquals("zero", map.get(0));
        assertEquals(1, map.size());
    }

    @Test
    void supportsNegativeKeys() {
        Int2ObjectHashMap<String> map = new Int2ObjectHashMap<>(8);
        map.put(Integer.MIN_VALUE, "min");
        map.put(-1, "neg");
        assertEquals("min", map.get(Integer.MIN_VALUE));
        assertEquals("neg", map.get(-1));
    }

    @Test
    void nullValuesAreRejected() {
        Int2ObjectHashMap<String> map = new Int2ObjectHashMap<>(8);
        assertThrows(NullPointerException.class, () -> map.put(1, null));
    }

    @Test
    void growsAndRetainsAllEntries() {
        Int2ObjectHashMap<Integer> map = new Int2ObjectHashMap<>(4);
        for (int i = 0; i < 1_000; i++) {
            map.put(i, Integer.valueOf(i * 3));
        }
        assertEquals(1_000, map.size());
        for (int i = 0; i < 1_000; i++) {
            assertEquals(i * 3, map.get(i));
            assertTrue(map.containsKey(i));
        }
        assertFalse(map.containsKey(1_000));
    }

    @Test
    void lowLoadFactorConstructorBehavesLikeDefault() {
        Int2ObjectHashMap<Integer> map = new Int2ObjectHashMap<>(10, 0.1f);
        for (int i = 0; i < 200; i++) {
            map.put(i, Integer.valueOf(i));
        }
        for (int i = 0; i < 200; i++) {
            assertEquals(i, map.get(i));
        }
        assertEquals(200, map.size());
    }

    @Test
    void invalidLoadFactorRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Int2ObjectHashMap<>(8, 0f));
        assertThrows(IllegalArgumentException.class, () -> new Int2ObjectHashMap<>(8, 1f));
    }

    @Test
    void valuesReturnsAllValuesAndViewIsCached() {
        Int2ObjectHashMap<String> map = new Int2ObjectHashMap<>(8);
        map.put(1, "a");
        map.put(2, "b");
        map.put(3, "c");

        List<String> collected = new ArrayList<>(map.values());
        collected.sort(String::compareTo);
        assertEquals(List.of("a", "b", "c"), collected);
        assertEquals(3, map.values().size());
        assertSameInstance(map.values(), map.values());
    }

    @Test
    void compactKeepsAllEntriesReachable() {
        Int2ObjectHashMap<Integer> map = new Int2ObjectHashMap<>(4);
        for (int i = 0; i < 500; i++) {
            map.put(i, Integer.valueOf(i));
        }
        map.compact();
        assertEquals(500, map.size());
        for (int i = 0; i < 500; i++) {
            assertEquals(i, map.get(i));
        }
    }

    @Test
    void boxedMapInterfaceAccessDelegatesToPrimitive() {
        Map<Integer, String> map = new Int2ObjectHashMap<>(8);
        assertNull(map.put(7, "seven"));
        assertEquals("seven", map.get(7));
        assertEquals("seven", map.get(Integer.valueOf(7)));
        assertTrue(map.containsKey(Integer.valueOf(7)));
        assertFalse(map.containsKey("not an integer"));
        assertNull(map.get("not an integer"));
    }

    @Test
    void unusedOptionalOperationsThrow() {
        Int2ObjectHashMap<String> map = new Int2ObjectHashMap<>(8);
        assertThrows(UnsupportedOperationException.class, () -> map.remove(Integer.valueOf(1)));
        assertThrows(UnsupportedOperationException.class, map::clear);
        assertThrows(UnsupportedOperationException.class, map::keySet);
        assertThrows(UnsupportedOperationException.class, map::entrySet);
        assertThrows(UnsupportedOperationException.class, () -> map.containsValue("x"));
        assertThrows(UnsupportedOperationException.class, () -> map.putAll(new HashMap<>()));
    }

    @Test
    void behavesLikeReferenceHashMapUnderRandomOperations() {
        Random random = new Random(20260716L);
        Int2ObjectHashMap<Integer> map = new Int2ObjectHashMap<>(8);
        HashMap<Integer, Integer> reference = new HashMap<>();

        for (int op = 0; op < 50_000; op++) {
            int key = random.nextInt(2_000) - 1_000;
            int value = random.nextInt();
            assertEquals(reference.put(key, value), map.put(key, Integer.valueOf(value)));
        }

        assertEquals(reference.size(), map.size());
        for (Map.Entry<Integer, Integer> entry : reference.entrySet()) {
            assertEquals(entry.getValue(), map.get(entry.getKey().intValue()));
            assertTrue(map.containsKey(entry.getKey().intValue()));
        }

        List<Integer> mapValues = new ArrayList<>(map.values());
        List<Integer> refValues = new ArrayList<>(reference.values());
        mapValues.sort(Integer::compareTo);
        refValues.sort(Integer::compareTo);
        assertEquals(refValues, mapValues);
    }
}
