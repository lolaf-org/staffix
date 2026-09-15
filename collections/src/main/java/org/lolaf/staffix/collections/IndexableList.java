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

import java.lang.reflect.Array;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * A list indexed by each element's own int, so a lookup is an array access rather than a scan.
 *
 * <p>Elements supply their index through {@link java.util.function.IntSupplier}, which the engine's types
 * already do - a field by tag, a message type by its hash - so nothing has to be boxed or hashed on the message
 * path.
 */
public class IndexableList<T extends IntSupplier> extends AbstractCollection<T> {
    private static final int DEFAULT_CAPACITY = 10;
    private final Class<T> intSupplierClass;
    private T[] elements;
    private int size;

    public IndexableList(Class<T> intSupplierClass) {
        this(intSupplierClass, DEFAULT_CAPACITY);
    }

    @SuppressWarnings("unchecked")
    public IndexableList(Class<T> intSupplierClass, int initialCapacity) {
        this.elements = (T[]) Array.newInstance(intSupplierClass, initialCapacity);
        this.size = initialCapacity;
        this.intSupplierClass = intSupplierClass;
    }

    @Override
    public void clear() {
        Arrays.fill(elements, null);
    }

    @Override
    public boolean add(T element) {
        if (element == null) {
            throw new NullPointerException("Element cannot be null");
        }

        int index = element.getAsInt();
        if (index < 0) {
            return false;
        }

        if (index >= elements.length) {
            expandCapacity(index + 1);
        }

        elements[index] = element;
        return true;
    }

    @Override
    public boolean contains(Object obj) {
        int index = ((IntSupplier) obj).getAsInt();
        if (index >= 0 && index < size) {
            T element = elements[index];
            return element != null && element.equals(obj);
        }
        return false;
    }

    @Override
    public boolean remove(Object obj) {
        int index = ((IntSupplier) obj).getAsInt();
        if (index >= 0 && index < size) {
            T element = elements[index];
            if (element != null && element.equals(obj)) {
                elements[index] = null;
                return true;
            }
        }
        return false;
    }

    @Override
    public Iterator<T> iterator() {
        return new IndexableIterator();
    }

    @Override
    public int size() {
        int count = 0;
        for (int i = 0; i < size; i++) {
            if (elements[i] != null) {
                count++;
            }
        }
        return count;
    }

    public T get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return elements[index];
    }

    @Override
    public void forEach(Consumer<? super T> action) {
        for (int i = 0; i < size; i++) {
            if (elements[i] != null) {
                action.accept(elements[i]);
            }
        }
    }

    private void expandCapacity(int requiredCapacity) {
        int newCapacity = Math.max(elements.length * 2, requiredCapacity);
        @SuppressWarnings("unchecked")
        T[] newElements = (T[]) Array.newInstance(intSupplierClass, newCapacity);
        System.arraycopy(elements, 0, newElements, 0, elements.length);
        elements = newElements;
        size = elements.length;
    }

    private class IndexableIterator implements Iterator<T> {
        private int currentIndex = 0;

        @Override
        public boolean hasNext() {
            while (currentIndex < size && elements[currentIndex] == null) {
                currentIndex++;
            }
            return currentIndex < size;
        }

        @Override
        public T next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            T element = elements[currentIndex];
            currentIndex++;
            return element;
        }

        @Override
        public void remove() {
            if (currentIndex == 0 || elements[currentIndex - 1] == null) {
                throw new IllegalStateException();
            }
            elements[currentIndex - 1] = null;
        }
    }
}