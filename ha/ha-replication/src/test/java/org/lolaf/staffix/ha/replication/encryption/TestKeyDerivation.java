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
package org.lolaf.staffix.ha.replication.encryption;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HKDF-SHA256 against the test vectors of RFC 5869, appendix A: both peers derive with the same code, so a defect
 * would pass every round trip.
 */
class TestKeyDerivation {

    private static final byte[] INPUT_KEY = filled(22, 0x0b);

    private static byte[] filled(int length, int value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static byte[] hex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    @Test
    void extractIsTestCaseOne() {
        byte[] salt = hex("000102030405060708090a0b0c");

        assertThat(KeyDerivation.extract(salt, INPUT_KEY))
                .isEqualTo(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"));
    }

    @Test
    void extractAndExpandOverTwoBlocksAreTestCaseThree() {
        // a salt of HashLen zeros is how RFC 5869 reads an empty one, which a JCE key cannot be
        byte[] pseudoRandomKey = KeyDerivation.extract(new byte[32], INPUT_KEY);

        assertThat(pseudoRandomKey).isEqualTo(hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"));
        assertThat(KeyDerivation.expand(pseudoRandomKey, "", 42)).isEqualTo(
                hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"));
    }
}
