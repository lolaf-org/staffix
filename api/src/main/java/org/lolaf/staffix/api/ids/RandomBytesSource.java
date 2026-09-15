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
package org.lolaf.staffix.api.ids;

import java.security.SecureRandom;
import java.util.Random;

/**
 * A source of random bytes, filling a buffer the caller owns.
 *
 * <p>The signature is deliberately the one every JDK random API already has, so any of them plugs in as a method
 * reference, with no adapter:
 *
 * <pre>
 * new UUIDsGenerator(uuidV7, secureRandom::nextBytes);                       // java.security.SecureRandom
 * new UUIDsGenerator(uuidV7, random::nextBytes);                             // java.util.Random
 * new UUIDsGenerator(uuidV7, RandomGenerator.of("Xoshiro256PlusPlus")::nextBytes);  // JDK 17+
 * </pre>
 *
 * <p>{@code java.util.random.RandomGenerator} arrived in JDK 17 and this module compiles for Java 11, which is why
 * this interface exists rather than the JDK's: naming that type here would pin the whole library to 17. A method
 * reference to it costs nothing and works on any runtime that has it.
 *
 * <p><b>Thread safety is the source's business.</b> The generator calls it from whatever thread mints an id, so it
 * must either be safe for concurrent use ({@link SecureRandom} and {@link Random} are) or be confined to one thread
 * by the caller. Most {@code RandomGenerator} implementations are <b>not</b> thread safe.
 *
 * <p><b>Strength is the source's business too.</b> A version 4 UUID is only as unpredictable as the bytes behind it;
 * a fast non-cryptographic generator produces valid, unique, but guessable ids. The default is a per-thread
 * {@link SecureRandom}, matching {@link java.util.UUID#randomUUID()}.
 */
@FunctionalInterface
public interface RandomBytesSource {

    /**
     * Fills {@code dst} completely with random bytes.
     *
     * @param dst the buffer to fill; owned and reused by the caller, never retained by the source
     */
    void nextBytes(byte[] dst);
}
