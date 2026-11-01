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
package org.lolaf.staffix.ha.follower;

import java.nio.ByteBuffer;

/**
 * How a session's events reach its store. A message is lent as a range of the receive buffer, which is put back as
 * it was. Seqnums are kept until {@link #writePending()}: each event sets the whole value, so only the last counts.
 */
interface StoreWrites {

    void storeLiveMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength);

    void storeSnapshotMessage(long seqNum, ByteBuffer receiveBuffer, int messageStart, int messageLength);

    void setNextIncomingSeqNum(long nextIncomingSeqNum);

    void setNextOutgoingSeqNum(long nextOutgoingSeqNum);

    void writePending();

    void discardPending();
}
