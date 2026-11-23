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
package org.lolaf.staffix.ha;

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.lolaf.staffix.ha.replication.encryption.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ReplicationEncryptionSettings;
import org.lolaf.staffix.ha.replication.encryption.TestingKeyExchanges;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine says at start when its replication cipher allocates on every frame.
 */
class TestCipherAllocationLog {

    private final TestLogger engineLog;

    TestCipherAllocationLog() {
        this.engineLog = TestLoggerFactory.getTestLogger(ReplicationFixEngineHa.class);
    }

    @BeforeEach
    void clearThisThreadsLogs() {
        TestLoggerFactory.clear();
    }

    private static void engine(ReplicationEncryptionSettings encryption) {
        new ReplicationFixEngineHa(ReplicationHaSettings.builder().nodeId("a")
                .peer("a", new InetSocketAddress("localhost", 0)).encryption(encryption).build())
                .bind(new RecordingEngine());
    }

    private static ReplicationEncryptionSettings encryption(String cipher) {
        return ReplicationEncryptionSettings.builder().mode(EncryptionMode.REQUIRED)
                .secret(TestingKeyExchanges.SECRET.getBytes(StandardCharsets.UTF_8)).cipher(cipher).build();
    }

    private List<String> allocationNotices() {
        return engineLog.getLoggingEvents().stream()
                .filter(event -> event.getLevel() == Level.INFO && event.getFormattedMessage().contains("allocates on every frame"))
                .map(LoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @ParameterizedTest
    @CsvSource({"AES/GCM/NoPadding, AES/GCM/NoPadding 256", "ChaCha20-Poly1305, ChaCha20-Poly1305 256"})
    void anAeadIsNamedAsAllocatingBesideTheAlternative(String cipher, String described) {
        engine(encryption(cipher));

        assertThat(allocationNotices()).singleElement().asString()
                .startsWith("HA replication cipher " + described + " allocates on every frame")
                .contains("AES/CTR/NoPadding with HmacSHA256 allocates nothing");
    }

    @ParameterizedTest
    @CsvSource({"AES/CTR/NoPadding", "ChaCha20"})
    void aStreamCipherSaysNothing(String cipher) {
        engine(encryption(cipher));

        assertThat(allocationNotices()).isEmpty();
    }

    @Test
    void noEncryptionSaysNothing() {
        engine(ReplicationEncryptionSettings.builder().cipher("AES/GCM/NoPadding").build());

        assertThat(allocationNotices()).isEmpty();
    }
}
