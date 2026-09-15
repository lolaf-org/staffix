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
package org.lolaf.staffix.spring.boot.mapper;

import lombok.experimental.UtilityClass;
import org.lolaf.betty.api.settings.SSLSettings;
import org.lolaf.betty.api.settings.ServerSSLSettings;
import org.lolaf.staffix.spring.boot.props.SslProps;
import org.springframework.context.ApplicationContext;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;

/**
 * Builds the TLS settings from their properties, loading the keystores named there.
 */
@UtilityClass
public class SslPropsMapper {

    public static SSLSettings toSSLSettings(SslProps props, ApplicationContext ctx) {
        if (props == null) {
            return null;
        }
        return SSLSettings.builder()
                .sslContext(resolveContext(props, ctx))
                .build();
    }

    public static ServerSSLSettings toServerSSLSettings(SslProps.ServerSslProps props, ApplicationContext ctx) {
        if (props == null) {
            return null;
        }
        return ServerSSLSettings.builder()
                .sslContext(resolveContext(props, ctx))
                .needClientAuth(props.isNeedClientAuth())
                .wantClientAuth(props.isWantClientAuth())
                .build();
    }

    private static SSLContext resolveContext(SslProps props, ApplicationContext ctx) {
        if (props.getSslContextBean() != null && !props.getSslContextBean().isBlank()) {
            return ctx.getBean(props.getSslContextBean(), SSLContext.class);
        }
        try {
            SSLContext sslContext = SSLContext.getInstance(props.getProtocol());
            KeyManagerFactory kmf = null;
            if (props.getKeyStorePath() != null) {
                KeyStore keyStore = KeyStore.getInstance(props.getKeyStoreType());
                try (InputStream in = new FileInputStream(props.getKeyStorePath())) {
                    char[] pwd = props.getKeyStorePassword() == null ? new char[0] : props.getKeyStorePassword().toCharArray();
                    keyStore.load(in, pwd);
                    kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    kmf.init(keyStore, pwd);
                }
            }
            TrustManagerFactory tmf = null;
            if (props.getTrustStorePath() != null) {
                KeyStore trustStore = KeyStore.getInstance(props.getTrustStoreType());
                try (InputStream in = new FileInputStream(props.getTrustStorePath())) {
                    char[] pwd = props.getTrustStorePassword() == null ? new char[0] : props.getTrustStorePassword().toCharArray();
                    trustStore.load(in, pwd);
                    tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                    tmf.init(trustStore);
                }
            }
            sslContext.init(
                    kmf == null ? null : kmf.getKeyManagers(),
                    tmf == null ? null : tmf.getTrustManagers(),
                    null);
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to build SSLContext from staffix ssl props", e);
        }
    }
}
