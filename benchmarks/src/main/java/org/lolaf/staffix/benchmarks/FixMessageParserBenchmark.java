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

import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.codec.DecodingException;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.logging.VoidMessageLogger;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.time.UTCTime;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.codec.decoders.FixMessageParser;
import org.lolaf.staffix.codec.decoders.FixMessageParserEventsListener;
import org.lolaf.staffix.impl.session.ClockImpl;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * {@link FixMessageParser} alone, on the session's default validation settings: decoders that keep nothing, so the
 * figure is the parsing loop and not what an application does with the fields. {@code batchOfQuotes} is the case where
 * the end of message check runs most, several messages arriving in one read.
 */
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(value = 1, jvmArgsPrepend = {
        "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xmx1g", "-Xms1g"
})
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class FixMessageParserBenchmark {

    private static final int BATCH_SIZE = 16;

    @Benchmark
    public int heartbeat(ParserState state) throws DecodingException {
        return state.parse(state.heartbeat);
    }

    @Benchmark
    public int quote(ParserState state) throws DecodingException {
        return state.parse(state.quote);
    }

    @Benchmark
    @OperationsPerInvocation(BATCH_SIZE)
    public int batchOfQuotes(ParserState state) throws DecodingException {
        return state.parse(state.batchOfQuotes);
    }

    @State(Scope.Thread)
    public static class ParserState {

        ByteBuffer heartbeat;
        ByteBuffer quote;
        ByteBuffer batchOfQuotes;
        FixMessageParser parser;
        Function<MessageType, FixMessageDecoder> decoders;
        int decodedMessages;

        @Setup(Level.Trial)
        public void setup() throws DecodingException {
            FixDictionaryId dictionaryId = FixDictionaryId.of("benchmarks", FixRegularVersion.VERSION_44);
            MessageTypeRegistry messageTypeRegistry = MessageTypeRegistry.Registry.getInstance(dictionaryId);
            FixSessionId sessionId = FixSessionId.of("benchmarks", FixRegularVersion.VERSION_44, "SENDER", "TARGET");
            parser = new FixMessageParser(sessionId, messageTypeRegistry, FieldsRegistry.Registry.getInstance(dictionaryId),
                    VoidMessageLogger.getInstance(), FixSessionSettings.ValidationSettings.builder().build(),
                    ClockImpl.get(), new CountingListener(this));
            Map<MessageType, FixMessageDecoder> decodersByType = new HashMap<>();
            decoders = messageType -> decodersByType.computeIfAbsent(messageType, NoOpDecoder::new);

            heartbeat = ByteBuffer.wrap(fixMessage("35=0", "34=1", "49=SENDER", "52=20260927-10:15:30.123456", "56=TARGET"));
            quote = ByteBuffer.wrap(quote(1));
            ByteBuffer batch = ByteBuffer.allocate(BATCH_SIZE * quote.capacity() * 2);
            for (int seqNum = 1; seqNum <= BATCH_SIZE; seqNum++) {
                batch.put(quote(seqNum));
            }
            batch.flip();
            batchOfQuotes = batch;

            checkEveryMessageDecodes(heartbeat, 1);
            checkEveryMessageDecodes(quote, 1);
            checkEveryMessageDecodes(batchOfQuotes, BATCH_SIZE);
        }

        int parse(ByteBuffer messages) throws DecodingException {
            messages.position(0);
            parser.parseMessages(messages, decoders);
            return decodedMessages;
        }

        private void checkEveryMessageDecodes(ByteBuffer messages, int expected) throws DecodingException {
            decodedMessages = 0;
            parse(messages);
            if (decodedMessages != expected) {
                throw new IllegalStateException("Decoded " + decodedMessages + " messages instead of " + expected);
            }
        }

        private static byte[] quote(int seqNum) {
            return fixMessage("35=S", "34=" + seqNum, "49=SENDER", "52=20260927-10:15:30.123456", "56=TARGET",
                    "131=testQuoteRequest", "117=testQuoteId", "55=FOO/BAR", "54=1", "132=1234567.123456789", "134=1234567");
        }

        private static byte[] fixMessage(String... bodyFields) {
            StringBuilder body = new StringBuilder(128);
            for (String field : bodyFields) {
                body.append(field).append('\u0001');
            }
            String head = "8=FIX.4.4\u00019=" + body.length() + '\u0001' + body;
            int checksum = 0;
            for (int i = 0; i < head.length(); i++) {
                checksum += head.charAt(i);
            }
            return (head + "10=" + String.format("%03d", checksum & 0xFF) + '\u0001').getBytes(StandardCharsets.US_ASCII);
        }
    }

    @RequiredArgsConstructor
    private static final class CountingListener implements FixMessageParserEventsListener {

        private final ParserState state;

        @Override
        public void onMessageDecoded(MessageType messageType, FixMessageDecoder fixMessageDecoder, long incomingSeqNum,
                                     boolean possibleDuplicate, boolean possResend, long localReceiveTimeInNanos,
                                     UTCTime localReceiveTime) {
            state.decodedMessages++;
        }
    }

    @Value
    private static class NoOpDecoder implements FixMessageDecoder {
        MessageType messageType;
    }
}
