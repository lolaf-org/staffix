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
package org.lolaf.staffix.api.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestSha256 {

    /**
     * FIPS 180-2's test vector.
     */
    @Test
    void theDigestIsTheStandardOneInLowercaseHex() {
        assertThat(Sha256.hex("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void leadingZerosAreKept() {
        assertThat(Sha256.hex(new byte[0])).hasSize(64);
        assertThat(Sha256.hex("39")).isEqualTo("0b918943df0962bc7a1824c0555a389347b4febdc7cf9d1254406d80ce44e3f9");
    }
}
