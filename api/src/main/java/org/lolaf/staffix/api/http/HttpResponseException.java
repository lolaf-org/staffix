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

import lombok.Getter;

import java.io.IOException;

/**
 * Thrown by {@link HttpSender#send(byte[], int, int, Header[])} when the endpoint answers with a
 * status outside the 2xx range.
 *
 * <p>The failure is reported as an exception rather than as a returned response object so that a
 * successful publish - the overwhelming majority of them - allocates nothing to describe itself. The
 * status, the reason phrase and the response body are carried here, on the branch that is the only one
 * where a body should be materialised at all.
 *
 * <p>The reason phrase is {@code null} for an implementation whose client does not expose one. That is
 * not an oversight in the implementation: {@link java.net.http.HttpResponse} has no accessor for it at
 * all, so {@code JdkHttpSender} cannot report one, while the OkHttp and Jetty clients can. Read it as a
 * diagnostic that may be absent, never as something to branch on - the status is the value with meaning.
 */
@Getter
public class HttpResponseException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String reasonPhrase;
    private final String body;

    /**
     * @param statusCode   the non-2xx status the endpoint answered with
     * @param reasonPhrase the status line's reason phrase, or {@code null} if this client cannot report
     *                     one
     * @param body         the response body, or {@code null} if the implementation did not read one
     */
    public HttpResponseException(int statusCode, String reasonPhrase, String body) {
        super("HTTP " + statusCode
                + (reasonPhrase == null || reasonPhrase.isEmpty() ? "" : " " + reasonPhrase)
                + (body == null || body.isEmpty() ? "" : ": " + body));
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.body = body;
    }

}