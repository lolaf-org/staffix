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
package org.lolaf.staffix.admin.http.dto;

import lombok.Builder;
import lombok.Value;
import org.lolaf.staffix.api.session.FixSessionDesiredState;
import org.lolaf.staffix.api.session.FixSessionStatus;

/**
 * The live state of a session box, named as in {@link EngineSessions}.
 */
@Value
@Builder
public class SessionStatus {
    String group;
    /**
     * The main config's name, unique within the group.
     */
    String name;
    /**
     * The config running now; changes when an initiator switches to a backup.
     */
    String selectedConfig;
    FixSessionDesiredState desiredState;
    /**
     * Where the session stands for an operator: tells an incident from a planned pause or an operator's logout.
     */
    FixSessionStatus status;
    long incomingSeqNum;
    long outgoingSeqNum;
}
