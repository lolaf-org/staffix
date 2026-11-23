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
package org.lolaf.staffix.benchmarks.impl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.ReplicationEncryptionSettings;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.Security;

/**
 * The replication's encryption parameter values, named so that a result's label reads on its own, as {@link HaParams}.
 * Nothing of FIX: shared by the cipher benchmarks and the HA pair's.
 */
@UtilityClass
public class CipherParams {

    /**
     * The replication's cipher, a stream cipher with its MAC or an AEAD; {@link #CLEAR} does not encrypt.
     */
    @RequiredArgsConstructor
    public enum Encryption {
        CLEAR(null, 0),
        AES_GCM_128("AES/GCM/NoPadding", 128),
        AES_GCM_256("AES/GCM/NoPadding", 256),
        CHACHA20_POLY1305("ChaCha20-Poly1305", 256),
        AES_CTR_HMAC_SHA256("AES/CTR/NoPadding", 256),
        CHACHA20_HMAC_SHA256("ChaCha20", 256);

        private static final byte[] SECRET = "the-benchmarks-secret-of-the-pair".getBytes(StandardCharsets.UTF_8);

        private final @Nullable String cipher;
        private final int keyBits;

        /**
         * @param provider the JCE provider's name, null for the JVM's choice
         */
        public ReplicationEncryptionSettings settings(@Nullable String provider) {
            if (cipher == null) {
                return ReplicationEncryptionSettings.builder().build();
            }
            return ReplicationEncryptionSettings.builder().mode(EncryptionMode.REQUIRED).secret(SECRET).cipher(cipher)
                    .keyBits(keyBits).mac("HmacSHA256").provider(provider).build();
        }
    }

    /**
     * The JCE provider of the cipher and the MAC. The others' jars are copied to {@code target/providers} (or the
     * {@code staffix.benchmarks.providers} directory) and loaded each in a class loader of its own: both Bouncy Castle
     * jars hold the same classes, so neither can be on the class path.
     */
    @RequiredArgsConstructor
    public enum CryptoProvider {
        PROVIDER_JDK(null, null),
        PROVIDER_CORRETTO("corretto-crypto-provider.jar", "com.amazon.corretto.crypto.provider.AmazonCorrettoCryptoProvider"),
        PROVIDER_BC("bcprov.jar", "org.bouncycastle.jce.provider.BouncyCastleProvider"),
        PROVIDER_BC_LTS("bcprov-lts.jar", "org.bouncycastle.jce.provider.BouncyCastleProvider");

        private final @Nullable String jar;
        private final @Nullable String providerClass;

        /**
         * Registers the provider, once per JVM.
         *
         * @return its name, null for the JVM's own
         */
        public synchronized @Nullable String install() throws GeneralSecurityException {
            if (jar == null || providerClass == null) {
                return null;
            }
            File file = new File(System.getProperty("staffix.benchmarks.providers", "target/providers"), jar);
            try {
                URLClassLoader loader = new URLClassLoader(new URL[]{file.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
                Provider provider = (Provider) loader.loadClass(providerClass).getConstructor().newInstance();
                Provider installed = Security.getProvider(provider.getName());
                if (installed == null) {
                    Security.addProvider(provider);
                    installed = provider;
                }
                return installed.getName();
            } catch (MalformedURLException | ReflectiveOperationException e) {
                throw new GeneralSecurityException("Cannot load the provider " + providerClass + " from " + file, e);
            }
        }
    }

    @Getter
    @RequiredArgsConstructor
    public enum MessageSize {
        MESSAGE_200_B(200),
        MESSAGE_1_KIB(1024),
        MESSAGE_16_KIB(16 * 1024);

        private final int bytes;
    }
}
