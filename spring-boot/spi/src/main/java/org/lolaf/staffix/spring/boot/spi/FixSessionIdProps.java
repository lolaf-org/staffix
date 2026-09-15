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
package org.lolaf.staffix.spring.boot.spi;

import lombok.Data;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.version.FixApplVerID;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

/**
 * A session's identity as properties - the CompIDs and FIX version - so a session can be declared entirely in
 * configuration.
 */
@Data
@ConfigurationPropertiesSource
public class FixSessionIdProps {

    /**
     * Free-form name for this session, used in logs and metrics.
     */
    private String id;

    /**
     * Fix version, must match a {@link org.lolaf.staffix.api.version.FixRegularVersion} value
     * (e.g. VERSION_42, VERSION_44, VERSION_50_SP2) or "FIXT_11" for FIXT 1.1 sessions.
     */
    private String fixVersion;

    /**
     * Required when {@code fixVersion} is FIXT_11. Must match a {@link org.lolaf.staffix.api.version.FixRegularVersion}
     * to derive the FIX appl version id.
     */
    private String defaultApplVerId;

    /**
     * Groups sessions that share configuration.
     */
    private String group = FixSessionId.DEFAULT_GROUP;

    /**
     * SenderCompID(49): who we are to the counterparty.
     */
    private String senderCompId;
    /**
     * SenderSubID(50), if the counterparty uses it.
     */
    private String senderSubId;
    /**
     * SenderLocationID(142), if the counterparty uses it.
     */
    private String senderLocationId;

    /**
     * TargetCompID(56): who the counterparty is.
     */
    private String targetCompId;
    /**
     * TargetSubID(57), if the counterparty uses it.
     */
    private String targetSubId;
    /**
     * TargetLocationID(143), if the counterparty uses it.
     */
    private String targetLocationId;

    private static FixRegularVersion parseRegularVersion(String version) {
        try {
            return FixRegularVersion.valueOf(version);
        } catch (IllegalArgumentException ignored) {
            return FixRegularVersion.fromString(version)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown FIX version '" + version
                            + "', expected one of FixRegularVersion enum names (e.g. VERSION_44) or FIX.x.y notation"));
        }
    }

    public FixSessionId toFixSessionId() {
        FixSessionId.FixSessionIdBuilder idBuilder = FixSessionId.FixSessionIdBuilder.builder()
                .id(id)
                .group(group == null ? FixSessionId.DEFAULT_GROUP : group)
                .senderCompID(senderCompId)
                .senderSubID(senderSubId)
                .senderLocationID(senderLocationId)
                .targetCompID(targetCompId)
                .targetSubID(targetSubId)
                .targetLocationID(targetLocationId)
                .build();

        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required for session " + senderCompId + "->" + targetCompId);
        }
        if (fixVersion == null || fixVersion.isBlank()) {
            throw new IllegalArgumentException("fix-version is required for session " + senderCompId + "->" + targetCompId);
        }
        if (FixtVersion.FIXT_11.name().equalsIgnoreCase(fixVersion) || fixVersion.equals(FixtVersion.FIXT_11.toString())) {
            if (defaultApplVerId == null || defaultApplVerId.isBlank()) {
                throw new IllegalArgumentException("default-appl-ver-id is required for FIXT_11 sessions");
            }
            FixRegularVersion regular = parseRegularVersion(defaultApplVerId);
            return FixSessionId.ofFIXT11(FixApplVerID.forFixVersion(regular), idBuilder);
        }
        return FixSessionId.of(parseRegularVersion(fixVersion), idBuilder);
    }
}