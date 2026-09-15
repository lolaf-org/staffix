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
package org.lolaf.staffix.tests;

import lombok.experimental.UtilityClass;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.SecureRandom;

/**
 * The {@link SSLContext}s the tests negotiate TLS with, built from the self-signed key pair and truststore shipped
 * beside this class. The client and the server each trust the other's certificate and nothing else, so a connection
 * standing up is evidence the two ends really authenticated one another.
 */
@UtilityClass
public class SSLUtils {

    public static final String STORE_PASSWORD = "password";

    public static SSLContext getClientSSLContext() {
        return createSSLContext("clientkeystore.p12", "clienttruststore.jks");
    }

    public static SSLContext getServerSSLContext() {
        return createSSLContext("serverkeystore.p12", "servertruststore.jks");
    }

    public static SSLContext getVoidSSLContext() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            ctx.init(null, null, new SecureRandom());
            return ctx;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static SSLContext createSSLContext(String keyStoreName, String trustStoreName) {
        try {
            char[] passphrase = STORE_PASSWORD.toCharArray();
            SSLContext ctx = SSLContext.getInstance("TLSv1.3");
            KeyStore ks = KeyStore.getInstance("PKCS12");
            InputStream in = SSLUtils.class.getClassLoader().getResourceAsStream(keyStoreName);
            if (in == null) {
                throw new IllegalStateException("Cannot find " + keyStoreName);
            }
            ks.load(in, passphrase);
            in.close();

            KeyStore ts = KeyStore.getInstance("JKS");
            in = SSLUtils.class.getClassLoader().getResourceAsStream(trustStoreName);
            if (in == null) {
                throw new IllegalStateException("Cannot find " + keyStoreName);
            }
            ts.load(in, passphrase);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, passphrase);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());
            return ctx;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}