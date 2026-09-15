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

import java.util.Collection;
import java.util.Iterator;
import java.util.function.IntSupplier;

/**
 * An {@link IndexableList} fixed at construction, for the registries built once at startup and read on every
 * message.
 */
public class ImmutableIndexableList<T extends IntSupplier> extends IndexableList<T> {

    public ImmutableIndexableList(Class<T> intSupplierClass, Collection<T> collection) {
        super(intSupplierClass, collection.size());
        collection.forEach(super::add);
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean add(T element) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object obj) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<T> iterator() {
        return new ImmutableIterator<>(super.iterator());
    }

    @Value
    private static class ImmutableIterator<T> implements Iterator<T> {

        Iterator<T> i;

        @Override
        public boolean hasNext() {
            return i.hasNext();
        }

        @Override
        public T next() {
            return i.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}