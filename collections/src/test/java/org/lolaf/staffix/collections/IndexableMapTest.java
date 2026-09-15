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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

class IndexableMapTest {

    private IndexableMap<TestKey, String> map;

    @BeforeEach
    void setUp() {
        map = new IndexableMap<>(TestKey.class);
    }

    @Test
    void testPutAndGet() {
        TestKey key1 = new TestKey(0, "first");
        TestKey key2 = new TestKey(5, "second");

        map.put(key1, "value1");
        map.put(key2, "value2");

        assertEquals("value1", map.get(key1));
        assertEquals("value2", map.get(key2));
    }

    @Test
    void testPutReturnsOldValue() {
        TestKey key = new TestKey(3, "key");

        assertNull(map.put(key, "first"));
        assertEquals("first", map.put(key, "second"));
        assertEquals("second", map.get(key));
    }

    @Test
    void testPutNullKeyThrowsException() {
        assertThrows(NullPointerException.class, () -> map.put(null, "value"));
    }

    @Test
    void testPutNegativeIndex() {
        TestKey key = new TestKey(-1, "negative");
        assertNull(map.put(key, "value"));
        assertFalse(map.containsKey(key));
        assertNull(map.get(key));
        assertNull(map.remove(key));
    }

    @Test
    void testGetNonExistentKey() {
        TestKey key = new TestKey(10, "missing");
        assertNull(map.get(key));
    }

    @Test
    void testContainsKey() {
        TestKey key1 = new TestKey(2, "exists");
        TestKey key2 = new TestKey(2, "different");
        TestKey key3 = new TestKey(5, "notAdded");

        map.put(key1, "value");

        assertTrue(map.containsKey(key1));
        assertFalse(map.containsKey(key2)); // Same index but different key
        assertFalse(map.containsKey(key3));
    }

    @Test
    void testRemove() {
        TestKey key = new TestKey(4, "toRemove");

        map.put(key, "value");
        assertTrue(map.containsKey(key));

        assertEquals("value", map.remove(key));
        assertFalse(map.containsKey(key));
        assertNull(map.get(key));
    }

    @Test
    void testRemoveNonExistent() {
        TestKey key = new TestKey(7, "notThere");
        assertNull(map.remove(key));
    }

    @Test
    void testSize() {
        assertEquals(0, map.size());

        map.put(new TestKey(0, "k1"), "v1");
        assertEquals(1, map.size());

        map.put(new TestKey(5, "k2"), "v2");
        assertEquals(2, map.size());

        map.put(new TestKey(10, "k3"), "v3");
        assertEquals(3, map.size());

        map.remove(new TestKey(5, "k2"));
        assertEquals(2, map.size());
    }

    @Test
    void testClear() {
        map.put(new TestKey(0, "k1"), "v1");
        map.put(new TestKey(5, "k2"), "v2");

        assertEquals(2, map.size());

        map.clear();
        assertEquals(0, map.size());
        assertNull(map.get(new TestKey(0, "k1")));
    }

    @Test
    void testEntrySetIteration() {
        map.put(new TestKey(1, "k1"), "v1");
        map.put(new TestKey(3, "k2"), "v2");
        map.put(new TestKey(7, "k3"), "v3");

        int count = 0;
        for (Map.Entry<TestKey, String> entry : map.entrySet()) {
            assertNotNull(entry.getKey());
            assertNotNull(entry.getValue());
            count++;
        }

        assertEquals(3, count);
    }

    @Test
    void testIteratorHasNext() {
        map.put(new TestKey(0, "k1"), "v1");
        map.put(new TestKey(2, "k2"), "v2");

        Iterator<Map.Entry<TestKey, String>> iterator = map.entrySet().iterator();

        assertTrue(iterator.hasNext());
        iterator.next();
        assertTrue(iterator.hasNext());
        iterator.next();
        assertFalse(iterator.hasNext());
    }

    @Test
    void testIteratorNext() {
        TestKey key1 = new TestKey(1, "k1");
        TestKey key2 = new TestKey(5, "k2");

        map.put(key1, "v1");
        map.put(key2, "v2");

        Iterator<Map.Entry<TestKey, String>> iterator = map.entrySet().iterator();

        Map.Entry<TestKey, String> entry1 = iterator.next();
        assertEquals(key1, entry1.getKey());
        assertEquals("v1", entry1.getValue());

        Map.Entry<TestKey, String> entry2 = iterator.next();
        assertEquals(key2, entry2.getKey());
        assertEquals("v2", entry2.getValue());
    }

    @Test
    void testIteratorNextThrowsException() {
        Iterator<Map.Entry<TestKey, String>> iterator = map.entrySet().iterator();
        assertThrows(NoSuchElementException.class, iterator::next);
    }

    @Test
    void testIteratorRemove() {
        map.put(new TestKey(2, "k1"), "v1");
        map.put(new TestKey(5, "k2"), "v2");

        Iterator<Map.Entry<TestKey, String>> iterator = map.entrySet().iterator();
        iterator.next();
        iterator.remove();

        assertEquals(1, map.size());
    }

    @Test
    void testIteratorRemoveThrowsException() {
        map.put(new TestKey(1, "k1"), "v1");

        Iterator<Map.Entry<TestKey, String>> iterator = map.entrySet().iterator();
        assertThrows(IllegalStateException.class, iterator::remove);
    }

    @Test
    void testCapacityExpansion() {
        // Test that map expands when adding elements beyond initial capacity
        for (int i = 0; i < 50; i++) {
            map.put(new TestKey(i, "key" + i), "value" + i);
        }

        assertEquals(50, map.size());

        // Verify all elements are retrievable
        for (int i = 0; i < 50; i++) {
            assertEquals("value" + i, map.get(new TestKey(i, "key" + i)));
        }
    }

    @Test
    void testSparseIndices() {
        // Test with sparse indices
        map.put(new TestKey(0, "k1"), "v1");
        map.put(new TestKey(100, "k2"), "v2");
        map.put(new TestKey(1000, "k3"), "v3");

        assertEquals(3, map.size());
        assertEquals("v1", map.get(new TestKey(0, "k1")));
        assertEquals("v2", map.get(new TestKey(100, "k2")));
        assertEquals("v3", map.get(new TestKey(1000, "k3")));
    }

    @Test
    void testEntrySetContains() {
        TestKey key = new TestKey(5, "k1");
        map.put(key, "v1");

        Map.Entry<TestKey, String> entry = Map.entry(key, "v1");
        assertTrue(map.entrySet().contains(entry));

        Map.Entry<TestKey, String> wrongEntry = Map.entry(key, "wrong");
        assertFalse(map.entrySet().contains(wrongEntry));
    }

    @Test
    void testEntrySetRemove() {
        TestKey key = new TestKey(3, "k1");
        map.put(key, "v1");

        Map.Entry<TestKey, String> entry = Map.entry(key, "v1");
        assertTrue(map.entrySet().remove(entry));
        assertEquals(0, map.size());
        assertFalse(map.containsKey(key));
    }

    static class TestKey implements IntSupplier {
        private final int index;
        private final String name;

        public TestKey(int index, String name) {
            this.index = index;
            this.name = name;
        }

        @Override
        public int getAsInt() {
            return index;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof TestKey)) return false;
            TestKey other = (TestKey) obj;
            return index == other.index && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return 31 * index + name.hashCode();
        }

        @Override
        public String toString() {
            return "TestKey{" + index + ", " + name + "}";
        }
    }
}