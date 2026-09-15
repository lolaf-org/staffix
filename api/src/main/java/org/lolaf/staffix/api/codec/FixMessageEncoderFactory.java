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
package org.lolaf.staffix.api.codec;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.FixDictionaryId;

import org.lolaf.staffix.api.time.Clock;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

/**
 * Factory for creating {@link FixMessageEncoder} instances for a specific FIX dictionary.
 * <p>
 * Implementations are loaded via {@link java.util.ServiceLoader SPI} and can be looked up
 * through the {@link Registry} by dictionary ID or encoder class.
 */
public interface FixMessageEncoderFactory {

    /**
     * Returns the {@link FixDictionaryId} this factory targets.
     *
     * @return the dictionary identifier
     */
    FixDictionaryId getTargetDictionaryId();

    /**
     * Returns {@code true} if this factory can create instances of the given encoder class.
     *
     * @param encoderClass the encoder class to check
     * @return {@code true} if the encoder class is registered in this factory
     */
    boolean isFactoryFor(Class<? extends FixMessageEncoder<?>> encoderClass);

    /**
     * Creates a new encoder instance.
     *
     * @param encoderClass the encoder class to instantiate
     * @param releaser     callback invoked when the encoder is released back to a pool, or {@code null} for unpooled usage
     * @param allocator    allocator for the encoder's internal {@link ByteBuffer}s, or {@code null} for default allocator
     * @param listener     listener notified on encoding lifecycle events, or {@code null}
     * @param clock        clock used for encoding timestamps, or {@code null}
     * @param <T>          the encoder type
     * @return a new encoder instance
     */
    <T extends FixMessageEncoder<?>> T newInstance(Class<T> encoderClass, Consumer<FixMessageEncoder<?>> releaser,
                                                   IntFunction<ByteBuffer> allocator, FixMessageEncodingListener listener, Clock clock);

    /**
     * SPI registry that discovers and caches all {@link FixMessageEncoderFactory} implementations
     * available on the classpath.
     */
    @Slf4j
    @UtilityClass
    class Registry {

        private static final Collection<FixMessageEncoderFactory> SPI_INSTANCES = loadInstances();

        /**
         * Returns the factory targeting the given dictionary.
         *
         * @param fixDictionaryId the dictionary to look up
         * @return the matching factory
         * @throws IllegalArgumentException if no factory is found for the dictionary
         */
        public static FixMessageEncoderFactory getInstance(FixDictionaryId fixDictionaryId) {
            return SPI_INSTANCES.stream().filter(r -> r.getTargetDictionaryId().equals(fixDictionaryId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unable to find any FixMessageEncoderFactory SPI instance for dictionary " + fixDictionaryId));
        }

        /**
         * Returns the factory that can create instances of the given encoder class.
         *
         * @param encoderClass the encoder class to look up
         * @return the matching factory
         * @throws IllegalArgumentException if no factory is found for the encoder class
         */
        public static FixMessageEncoderFactory find(Class<? extends FixMessageEncoder<?>> encoderClass) {
            return SPI_INSTANCES.stream().filter(f -> f.isFactoryFor(encoderClass))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unable to find any FixMessageEncoderFactory, did you import the right dictionaries="));
        }

        private static Collection<FixMessageEncoderFactory> loadInstances() {
            List<FixMessageEncoderFactory> instances = ServiceLoader.load(FixMessageEncoderFactory.class)
                    .stream().map(ServiceLoader.Provider::get).collect(Collectors.toList());
            log.info("Loaded FixMessageEncoderFactory SPI instances for dictionaries: {}", instances.stream().map(FixMessageEncoderFactory::getTargetDictionaryId).collect(Collectors.toList()));
            return instances;
        }
    }
}
