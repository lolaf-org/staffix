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
 * An open-addressing, linear-probing {@link Map} specialised for primitive {@code long} keys and non-null object
 * values. See {@link Int2ObjectHashMap} for the design; this is the {@code long}-keyed counterpart. Keys live in a
 * primitive {@code long[]}, values in a parallel {@code Object[]}, an empty slot is one with a {@code null} value, and
 * the primitive {@link #get(long)} / {@link #put(long, Object)} overloads are boxing-free and allocation-free.
 */
public class Long2ObjectHashMap<V> implements Map<Long, V> {

    public static final float DEFAULT_LOAD_FACTOR = 0.65f;
    private static final int MIN_CAPACITY = 8;

    private final float loadFactor;
    private int resizeThreshold;
    private int size;

    private long[] keys;
    private Object[] values;

    private Collection<V> valuesView;

    public Long2ObjectHashMap(final int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    public Long2ObjectHashMap(final int initialCapacity, final float loadFactor) {
        if (loadFactor <= 0.0f || loadFactor >= 1.0f) {
            throw new IllegalArgumentException("loadFactor must be in the (0, 1) range: " + loadFactor);
        }
        this.loadFactor = loadFactor;
        final int capacity = findNextPositivePowerOfTwo(Math.max(MIN_CAPACITY, initialCapacity));
        this.resizeThreshold = (int) (capacity * loadFactor);
        this.keys = new long[capacity];
        this.values = new Object[capacity];
    }

    // ------------------------------------------------------------------------------------------------------------
    // Primitive, allocation-free hot path
    // ------------------------------------------------------------------------------------------------------------

    private static int hashIndex(final long key, final int mask) {
        long hash = key * 0x9E3779B97F4A7C15L;
        hash ^= hash >>> 32;
        return (int) hash & mask;
    }

    private static int findNextPositivePowerOfTwo(final int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

    @SuppressWarnings("unchecked")
    public V get(final long key) {
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
    public V put(final long key, final V value) {
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
    // Map<Long, V>
    // ------------------------------------------------------------------------------------------------------------

    public boolean containsKey(final long key) {
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
        return key instanceof Long ? get(((Long) key).longValue()) : null;
    }

    @Override
    public V put(final Long key, final V value) {
        return put(key.longValue(), value);
    }

    // Unused optional operations - implement on demand rather than carrying untested code.

    @Override
    public boolean containsKey(final Object key) {
        return key instanceof Long && containsKey(((Long) key).longValue());
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
    public void putAll(final Map<? extends Long, ? extends V> m) {
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
    public Set<Long> keySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Entry<Long, V>> entrySet() {
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
        final long[] oldKeys = keys;
        final Object[] oldValues = values;
        final long[] newKeys = new long[newCapacity];
        final Object[] newValues = new Object[newCapacity];
        final int mask = newCapacity - 1;
        for (int i = 0, n = oldValues.length; i < n; i++) {
            final Object value = oldValues[i];
            if (value != null) {
                final long key = oldKeys[i];
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
            return Long2ObjectHashMap.this.size;
        }

        @Override
        public boolean isEmpty() {
            return Long2ObjectHashMap.this.size == 0;
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
            final Object[] valuesLocal = Long2ObjectHashMap.this.values;
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
