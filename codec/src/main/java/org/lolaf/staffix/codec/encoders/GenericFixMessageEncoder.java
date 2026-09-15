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
package org.lolaf.staffix.codec.encoders;

import org.lolaf.staffix.api.msg.MessageType;

/**
 * An encoder for a message type decided at runtime, with no typed setters: fields go in through
 * {@link org.lolaf.staffix.api.codec.FixFieldsEncoder#addField}, already serialized.
 * <p>
 * For the paths that carry a message whose fields are bytes rather than values - a retransmission reading messages
 * back out of the store, an administrative send parsing one out of a string. Everything a message needs beyond its
 * fields is still computed by {@link FixMessageEncoderImpl}: BeginString(8), BodyLength(9), MsgSeqNum(34),
 * SendingTime(52) and CheckSum(10) are all applied as it encodes, which is the point of going through an encoder at
 * all rather than putting the bytes on the wire as they were found.
 */
public class GenericFixMessageEncoder extends FixMessageEncoderImpl<GenericFixMessageEncoder> {

    public GenericFixMessageEncoder(MessageType messageType) {
        super(null, messageType, null, null, null);
    }
}
