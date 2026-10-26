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
package org.lolaf.staffix.ha.it;

import lombok.RequiredArgsConstructor;
import org.lolaf.primos.transport.DatagramHandler;
import org.lolaf.primos.transport.Transport;

import java.nio.ByteBuffer;

/**
 * A primos node's transport the test can cut, both ways: a node isolated from its peers and its witness, as by a
 * partition, while its process runs on.
 */
@RequiredArgsConstructor
final class CuttableTransport implements Transport {

    private static final DatagramHandler DROPPED = datagram -> {
    };

    private final Transport transport;
    private volatile boolean cut;

    void cut() {
        cut = true;
    }

    @Override
    public void send(int peerId, ByteBuffer datagram) {
        if (!cut) {
            transport.send(peerId, datagram);
        }
    }

    @Override
    public int poll(long timeoutNanos, DatagramHandler handler) {
        return transport.poll(timeoutNanos, cut ? DROPPED : handler);
    }

    @Override
    public long sendsDropped() {
        return transport.sendsDropped();
    }

    @Override
    public void wakeup() {
        transport.wakeup();
    }

    @Override
    public void confirmSender(int senderId) {
        transport.confirmSender(senderId);
    }

    @Override
    public void forgetSender(int senderId) {
        transport.forgetSender(senderId);
    }

    @Override
    public void close() {
        transport.close();
    }
}
