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
package org.lolaf.staffix.ha.replication.spi;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What an application marks a record with, as it appends it or hands it to a snapshot. One per record: a reset
 * carries no payload worth sending in clear. The record's other flag bits are the library's, for the fragments of a
 * payload longer than a frame.
 */
@Getter
@RequiredArgsConstructor
public enum RecordFlag {

    /**
     * The record's payload is encrypted on an encrypted connection, as every frame there.
     */
    NONE((byte) 0),
    /**
     * The record's payload goes unencrypted on an encrypted connection; the frame is still authenticated, this bit
     * included. Meaningless on a clear connection, where nothing is encrypted.
     */
    PLAINTEXT((byte) 0x04),
    /**
     * The stream starts over with this record: a snapshot of the stream in progress ends before it. Ignored on a
     * record handed to a snapshot.
     */
    RESET((byte) 0x08);

    /**
     * The flag's bit in a record's flags on the wire.
     */
    private final byte bits;
}
