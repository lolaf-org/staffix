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
package org.lolaf.staffix.jdbc.ddl.entity.sessions;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;

/**
 * The {@code fix_sessions_settings} row: one session's settings, owned by one engine.
 */
@Entity
@Table(name = "fix_sessions_settings")
@Getter
@Setter
public class FixSessionSettingsRow {

    @EmbeddedId
    private FixSessionSettingsRowId id;

    @Column(name = "session_type", nullable = false, length = 16)
    private String sessionType;

    /**
     * The settings document as JSON. {@code LONG32VARCHAR} gives {@code text} on PostgreSQL where {@code @Lob}
     * would give an {@code oid}, and {@code clob} on Oracle.
     */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "settings", nullable = false)
    private String settings;

    /**
     * Incremented by every write and checked by it, so two writers cannot overwrite each other unseen.
     */
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class FixSessionSettingsRowId implements Serializable {

        @Column(name = "engine_id", nullable = false, length = 255)
        private String engineId;

        @Column(name = "qualified_name", nullable = false, length = 255)
        private String qualifiedName;
    }
}
