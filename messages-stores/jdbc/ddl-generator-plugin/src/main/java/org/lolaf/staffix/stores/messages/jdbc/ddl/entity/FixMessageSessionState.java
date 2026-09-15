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
package org.lolaf.staffix.stores.messages.jdbc.ddl.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA entity representing FIX session state (sequence numbers) in the database.
 * This entity is used by the DDL generator plugin to create the database schema.
 */
@Entity
@Table(name = "fix_messages_session_state",
        indexes = {
                @Index(name = "idx_session", columnList = "session_id")
        }
)
/**
 * The {@code fix_messages_session_state} row: a session's sequence numbers.
 */
@Getter
@Setter
public class FixMessageSessionState {

    @Id
    @Column(name = "session_id", nullable = false, length = 255)
    private String sessionId;

    @Column(name = "incoming_seq_num", nullable = false)
    private long incomingSeqNum = 1L;

    @Column(name = "outgoing_seq_num", nullable = false)
    private long outgoingSeqNum = 1L;
}
