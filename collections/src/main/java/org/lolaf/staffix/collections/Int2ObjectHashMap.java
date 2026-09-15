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

import java.util.*;

/**
 * An open-addressing, linear-probing {@link Map} specialised for primitive {@code int} keys and non-null object values.
 * <p>
 * Keys are stored in a primitive {@code int[]} and values in a parallel {@code Object[]}, so lookups and insertions
 * through the primitive {@link #get(int)} / {@link #put(int, Object)} overloads never box the key and never allocate.
 * An empty slot is simply one whose value is {@code null}; null values are therefore not supported. This is a
 * from-scratch replacement for the Agrona map that used to back this class, exposing only the surface actually used by
 * the codebase plus the mandatory {@link Map} methods (unused optional operations throw
 * {@link UnsupportedOperationException} until they are needed).
 */
public class Int2ObjectHashMap<V> implements Map<Integer, V> {

    public static final float DEFAULT_LOAD_FACTOR = 0.65f;
    private static final int MIN_CAPACITY = 8;

    private final float loadFactor;
    private int resizeThreshold;
    private int size;

    private int[] keys;
    private Object[] values;

    private Collection<V> valuesView;

    public Int2ObjectHashMap(final int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    public Int2ObjectHashMap(final int initialCapacity, final float loadFactor) {
        if (loadFactor <= 0.0f || loadFactor >= 1.0f) {
            throw new IllegalArgumentException("loadFactor must be in the (0, 1) range: " + loadFactor);
        }
        this.loadFactor = loadFactor;
        final int capacity = findNextPositivePowerOfTwo(Math.max(MIN_CAPACITY, initialCapacity));
        this.resizeThreshold = (int) (capacity * loadFactor);
        this.keys = new int[capacity];
        this.values = new Object[capacity];
    }

    // ------------------------------------------------------------------------------------------------------------
    // Primitive, allocation-free hot path
    // ------------------------------------------------------------------------------------------------------------

    private static int hashIndex(final int key, final int mask) {
        int hash = key * 0x9E3779B9;
        hash ^= hash >>> 15;
        return hash & mask;
    }

    private static int findNextPositivePowerOfTwo(final int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    @SuppressWarnings("unchecked")
    public V get(final int key) {
        final Object[] valuesLocal = this.values;
        final int mask = valuesLocal.length - 1;
        int index = hashIndex(key, mask);
        Object value;
        while ((value = valuesLocal[index]) != null) {
            if (keys[index] == key) {
                break;
            }
            index = (index + 1) & mask;
        }
        return (V) value;
    }

    @SuppressWarnings("unchecked")
    public V put(final int key, final V value) {
        if (value == null) {
            throw new NullPointerException("null values are not supported");
        }
        final Object[] valuesLocal = this.values;
        final int mask = valuesLocal.length - 1;
        int index = hashIndex(key, mask);
        Object existing;
        while ((existing = valuesLocal[index]) != null) {
            if (keys[index] == key) {
                valuesLocal[index] = value;
                return (V) existing;
            }
            index = (index + 1) & mask;
        }
        keys[index] = key;
        valuesLocal[index] = value;
        if (++size > resizeThreshold) {
            increaseCapacity();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Map<Integer, V>
    // ------------------------------------------------------------------------------------------------------------

    public boolean containsKey(final int key) {
        final Object[] valuesLocal = this.values;
        final int mask = valuesLocal.length - 1;
        int index = hashIndex(key, mask);
        while (valuesLocal[index] != null) {
            if (keys[index] == key) {
                return true;
            }
            index = (index + 1) & mask;
        }
        return false;
    }

    /**
     * Shrinks the backing arrays to the smallest power-of-two capacity that still honours the load factor for the
     * current {@link #size()}. Useful after a burst of insertions that will not be repeated.
     */
    public void compact() {
        final int idealCapacity = (int) Math.round(size * (1.0d / loadFactor));
        final int newCapacity = findNextPositivePowerOfTwo(Math.max(MIN_CAPACITY, idealCapacity));
        if (newCapacity < values.length) {
            rehash(newCapacity);
        }
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public V get(final Object key) {
        return key instanceof Integer ? get(((Integer) key).intValue()) : null;
    }

    @Override
    public V put(final Integer key, final V value) {
        return put(key.intValue(), value);
    }

    // Unused optional operations - implement on demand rather than carrying untested code.

    @Override
    public boolean containsKey(final Object key) {
        return key instanceof Integer && containsKey(((Integer) key).intValue());
    }

    @Override
    public Collection<V> values() {
        Collection<V> view = valuesView;
        if (view == null) {
            view = valuesView = new Values();
        }
        return view;
    }

    @Override
    public boolean containsValue(final Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V remove(final Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(final Map<? extends Integer, ? extends V> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------------------------

    @Override
    public Set<Integer> keySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Entry<Integer, V>> entrySet() {
        throw new UnsupportedOperationException();
    }

    private void increaseCapacity() {
        final int newCapacity = values.length << 1;
        if (newCapacity < 0) {
            throw new IllegalStateException("max capacity reached at size " + size);
        }
        rehash(newCapacity);
    }

    private void rehash(final int newCapacity) {
        final int[] oldKeys = keys;
        final Object[] oldValues = values;
        final int[] newKeys = new int[newCapacity];
        final Object[] newValues = new Object[newCapacity];
        final int mask = newCapacity - 1;
        for (int i = 0, n = oldValues.length; i < n; i++) {
            final Object value = oldValues[i];
            if (value != null) {
                final int key = oldKeys[i];
                int index = hashIndex(key, mask);
                while (newValues[index] != null) {
                    index = (index + 1) & mask;
                }
                newKeys[index] = key;
                newValues[index] = value;
            }
        }
        this.keys = newKeys;
        this.values = newValues;
        this.resizeThreshold = (int) (newCapacity * loadFactor);
    }

    private final class Values extends AbstractCollection<V> {

        @Override
        public int size() {
            return Int2ObjectHashMap.this.size;
        }

        @Override
        public boolean isEmpty() {
            return Int2ObjectHashMap.this.size == 0;
        }

        @Override
        public Iterator<V> iterator() {
            return new ValueIterator();
        }
    }

    private final class ValueIterator implements Iterator<V> {

        private int remaining = size;
        private int position;

        @Override
        public boolean hasNext() {
            return remaining > 0;
        }

        @Override
        @SuppressWarnings("unchecked")
        public V next() {
            if (remaining == 0) {
                throw new NoSuchElementException();
            }
            final Object[] valuesLocal = Int2ObjectHashMap.this.values;
            int index = position;
            while (valuesLocal[index] == null) {
                index++;
            }
            position = index + 1;
            remaining--;
            return (V) valuesLocal[index];
        }
    }
}
