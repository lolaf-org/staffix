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
package org.lolaf.staffix.api.http;

/**
 * The HTTP version an {@link HttpSender} speaks to its collector.
 *
 * <p>A constructor argument on each sender rather than an {@link HttpSenderSettings} field, because each
 * client asks for it differently. Every sender defaults to {@link #HTTP_1_1}, which none of the
 * underlying clients does on its own: HTTP/2 costs 17-40% more latency and 1.17x-1.51x the allocation
 * for an OTLP export, which has nothing to multiplex.
 */
public enum HttpVersion {

    /**
     * HTTP/1.1, pinned: the ALPN offer is withdrawn, so a collector that speaks HTTP/2 is not taken up.
     */
    HTTP_1_1,

    /**
     * HTTP/2: an ALPN preference with HTTP/1.1 behind it over TLS, prior knowledge on cleartext. On
     * cleartext there is no {@code h2c} upgrade and no fallback, so an HTTP/1.1 collector fails rather
     * than degrading.
     */
    HTTP_2
}
