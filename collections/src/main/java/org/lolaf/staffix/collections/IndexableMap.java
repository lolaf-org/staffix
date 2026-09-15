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

import lombok.AllArgsConstructor;
import lombok.Data;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * A map whose keys supply their own int, so a get costs an array access and no hashing or boxing.
 */
public class IndexableMap<K extends IntSupplier, V> extends AbstractMap<K, V> {
    private static final int DEFAULT_CAPACITY = 10;
    private EntryImpl<K, V>[] entries;
    private int size;


    public IndexableMap(Class<K> keyClass) {
        this(keyClass, DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public IndexableMap(Class<K> keyClass, int initialCapacity) {
        this.entries = (EntryImpl<K, V>[]) Array.newInstance(EntryImpl.class, initialCapacity);
        this.size = initialCapacity;
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        for (int i = 0; i < entries.length; i++) {
            EntryImpl<K, V> e = entries[i];
            if (e != null) {
                action.accept(e.getKey(), e.getValue());
            }
        }
    }

    @Override
    public V put(K key, V value) {
        if (key == null) {
            throw new NullPointerException("Key cannot be null");
        }

        int index = key.getAsInt();
        if (index < 0) {
            return null;
        }

        if (index >= entries.length) {
            expandCapacity(index + 1);
        }

        EntryImpl<K, V> oldEntry = entries[index];
        if (oldEntry != null) {
            return oldEntry.setValue(value);
        } else {
            entries[index] = new EntryImpl<>(key, value);
            return null;
        }
    }

    @Override
    public V get(Object key) {
        int index = ((IntSupplier) key).getAsInt();
        if (index >= 0 && index < size) {
            Entry<K, V> entry = entries[index];
            if (entry != null && entry.getKey().equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        int index = ((IntSupplier) key).getAsInt();
        if (index >= 0 && index < size) {
            Entry<K, V> entry = entries[index];
            return entry != null && entry.getKey().equals(key);
        }
        return false;
    }

    @Override
    public V remove(Object key) {
        int index = ((IntSupplier) key).getAsInt();
        if (index >= 0 && index < size) {
            Entry<K, V> entry = entries[index];
            if (entry != null && entry.getKey().equals(key)) {
                entries[index] = null;
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new EntrySet();
    }

    @Override
    public int size() {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (entries[i] != null) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void clear() {
        Arrays.fill(entries, null);
    }

    private void expandCapacity(int requiredCapacity) {
        int newCapacity = Math.max(entries.length * 2, requiredCapacity);
        @SuppressWarnings("unchecked")
        EntryImpl<K, V>[] newEntries = (EntryImpl<K, V>[]) Array.newInstance(EntryImpl.class, newCapacity);
        System.arraycopy(entries, 0, newEntries, 0, entries.length);
        entries = newEntries;
        size = entries.length;
    }

    @Data
    @AllArgsConstructor
    private static final class EntryImpl<K, V> implements Entry<K, V> {
        private K key;
        private V value;

        public V setValue(V value) {
            V oldValue = this.value;
            this.value = value;
            return oldValue;
        }
    }

    private class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public int size() {
            return IndexableMap.this.size();
        }

        @Override
        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            Object key = entry.getKey();
            V value = IndexableMap.this.get(key);
            return value != null && value.equals(entry.getValue());
        }

        @Override
        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry)) {
                return false;
            }
            Map.Entry<?, ?> entry = (Map.Entry<?, ?>) o;
            Object key = entry.getKey();
            V removedValue = IndexableMap.this.remove(key);
            return removedValue != null;
        }

        @Override
        public void clear() {
            IndexableMap.this.clear();
        }
    }

    private class EntryIterator implements Iterator<Entry<K, V>> {
        private int currentIndex = 0;
        private int lastReturnedIndex = -1;

        @Override
        public boolean hasNext() {
            while (currentIndex < size && entries[currentIndex] == null) {
                currentIndex++;
            }
            return currentIndex < size;
        }

        @Override
        public Entry<K, V> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Entry<K, V> entry = entries[currentIndex];
            lastReturnedIndex = currentIndex;
            currentIndex++;
            return entry;
        }

        @Override
        public void remove() {
            if (lastReturnedIndex < 0 || entries[lastReturnedIndex] == null) {
                throw new IllegalStateException();
            }
            entries[lastReturnedIndex] = null;
            lastReturnedIndex = -1;
        }
    }
}