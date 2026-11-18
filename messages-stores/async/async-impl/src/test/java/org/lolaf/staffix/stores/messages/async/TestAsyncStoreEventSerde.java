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
package org.lolaf.staffix.stores.messages.async;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.Wire;
import net.openhft.chronicle.wire.WireType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestAsyncStoreEventSerde {

    /**
     * A replica's message is a window of its receive buffer. Reading past the window can leave mapped memory and crash
     * the JVM, and hides from a round trip: the queue's write position only moves by the window's size.
     */
    @Test
    void readsNothingPastTheLimitOfAMessageAtANonZeroPosition() {
        byte[] content = "beforemessageafter".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer window = ByteBuffer.allocateDirect(content.length).put(content).position(6).limit(13);
        Bytes<?> bytes = Bytes.allocateElasticDirect(256);
        Wire wire = WireType.FIELDLESS_BINARY.apply(bytes);
        DocumentContext document = mock(DocumentContext.class);
        when(document.wire()).thenReturn(wire);
        AsyncStoreEventSerde serde = new AsyncStoreEventSerde(released -> new AsyncStoreEvent(null, released));

        serde.serialize(document, serde.storeMessage(1, window));

        assertThat(bytes.readByte(bytes.writePosition())).isNotEqualTo((byte) 'a');
        assertThat(bytes.readByte(bytes.writePosition() - 1)).isEqualTo((byte) 'e');
    }
}
