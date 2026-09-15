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

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/**
 * Hands OkHttp sockets with Nagle's algorithm disabled.
 *
 * <p>Not a tuning detail: a stock {@code OkHttpClient} writes the request head and the request body as
 * separate segments, and with Nagle on, the second one waits for the peer to acknowledge the first.
 * Against a receiver that itself delays acknowledgements - which is the default on Linux - every publish
 * stalls on that 40 ms timer. Measured at 42 ms per publish with the option left alone, against 0.7 ms
 * with it set.
 *
 * <p>Applies to TLS too: OkHttp builds the raw socket through this factory and then wraps it, so the
 * option is already set by the time the handshake starts.
 */
final class NoDelaySocketFactory extends SocketFactory {

    private static Socket noDelay(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        return socket;
    }

    @Override
    public Socket createSocket() throws IOException {
        return noDelay(new Socket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return noDelay(new Socket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
        return noDelay(new Socket(host, port, localAddress, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return noDelay(new Socket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress localAddress, int localPort)
            throws IOException {
        return noDelay(new Socket(host, port, localAddress, localPort));
    }
}
