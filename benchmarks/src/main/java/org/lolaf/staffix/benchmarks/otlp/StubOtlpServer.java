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
package org.lolaf.staffix.benchmarks.otlp;

import javax.net.ssl.*;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

/**
 * A stub OTLP receiver that drains each request and answers a fixed {@code 200} with no body.
 *
 * <p>Deliberately raw sockets rather than {@code com.sun.net.httpserver}. That server writes a response
 * across several segments, which on Linux collides with the 40 ms delayed-ACK timer and adds a 40 ms
 * step to whichever client happens to trip it - an artifact large enough to bury the difference between
 * the senders under test. Here the whole response goes out in one write on a {@code TCP_NODELAY}
 * socket, and the connection stays open so the client's pool behaves as it would against a real
 * collector.
 *
 * <p>Run as a {@code main} in a JVM of its own by {@link OtlpHttpSenderBenchmark}, so that the server's
 * own allocation does not land in the benchmark JVM's GC profile. It prints {@code PORT <n>} to stdout
 * once listening, and serves until killed.
 */
public final class StubOtlpServer {

    /**
     * Password of the benchmark keystore. A self-signed certificate for a loopback stub, checked in
     * beside this class; it guards nothing.
     */
    public static final String KEYSTORE_PASSWORD = "changeit";

    /**
     * Classpath resource holding that keystore.
     */
    public static final String KEYSTORE_RESOURCE = "/otlp-stub-keystore.p12";

    /**
     * Path the receiver answers on.
     */
    public static final String PATH = "/v1/metrics";

    private static final byte[] RESPONSE =
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    /**
     * The client's connection preface, which every HTTP/2 connection opens with.
     */
    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int FRAME_HEADER_LENGTH = 9;
    private static final int TYPE_DATA = 0x0;
    private static final int TYPE_HEADERS = 0x1;
    private static final int TYPE_SETTINGS = 0x4;
    private static final int TYPE_PING = 0x6;
    private static final int TYPE_WINDOW_UPDATE = 0x8;
    private static final int FLAG_END_STREAM = 0x1;
    private static final int FLAG_ACK = 0x1;
    private static final int FLAG_END_HEADERS = 0x4;

    // Enough HTTP/2 to be a receiver: request headers are skipped by length rather than HPACK-decoded,
    // since every sender under test posts one body to one endpoint. That keeps this cheap enough to
    // measure latency against, which MockWebServer at ~170 us an exchange is not.
    /**
     * The connection window starts at 65535 and only WINDOW_UPDATE can enlarge it - no SETTINGS can - so
     * a receiver that ignores it stalls for good after the fourth 16 KB publish.
     */
    private static final int WINDOW_REFRESH_THRESHOLD = 32 * 1024;
    private static final byte[] EMPTY = new byte[0];
    private static final byte[] RESPONSE_HEADERS = {(byte) 0x88};

    private StubOtlpServer() {
    }

