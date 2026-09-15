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
package org.lolaf.staffix.spring.boot.props;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * TLS configuration as properties - keystore, truststore, protocols and ciphers.
 */
@Data
public class SslProps {

    /**
     * Spring bean name of a pre-built {@link javax.net.ssl.SSLContext}. When set, it overrides
     * the keystore/truststore properties below.
     */
    private String sslContextBean;

    /**
     * Path to the keystore holding this side's certificate and key.
     */
    private String keyStorePath;
    /**
     * Password for the keystore.
     */
    private String keyStorePassword;
    /**
     * Keystore format. PKCS12 by default, which is the portable one.
     */
    private String keyStoreType = "PKCS12";

    /**
     * Path to the truststore of certificates the peer may present. Omit to use the JVM's.
     */
    private String trustStorePath;
    /**
     * Password for the truststore.
     */
    private String trustStorePassword;
    /**
     * Truststore format.
     */
    private String trustStoreType = "PKCS12";

    /**
     * TLS version. TLSv1.3 by default; lower it only for a counterparty that cannot speak it.
     */
    private String protocol = "TLSv1.3";

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ServerSslProps extends SslProps {
        /**
         * Acceptor only: refuse a connection that presents no client certificate.
         */
        private boolean needClientAuth;
        /**
         * Acceptor only: ask for a client certificate but accept a connection without one.
         */
        private boolean wantClientAuth;
    }
}
