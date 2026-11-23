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

import org.lolaf.staffix.benchmarks.impl.CipherParams;
import org.lolaf.staffix.ha.replication.EncryptionMode;
import org.lolaf.staffix.ha.replication.encryption.ConnectionEncryptionKeys;
import org.lolaf.staffix.ha.replication.encryption.KeyExchange;
import org.lolaf.staffix.ha.replication.wire.FrameCipher;
import org.lolaf.staffix.ha.replication.wire.FrameEncoder;
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
 * What authenticating the control frames adds to a round trip, in a hot loop: the follower's ACK sealed and the
 * leader opening it ({@code SYNC_APPLIED}), and the RECEIVED before it ({@code SYNC_RECEIVED}). {@code CLEAR} is the
 * frames' encoding alone, which every other figure includes.
 */
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 3, jvmArgsPrepend = {"-Xmx1g", "-Xms1g"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class HaControlFrameCipherBenchmark {

    @Param({"CLEAR", "AES_GCM_256", "AES_CTR_HMAC_SHA256", "CHACHA20_HMAC_SHA256"})
    CipherParams.Encryption encryption;
    @Param({"PROVIDER_JDK"})
    CipherParams.CryptoProvider provider;

    private final ByteBuffer frames;
    private FrameCipher sealer;
    private FrameCipher opener;
    private long offset;

    public HaControlFrameCipherBenchmark() {
        this.frames = ByteBuffer.allocateDirect(1024);
        this.sealer = FrameCipher.CLEAR;
        this.opener = FrameCipher.CLEAR;
        this.offset = 0;
    }

    @Setup(Level.Trial)
    public void setup() throws GeneralSecurityException {
        KeyExchange keyExchange = new KeyExchange(encryption.settings(provider.install()));
        if (keyExchange.getMode() != EncryptionMode.DISABLED) {
            ConnectionEncryptionKeys keys = keyExchange.deriveKeys(keyExchange.newNonce(), keyExchange.newNonce());
            sealer = keyExchange.newSealer(keys.getFollowerToLeader());
            opener = keyExchange.newOpener(keys.getFollowerToLeader());
        }
    }

    @Benchmark
    public int ack() {
        long applied = ++offset;
        return opener.open(frames, 0, sealer.seal(frames, 0, FrameEncoder.ack(frames, 0, 1, applied, true, 0)));
    }

    @Benchmark
    public int receivedAndAck() {
        long received = ++offset;
        int length = opener.open(frames, 0, sealer.seal(frames, 0, FrameEncoder.received(frames, 0, 1, received)));
        return length + opener.open(frames, 0, sealer.seal(frames, 0, FrameEncoder.ack(frames, 0, 1, received, true, 0)));
    }
}
