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
package org.lolaf.staffix.ha;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReplicationEncryptionSettings {

    private static final String SECRET_TEXT = "s3cr3t-0123456789";
    private static final byte[] SECRET = SECRET_TEXT.getBytes(StandardCharsets.UTF_8);

    private static ReplicationEncryptionSettings.ReplicationEncryptionSettingsBuilder encrypting() {
        return ReplicationEncryptionSettings.builder().mode(EncryptionMode.REQUIRED).secret(SECRET);
    }

    @Test
    void disabledByDefaultAndCheckedForNothing() {
        ReplicationEncryptionSettings settings = ReplicationEncryptionSettings.builder()
                .cipher("AES/ECB/PKCS5Padding").provider("NoSuchProvider").build();

        assertThat(ReplicationHaSettings.builder().build().getEncryption().getMode())
                .isEqualTo(EncryptionMode.DISABLED);
        assertThatCode(settings::validate).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(value = EncryptionMode.class, names = {"PREFERRED", "REQUIRED"})
    void encryptingNeedsASecret(EncryptionMode mode) {
        ReplicationEncryptionSettings settings = ReplicationEncryptionSettings.builder().mode(mode).build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encryption.secret");
    }

    @Test
    void theSecretIsAtLeastSixteenBytes() {
        assertThatThrownBy(() -> encrypting().secret(new byte[15]).build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encryption.secret");
        assertThatCode(() -> encrypting().secret(new byte[16]).build().validate()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/GCM/NoPadding", "ChaCha20-Poly1305", "AES/CTR/NoPadding", "ChaCha20"})
    void everySupportedCipherRunsOnThisJvm(String cipher) {
        assertThatCode(() -> encrypting().cipher(cipher).build().validate()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"AES/CBC/PKCS5Padding", "AES/ECB/NoPadding", "AES", "aes/gcm/nopadding", "ChaCha"})
    void aCipherWithPaddingOrNoIntegrityOrMisspeltIsRefused(String cipher) {
        assertThatThrownBy(() -> encrypting().cipher(cipher).build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encryption.cipher");
    }

    @Test
    void theKeySizeFitsTheCipher() {
        assertThatCode(() -> encrypting().keyBits(128).build().validate()).doesNotThrowAnyException();
        assertThatThrownBy(() -> encrypting().keyBits(512).build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encryption.keyBits");
        assertThatThrownBy(() -> encrypting().cipher("ChaCha20").keyBits(128).build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encryption.keyBits");
    }

    @Test
    void theProviderMustBeInstalledAndOfferTheCipher() {
        assertThatCode(() -> encrypting().provider("SunJCE").build().validate()).doesNotThrowAnyException();
        assertThatThrownBy(() -> encrypting().provider("NoSuchProvider").build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encryption.provider");
        assertThatThrownBy(() -> encrypting().provider("SUN").build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encryption.cipher");
    }

    @Test
    void theMacIsCheckedForAStreamCipherOnly() {
        assertThatThrownBy(() -> encrypting().cipher("AES/CTR/NoPadding").mac("HmacNothing").build().validate())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("encryption.mac");
        assertThatCode(() -> encrypting().cipher("AES/GCM/NoPadding").mac("HmacNothing").build().validate())
                .doesNotThrowAnyException();
    }

    @Test
    void theEngineSettingsCheckTheirEncryption() {
        ReplicationHaSettings settings = ReplicationHaSettings.builder()
                .encryption(ReplicationEncryptionSettings.builder().mode(EncryptionMode.PREFERRED).build()).build();

        assertThatThrownBy(settings::validate).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("encryption.secret");
    }

    @Test
    void theSecretIsNeverShown() {
        ReplicationEncryptionSettings.ReplicationEncryptionSettingsBuilder builder = encrypting();

        assertThat(builder.build().toString()).doesNotContain("secret").doesNotContain(SECRET_TEXT);
        assertThat(builder.toString()).doesNotContain("secret").doesNotContain(SECRET_TEXT);
        assertThat(ReplicationHaSettings.builder().encryption(builder.build()).toString())
                .doesNotContain(SECRET_TEXT);
        assertThatThrownBy(() -> builder.cipher("AES/CBC/PKCS5Padding").build().validate())
                .message().doesNotContain(SECRET_TEXT);
    }
}
