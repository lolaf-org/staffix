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

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Mutable, allocation-friendly holder for the two 64-bit halves of a UUID, in the same convention as
 * {@link UUID#getMostSignificantBits()} / {@link UUID#getLeastSignificantBits()}.
 *
 * <p>Reusable as an in-place target on both sides of the UUID path: {@link UUIDv7#generateMsbLsb(MsbLsb)} writes a
 * newly generated value into it, and the UUID serdes write a decoded one, so neither has to allocate a
 * {@link UUID} to carry the two halves around.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MsbLsb {

    long msb;
    long lsb;

}
