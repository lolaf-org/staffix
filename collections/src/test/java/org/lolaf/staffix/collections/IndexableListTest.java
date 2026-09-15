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

import lombok.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexableListTest {

    private IndexableList<TestItem> collection;

    @BeforeEach
    void setUp() {
        collection = new IndexableList<>(TestItem.class);
    }

    @Test
    @DisplayName("should add element to collection")
    void testAddElement() {
        TestItem item = new TestItem(0, "First");
        boolean added = collection.add(item);

        assertThat(added).isTrue();
        assertThat(collection).hasSize(1);
    }

    @Test
    @DisplayName("should add multiple elements at different indices")
    void testAddMultipleElements() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(5, "Sixth");
        TestItem item3 = new TestItem(2, "Third");

        collection.add(item1);
        collection.add(item2);
        collection.add(item3);

        assertThat(collection).hasSize(3).contains(item1, item2, item3);
    }

    @Test
    @DisplayName("should expand capacity when adding at high index")
    void testCapacityExpansion() {
        TestItem item = new TestItem(100, "High Index Item");
        collection.add(item);

        assertThat(collection).hasSize(1).contains(item);
    }

    @Test
    @DisplayName("should throw NullPointerException when adding null element")
    void testAddNullElement() {
        assertThatThrownBy(() -> collection.add(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Element cannot be null");
    }

    @Test
    @DisplayName("should not add for negative index")
    void testNegativeIndex() {
        TestItem item = new TestItem(-1, "Negative");

        assertThat(collection.add(item)).isFalse();
    }

    @Test
    @DisplayName("should get element by index")
    void testGetElement() {
        TestItem item = new TestItem(3, "Fourth");
        collection.add(item);

        TestItem retrieved = collection.get(3);

        assertThat(retrieved).isEqualTo(item);
        assertThat(retrieved.name).isEqualTo("Fourth");
    }

    @Test
    @DisplayName("should throw IndexOutOfBoundsException for invalid get")
    void testGetInvalidIndex() {
        collection.add(new TestItem(0, "First"));

        assertThatThrownBy(() -> collection.get(10))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessageContaining("Index: 10");
    }

    @Test
    @DisplayName("should throw IndexOutOfBoundsException for negative get index")
    void testGetNegativeIndex() {
        assertThatThrownBy(() -> collection.get(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("should contain element after adding")
    void testContainsElement() {
        TestItem item = new TestItem(5, "Fifth");
        collection.add(item);

        assertThat(collection).contains(item);
    }

    @Test
    @DisplayName("should not contain element that was not added")
    void testNotContainsElement() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(1, "Second");
        collection.add(item1);

        assertThat(collection).doesNotContain(item2);
    }

    @Test
    @DisplayName("should not contain element after removal")
    void testRemoveElement() {
        TestItem item = new TestItem(2, "Third");
        collection.add(item);

        boolean removed = collection.remove(item);

        assertThat(removed).isTrue();
        assertThat(collection).doesNotContain(item);
    }

    @Test
    @DisplayName("should return false when removing non-existent element")
    void testRemoveNonExistentElement() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(1, "Second");
        collection.add(item1);

        boolean removed = collection.remove(item2);

        assertThat(removed).isFalse();
    }

    @Test
    @DisplayName("should iterate through all elements")
    void testIterator() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(2, "Third");
        TestItem item3 = new TestItem(4, "Fifth");
        collection.add(item1);
        collection.add(item2);
        collection.add(item3);

        assertThat(collection)
                .containsExactly(item1, item2, item3);
    }

    @Test
    @DisplayName("should iterate and skip null gaps")
    void testIteratorSkipsNulls() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(5, "Sixth");
        collection.add(item1);
        collection.add(item2);

        assertThat(collection)
                .hasSize(2)
                .containsExactly(item1, item2);
    }

    @Test
    @DisplayName("should have size 0 for empty collection")
    void testEmptyCollectionSize() {
        assertThat(collection).isEmpty();
    }

    @Test
    @DisplayName("should replace existing element at same index")
    void testReplaceElement() {
        TestItem item1 = new TestItem(3, "Original");
        TestItem item2 = new TestItem(3, "Replacement");
        collection.add(item1);
        collection.add(item2);

        assertThat(collection)
                .hasSize(1)
                .contains(item2)
                .doesNotContain(item1);
    }

    @Test
    @DisplayName("should handle initial capacity constructor")
    void testInitialCapacityConstructor() {
        IndexableList<TestItem> customCollection = new IndexableList<>(TestItem.class, 50);
        TestItem item = new TestItem(40, "Item");

        customCollection.add(item);

        assertThat(customCollection).contains(item);
    }

    @Test
    @DisplayName("should be an AbstractCollection")
    void testIsAbstractCollection() {
        assertThat(collection).isInstanceOf(java.util.AbstractCollection.class);
    }

    @Test
    @DisplayName("should iterate using forEach without iterator")
    void testForEach() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(2, "Third");
        TestItem item3 = new TestItem(4, "Fifth");
        collection.add(item1);
        collection.add(item2);
        collection.add(item3);

        java.util.List<TestItem> result = new java.util.ArrayList<>();
        collection.forEach(result::add);

        assertThat(result)
                .containsExactly(item1, item2, item3)
                .hasSize(3);
    }

    @Test
    @DisplayName("should forEach skip null gaps in array")
    void testForEachSkipsNulls() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(5, "Sixth");
        collection.add(item1);
        collection.add(item2);

        java.util.List<TestItem> result = new java.util.ArrayList<>();
        collection.forEach(result::add);

        assertThat(result)
                .containsExactly(item1, item2)
                .hasSize(2);
    }

    @Test
    @DisplayName("should forEach work on empty collection")
    void testForEachEmpty() {
        java.util.List<TestItem> result = new java.util.ArrayList<>();
        collection.forEach(result::add);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should forEach execute action on each non-null element")
    void testForEachWithCounter() {
        TestItem item1 = new TestItem(0, "First");
        TestItem item2 = new TestItem(3, "Fourth");
        TestItem item3 = new TestItem(7, "Eighth");
        collection.add(item1);
        collection.add(item2);
        collection.add(item3);

        java.util.List<Integer> indices = new java.util.ArrayList<>();
        collection.forEach(item -> indices.add(item.getIndex()));

        assertThat(indices).containsExactly(0, 3, 7);
    }

    @Value
    static class TestItem implements IntSupplier {
        int index;
        String name;

        TestItem(int index, String name) {
            this.index = index;
            this.name = name;
        }

        @Override
        public int getAsInt() {
            return index;
        }

    }
}