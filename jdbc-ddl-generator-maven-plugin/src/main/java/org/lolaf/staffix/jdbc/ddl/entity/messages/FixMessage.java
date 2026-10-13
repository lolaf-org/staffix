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
package org.lolaf.staffix.jdbc.ddl.entity.messages;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * JPA entity representing a FIX protocol message in the database.
 * This entity is used by the DDL generator plugin to create the database schema.
 */
@Entity
@Table(name = "fix_messages",
        indexes = {
                @Index(name = "idx_session_seq", columnList = "session_id,sequence_number")
        }
)
@Getter
@Setter
public class FixMessage {

    @EmbeddedId
    private FixMessageId id;

    @Column(name = "message_data", nullable = false, length = 1024 * 32)
    private byte[] messageData;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class FixMessageId implements Serializable {

        @Column(name = "session_id", nullable = false, length = 255)
        private String sessionId;

        @Column(name = "sequence_number", nullable = false)
        private long sequenceNumber;
    }
}