    /**
     * @param args a single argument: {@code http}, {@code https}, {@code http2} or {@code https2}, the
     *             last two speaking HTTP/2 - by prior knowledge on cleartext, through an ALPN offer of
     *             {@code h2} over TLS
     * @throws Exception if the server or its TLS context cannot be built
     */
    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "http";
        boolean tls = mode.startsWith("https");
        boolean http2 = mode.endsWith("2");
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (ServerSocket serverSocket = tls
                ? sslContext().getServerSocketFactory().createServerSocket(0, 50, loopback)
                : new ServerSocket(0, 50, loopback)) {
            System.out.println("PORT " + serverSocket.getLocalPort());
            System.out.flush();
            while (true) {
                Socket socket = serverSocket.accept();
                if (tls && http2) {
                    SSLParameters parameters = ((SSLSocket) socket).getSSLParameters();
                    parameters.setApplicationProtocols(new String[]{"h2"});
                    ((SSLSocket) socket).setSSLParameters(parameters);
                }
                Thread connection = new Thread(
                        http2 ? () -> serveHttp2(socket) : () -> serve(socket), "stub-otlp-connection");
                connection.setDaemon(true);
                connection.start();
            }
        }
    }

    /**
     * An SSL context holding the checked-in self-signed certificate, as both key material for the
     * server and the only trust anchor a client needs.
     *
     * @return the context
     * @throws Exception if the keystore cannot be read or the context cannot be built
     */
    public static SSLContext sslContext() throws Exception {
        KeyStore keyStore = keyStore();
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers(keyStore), null);
        return context;
    }

    /**
     * The trust manager over that same certificate, for clients that need it handed to them separately
     * from the context.
     *
     * @return the trust manager
     * @throws Exception if the keystore cannot be read
     */
    public static X509TrustManager trustManager() throws Exception {
        for (TrustManager trustManager : trustManagers(keyStore())) {
            if (trustManager instanceof X509TrustManager) {
                return (X509TrustManager) trustManager;
            }
        }
        throw new IllegalStateException("No X509TrustManager for " + KEYSTORE_RESOURCE);
    }

    private static TrustManager[] trustManagers(KeyStore keyStore) throws Exception {
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(keyStore);
        return trustManagers.getTrustManagers();
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = StubOtlpServer.class.getResourceAsStream(KEYSTORE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing benchmark keystore " + KEYSTORE_RESOURCE);
            }
            keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static void serve(Socket socket) {
        try (Socket open = socket) {
            open.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(open.getInputStream(), 32 * 1024);
            OutputStream out = open.getOutputStream();
            while (readRequest(in)) {
                out.write(RESPONSE);
                out.flush();
            }
        } catch (IOException e) {
            // The client closed, or went away; either way this connection is done.
        }
    }

    /**
     * @return false at end of stream
     */
    private static boolean readRequest(InputStream in) throws IOException {
        int contentLength = 0;
        String line;
        boolean started = false;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                if (!started) {
                    continue;
                }
                skip(in, contentLength);
                return true;
            }
            started = true;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        return false;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                int length = line.length();
                if (length > 0 && line.charAt(length - 1) == '\r') {
                    line.setLength(length - 1);
                }
                return line.toString();
            }
            line.append((char) c);
        }
        return null;
    }

    private static void skip(InputStream in, int length) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return;
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static void serveHttp2(Socket socket) {
        try (Socket open = socket) {
            open.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(open.getInputStream(), 32 * 1024);
            OutputStream out = open.getOutputStream();

            byte[] preface = new byte[PREFACE.length];
            if (!readFully(in, preface, preface.length)) {
                return;
            }
            // An empty SETTINGS frame is a valid one: every parameter keeps its default.
            writeFrame(out, TYPE_SETTINGS, 0, 0, EMPTY, 0);
            out.flush();

            byte[] header = new byte[FRAME_HEADER_LENGTH];
            byte[] payload = new byte[64 * 1024];
            int received = 0;
            while (readFully(in, header, FRAME_HEADER_LENGTH)) {
                int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
                int type = header[3] & 0xFF;
                int flags = header[4] & 0xFF;
                int streamId = ((header[5] & 0x7F) << 24) | ((header[6] & 0xFF) << 16)
                        | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
                if (length > payload.length) {
                    payload = new byte[length];
                }
                if (length > 0 && !readFully(in, payload, length)) {
                    return;
                }

                switch (type) {
                    case TYPE_SETTINGS:
                        if ((flags & FLAG_ACK) == 0) {
                            writeFrame(out, TYPE_SETTINGS, FLAG_ACK, 0, EMPTY, 0);
                            out.flush();
                        }
                        break;
                    case TYPE_PING:
                        if ((flags & FLAG_ACK) == 0) {
                            writeFrame(out, TYPE_PING, FLAG_ACK, 0, payload, length);
                            out.flush();
                        }
                        break;
                    case TYPE_DATA:
                        received += length;
                        if (received >= WINDOW_REFRESH_THRESHOLD) {
                            writeWindowUpdate(out, received);
                            received = 0;
                        }
                        if ((flags & FLAG_END_STREAM) != 0) {
                            respond(out, streamId);
                        }
                        break;
                    case TYPE_HEADERS:
                        // Skipped, not parsed - see the note above. A request with no body ends here.
                        if ((flags & FLAG_END_STREAM) != 0) {
                            respond(out, streamId);
                        }
                        break;
                    case TYPE_WINDOW_UPDATE:
                    default:
                        // Nothing here sends enough to be flow-controlled, and no other frame changes
                        // what this receiver does.
                        break;
                }
            }
        } catch (IOException e) {
            // The client closed, or went away; either way this connection is done.
        }
    }

    /**
     * {@code 0x88} is HPACK static-table entry 8, exactly {@code :status: 200}, so no encoder is needed.
     */
    private static void respond(OutputStream out, int streamId) throws IOException {
        writeFrame(out, TYPE_HEADERS, FLAG_END_HEADERS | FLAG_END_STREAM, streamId, RESPONSE_HEADERS, 1);
        out.flush();
    }

    private static void writeWindowUpdate(OutputStream out, int increment) throws IOException {
        byte[] update = new byte[4];
        update[0] = (byte) (increment >>> 24);
        update[1] = (byte) (increment >>> 16);
        update[2] = (byte) (increment >>> 8);
        update[3] = (byte) increment;
        // Stream zero: the connection-level window, which is the one that runs out.
        writeFrame(out, TYPE_WINDOW_UPDATE, 0, 0, update, 4);
        out.flush();
    }

    private static void writeFrame(OutputStream out, int type, int flags, int streamId,
                                   byte[] payload, int length) throws IOException {
        byte[] header = new byte[FRAME_HEADER_LENGTH];
        header[0] = (byte) (length >>> 16);
        header[1] = (byte) (length >>> 8);
        header[2] = (byte) length;
        header[3] = (byte) type;
        header[4] = (byte) flags;
        header[5] = (byte) (streamId >>> 24);
        header[6] = (byte) (streamId >>> 16);
        header[7] = (byte) (streamId >>> 8);
        header[8] = (byte) streamId;
        out.write(header);
        if (length > 0) {
            out.write(payload, 0, length);
        }
    }

    private static boolean readFully(InputStream in, byte[] into, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = in.read(into, read, length - read);
            if (count == -1) {
                return false;
            }
            read += count;
        }
        return true;
    }
}
