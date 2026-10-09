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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Value;
import org.lolaf.staffix.api.session.FixSessionId;

import java.nio.charset.StandardCharsets;

/**
 * A FIX session id as its parts, so the console shows them without parsing {@link FixSessionId#toString()}.
 */
@Value
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FixIdentity {
    /**
     * The BeginString, such as FIX.4.4 or FIXT.1.1.
     */
    String fixVersion;
    /**
     * The DefaultApplVerID code of a FIXT session, absent otherwise.
     */
    String defaultApplVerId;
    CompIds sender;
    CompIds target;

    public static FixIdentity of(FixSessionId fixSessionId) {
        return new FixIdentity(
                new String(fixSessionId.getFixVersion().getBeginString(), StandardCharsets.US_ASCII),
                fixSessionId.getDefaultApplVerID() != null ? fixSessionId.getDefaultApplVerID().getCode() : null,
                CompIds.of(fixSessionId.getSenderCompID(), fixSessionId.getSenderSubID(), fixSessionId.getSenderLocationID()),
                CompIds.of(fixSessionId.getTargetCompID(), fixSessionId.getTargetSubID(), fixSessionId.getTargetLocationID()));
    }
}
