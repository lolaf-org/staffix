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
package org.lolaf.staffix.http.okhttp;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.SocketAddress;
import java.util.List;

/**
 * Answers OkHttp's authentication challenges from a {@link java.net.Authenticator}, which is what
 * {@code HttpSenderSettings} carries because it is what the JDK client and {@code HttpURLConnection}
 * both take.
 *
 * <p>Only the {@code Basic} scheme is answered, because a {@link PasswordAuthentication} is a username
 * and a password and nothing else; a {@code Digest} or {@code Negotiate} challenge needs state this
 * bridge does not have, and is declined rather than answered wrongly. Static credentials are better
 * sent as an {@code Authorization} header bound in the settings, which costs no rejected round trip at
 * all.
 */
@Slf4j
final class JdkAuthenticatorBridge {

    private JdkAuthenticatorBridge() {
    }

    /**
     * Answers a 407 from a proxy.
     */
    static Authenticator forProxy(java.net.Authenticator authenticator) {
        return (route, response) -> {
            if (response.request().header("Proxy-Authorization") != null) {
                // The credentials this bridge had were already refused; asking again only loops.
                return null;
            }
            Challenge challenge = basicChallengeOf(response);
            if (challenge == null) {
                return null;
            }
            InetSocketAddress address = proxyAddressOf(route);
            PasswordAuthentication credentials = authenticator.requestPasswordAuthenticationInstance(
                    address != null ? address.getHostString() : null,
                    address != null ? address.getAddress() : null,
                    address != null ? address.getPort() : 0,
                    "http",
                    challenge.realm(),
                    challenge.scheme(),
                    null,
                    java.net.Authenticator.RequestorType.PROXY);
            return authenticated(response, "Proxy-Authorization", credentials);
        };
    }

    /**
     * Answers a 401 from the endpoint itself.
     */
    static Authenticator forServer(java.net.Authenticator authenticator) {
        return (route, response) -> {
            if (response.request().header("Authorization") != null) {
                return null;
            }
            Challenge challenge = basicChallengeOf(response);
            if (challenge == null) {
                return null;
            }
            okhttp3.HttpUrl url = response.request().url();
            PasswordAuthentication credentials = authenticator.requestPasswordAuthenticationInstance(
                    url.host(),
                    (InetAddress) null,
                    url.port(),
                    url.scheme(),
                    challenge.realm(),
                    challenge.scheme(),
                    url.url(),
                    java.net.Authenticator.RequestorType.SERVER);
            return authenticated(response, "Authorization", credentials);
        };
    }

    private static Request authenticated(Response response, String header, PasswordAuthentication credentials) {
        if (credentials == null) {
            return null;
        }
        return response.request().newBuilder()
                .header(header, Credentials.basic(credentials.getUserName(), new String(credentials.getPassword())))
                .build();
    }

    private static Challenge basicChallengeOf(Response response) {
        List<Challenge> challenges = response.challenges();
        for (int i = 0; i < challenges.size(); i++) {
            if ("basic".equalsIgnoreCase(challenges.get(i).scheme())) {
                return challenges.get(i);
            }
        }
        log.warn("Not answering the {} challenge on {}: a java.net.Authenticator can only answer Basic. "
                        + "Bind an Authorization header in the settings instead.",
                challenges.isEmpty() ? "unnamed" : challenges.get(0).scheme(), response.request().url().redact());
        return null;
    }

    private static InetSocketAddress proxyAddressOf(Route route) {
        if (route == null) {
            return null;
        }
        SocketAddress address = route.proxy().address();
        return address instanceof InetSocketAddress ? (InetSocketAddress) address : null;
    }
}
