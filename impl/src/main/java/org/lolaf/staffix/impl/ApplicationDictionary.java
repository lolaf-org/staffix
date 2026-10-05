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
package org.lolaf.staffix.impl;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixtVersion;

/**
 * The dictionary a session speaks, which is its application's: checked against the session's FIX version so a
 * mismatch is refused up front rather than failing at the first message.
 */
@UtilityClass
public class ApplicationDictionary {

    /**
     * The application's dictionary, if the session's application version is the dictionary's.
     *
     * @throws IllegalArgumentException when it is not
     */
    public static FixDictionaryId require(FixDictionaryId dictionaryId, String applicationId, FixSessionId fixSessionId) {
        if (dictionaryId == null) {
            throw new IllegalArgumentException("FIX application '" + applicationId + "' declares no dictionary");
        }
        FixRegularVersion sessionVersion = applicationVersion(fixSessionId);
        if (!dictionaryId.getTargetFixVersion().equals(sessionVersion)) {
            throw new IllegalArgumentException(String.format("FIX application '%s' speaks %s, FIX session %s is on %s",
                    applicationId, dictionaryId.getTargetFixVersion(), fixSessionId, sessionVersion));
        }
        return dictionaryId;
    }

    /**
     * A FIXT session's messages are in the version its DefaultApplVerID names; a regular one's in its own.
     */
    public static FixRegularVersion applicationVersion(FixSessionId fixSessionId) {
        if (fixSessionId.getFixVersion() instanceof FixtVersion) {
            return FixApplVerID.getFixVersionForCode(fixSessionId.getDefaultApplVerID().getCode());
        }
        if (fixSessionId.getFixVersion() instanceof FixRegularVersion) {
            return (FixRegularVersion) fixSessionId.getFixVersion();
        }
        throw new IllegalArgumentException("Unhandled fix version class " + fixSessionId.getFixVersion());
    }
}
