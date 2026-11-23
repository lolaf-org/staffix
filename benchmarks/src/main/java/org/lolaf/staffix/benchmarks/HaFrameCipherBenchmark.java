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
package org.lolaf.staffix.benchmarks;

import org.lolaf.staffix.api.stores.FixMessagesStore.StoreEventType;
import org.lolaf.staffix.benchmarks.impl.HaParams;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ConnectionEncryptionKeys;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
import org.lolaf.staffix.ha.replication.wire.WireFormat;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

/**
 * One store event frame sealed as the leader's sender does, and sealed then opened as the follower does, on a direct
 * buffer like the replication's: per cipher, JCE provider and message size. {@code CLEAR} is the frame's encoding alone,
 * which every other figure includes. Not every provider has every cipher: run each provider with its own {@code -p}.
 */
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsPrepend = {"-Xmx1g", "-Xms1g"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class HaFrameCipherBenchmark {

    @Param({"CLEAR", "AES_GCM_128", "AES_GCM_256", "CHACHA20_POLY1305", "AES_CTR_HMAC_SHA256", "CHACHA20_HMAC_SHA256"})
    HaParams.Encryption encryption;
    @Param({"PROVIDER_JDK"})
    HaParams.CryptoProvider provider;
    @Param({"MESSAGE_200_B", "MESSAGE_1_KIB", "MESSAGE_16_KIB"})
    HaParams.MessageSize messageSize;

    private final ByteBuffer frames;
    private ByteBuffer message;
    private FrameCipher sealer;
    private FrameCipher opener;
    private long sequenceNumber;

    public HaFrameCipherBenchmark() {
        this.frames = ByteBuffer.allocateDirect(64 * 1024);
        this.sealer = FrameCipher.CLEAR;
        this.opener = FrameCipher.CLEAR;
        this.sequenceNumber = 0;
    }

    @Setup(Level.Trial)
    public void setup() throws GeneralSecurityException {
        message = ByteBuffer.allocateDirect(messageSize.getBytes());
        KeyExchange keyExchange = new KeyExchange(encryption.settings(provider.install()));
        if (keyExchange.getMode() != EncryptionMode.DISABLED) {
            ConnectionEncryptionKeys keys = keyExchange.deriveKeys(keyExchange.newNonce(), keyExchange.newNonce());
            sealer = keyExchange.newSealer(keys.getLeaderToFollower());
            opener = keyExchange.newOpener(keys.getLeaderToFollower());
        }
    }

    @Benchmark
    public int seal() {
        return sealer.seal(frames, 0, storeEvent());
    }

    @Benchmark
    public int sealAndOpen() {
        return opener.open(frames, 0, sealer.seal(frames, 0, storeEvent()));
    }

    private int storeEvent() {
        long seq = ++sequenceNumber;
        int body = FrameEncoder.recordBody(frames, WireFormat.RECORD_BODY_OFFSET, 0,
                StoreEventType.STORE_MESSAGE.getCode(), (byte) 0, seq, message, 0, message.capacity());
        return FrameEncoder.recordHeader(frames, 0, 1, seq, body);
    }
}
