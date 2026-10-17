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

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.http.Header;
import org.lolaf.staffix.api.http.HttpSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpMicrometerMonitoringManagerTest {

    @Test
    void closesTheRegistryItBuiltWithALastPushBeforeItsSenderCloses() {
        List<String> senderCalls = new CopyOnWriteArrayList<>();
        AtomicReference<MeterRegistry> registry = new AtomicReference<>();
        OtlpMicrometerMonitoringManager manager = new OtlpMicrometerMonitoringManager(
                OtlpMicrometerMonitoringManagerSettings.builder()
                        .otlpEndpointUrl("http://localhost:4318")
                        .httpSenderFactory(senderSettings -> new HttpSender() {
                            @Override
                            public int send(byte[] data, int offset, int length, Header[] extraHeaders) {
                                senderCalls.add("send");
                                return 200;
                            }

                            @Override
                            public void close() {
                                senderCalls.add("close");
                            }
                        })
                        .startedMeterRegistryConsumer(registry::set)
                        .build());
        manager.start();
        registry.get().counter("orders").increment();

        manager.stop(Deadline.unlimited());

        assertThat(registry.get().isClosed()).isTrue();
        assertThat(senderCalls).containsExactly("send", "close");
    }
}
