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
package org.lolaf.staffix.stores.messages.file;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.MappedByteBuffer;

/**
 * Makes a store call's writes durable. Writes go through the mapping, which no channel option such as {@code DSYNC}
 * covers: only a force reaches the disk.
 */
interface WritesSync {

    WritesSync DISABLED = messages -> {
    };

    /**
     * @param sliceOffset where the store's aligned slice starts in the mapping
     */
    static WritesSync of(boolean syncWrites, MappedByteBuffer mapping, int sliceOffset, int headerLength) {
        if (!syncWrites) {
            return DISABLED;
        }
        if (Range.FORCE_RANGE == null) {
            return new WholeMapping(mapping);
        }
        return new Range(mapping, sliceOffset, headerLength);
    }

    /**
     * Forces the messages written, then the store's header, so that the header never points past what a crash kept.
     */
    void sync(UnsyncedRange messages);

    /**
     * Java 11, where {@code MappedByteBuffer} forces only as a whole.
     */
    @RequiredArgsConstructor
    final class WholeMapping implements WritesSync {

        private final MappedByteBuffer mapping;

        @Override
        public void sync(UnsyncedRange messages) {
            mapping.force();
        }
    }

    /**
     * Java 13 and later, through {@code MappedByteBuffer.force(int, int)}: the build targets 11.
     */
    @RequiredArgsConstructor
    final class Range implements WritesSync {

        static final @Nullable MethodHandle FORCE_RANGE = forceRangeHandle();

        private final MappedByteBuffer mapping;
        private final int sliceOffset;
        private final int headerLength;

        private static @Nullable MethodHandle forceRangeHandle() {
            try {
                // void, so that invokeExact can be called as a statement
                return MethodHandles.publicLookup().findVirtual(MappedByteBuffer.class, "force",
                                MethodType.methodType(MappedByteBuffer.class, int.class, int.class))
                        .asType(MethodType.methodType(void.class, MappedByteBuffer.class, int.class, int.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                return null;
            }
        }

        @Override
        public void sync(UnsyncedRange messages) {
            if (messages.isNotEmpty()) {
                forceRange(messages.getFrom(), messages.length());
            }
            forceRange(0, headerLength);
        }

        private void forceRange(int index, int length) {
            try {
                FORCE_RANGE.invokeExact(mapping, sliceOffset + index, length);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }
}
