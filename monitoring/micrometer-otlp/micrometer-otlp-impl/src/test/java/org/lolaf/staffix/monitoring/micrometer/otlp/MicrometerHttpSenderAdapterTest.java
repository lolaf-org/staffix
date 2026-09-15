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
package org.lolaf.staffix.monitoring.micrometer.otlp;

import io.micrometer.core.ipc.http.HttpSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.api.http.HttpResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MicrometerHttpSenderAdapterTest {

    private static final String ENDPOINT = "http://127.0.0.1:4318/v1/metrics";
    private static final String PROTOBUF = "application/x-protobuf";
    private static final byte[] PAYLOAD = "a protobuf encoded batch of metrics".getBytes(StandardCharsets.UTF_8);

    private RecordingSender delegate;
    private MicrometerHttpSenderAdapter adapter;

    @BeforeEach
    void setUp() {
        delegate = new RecordingSender();
        adapter = new MicrometerHttpSenderAdapter(delegate, ENDPOINT);
    }

    @Test
    void forwardsTheEntityAndTheHeadersToTheDelegate() throws Throwable {
        HttpSender.Response response = adapter.post(ENDPOINT)
                .withHeader("User-Agent", "Micrometer-OTLP-Exporter-Java")
                .withContent(PROTOBUF, PAYLOAD)
                .send();

        assertThat(response.isSuccessful()).isTrue();
        assertThat(delegate.data).isEqualTo(PAYLOAD);
        assertThat(delegate.offset).isZero();
        assertThat(delegate.length).isEqualTo(PAYLOAD.length);
        assertThat(delegate.headers)
                .extracting(Header::getName, Header::getValue)
                .contains(org.assertj.core.groups.Tuple.tuple("User-Agent", "Micrometer-OTLP-Exporter-Java"),
                        org.assertj.core.groups.Tuple.tuple("Content-Type", PROTOBUF));
    }

    @Test
    void handsBackTheSameResponseObjectWhileTheStatusRepeats() throws Throwable {
        HttpSender.Response first = publish();
        HttpSender.Response second = publish();

        assertThat(second)
                .as("an immutable response carrying no body need not be rebuilt per publish")
                .isSameAs(first);
    }

    @Test
    void buildsAFreshResponseWhenTheStatusChanges() throws Throwable {
        delegate.status = 200;
        HttpSender.Response ok = publish();
        delegate.status = 202;
        HttpSender.Response accepted = publish();

        assertThat(accepted).isNotSameAs(ok);
        assertThat(accepted.code()).isEqualTo(202);
        assertThat(ok.code()).isEqualTo(200);
    }

    @Test
    void reusesTheHeaderArrayWhileTheHeadersRepeat() throws Throwable {
        publish();
        Header[] first = delegate.headers;
        publish();

        assertThat(delegate.headers)
                .as("micrometer builds a fresh map per request, but its contents do not change")
                .isSameAs(first);
    }

    @Test
    void rebuildsTheHeaderArrayWhenAHeaderChanges() throws Throwable {
        adapter.post(ENDPOINT).withHeader("Content-Encoding", "gzip").withContent(PROTOBUF, PAYLOAD).send();
        Header[] gzipped = delegate.headers;
        adapter.post(ENDPOINT).withContent(PROTOBUF, PAYLOAD).send();

        assertThat(delegate.headers).isNotSameAs(gzipped);
        assertThat(delegate.headers).extracting(Header::getName).doesNotContain("Content-Encoding");
    }

    @Test
    void mapsAFailedStatusBackIntoAResponseCarryingTheBody() throws Throwable {
        delegate.failure = new HttpResponseException(500, "Internal Server Error", "the collector is unwell");

        HttpSender.Response response = publish();

        assertThat(response.isSuccessful()).isFalse();
        assertThat(response.code()).isEqualTo(500);
        assertThat(response.body()).isEqualTo("the collector is unwell");
    }

    @Test
    void letsATransportFailureThrough() {
        delegate.failure = new IOException("connection refused");

        assertThatThrownBy(this::publish).isInstanceOf(IOException.class).hasMessage("connection refused");
    }

    @Test
    void refusesToPublishToAnAddressItIsNotBoundTo() {
        assertThatThrownBy(() -> adapter.post("http://127.0.0.1:4318/v1/traces")
                .withContent(PROTOBUF, PAYLOAD)
                .send())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/v1/traces")
                .hasMessageContaining(ENDPOINT);
    }

    @Test
    void refusesAMethodOtherThanPost() {
        assertThatThrownBy(() -> adapter.get(ENDPOINT).send())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void closesTheDelegate() {
        adapter.close();

        assertThat(delegate.closed).isTrue();
    }

    private HttpSender.Response publish() throws Throwable {
        return adapter.post(ENDPOINT).withContent(PROTOBUF, PAYLOAD).send();
    }

    /**
     * A delegate that records what the adapter handed it, and answers however a test needs.
     */
    private static final class RecordingSender implements org.lolaf.staffix.api.http.HttpSender {

        private byte[] data;
        private int offset;
        private int length;
        private Header[] headers;
        private int status = 200;
        private IOException failure;
        private boolean closed;

        @Override
        public int send(byte[] data, int offset, int length, Header[] extraHeaders) throws IOException {
            this.data = data;
            this.offset = offset;
            this.length = length;
            this.headers = extraHeaders;
            if (failure != null) {
                throw failure;
            }
            return status;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
