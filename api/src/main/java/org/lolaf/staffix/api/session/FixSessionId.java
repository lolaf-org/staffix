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
package org.lolaf.staffix.api.session;

import org.lolaf.staffix.api.serde.SerDe;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import org.lolaf.staffix.api.codec.FixFieldsEncoder;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.version.*;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * What identifies a session on the wire: the FIX version and the Sender and Target CompIDs, with the optional
 * Sub and Location IDs where a counterparty uses them.
 *
 * <p>Instances are interned, so the id of an inbound message can be compared by reference rather than by
 * content on the message path. It implements {@link IntSupplier} for the same reason - the hash is computed
 * once, at construction, and is what routes a message to its session.
 *
 * <p>Both directions are part of the identity: the same pair of CompIDs reversed is the other side of the
 * conversation, not the same session.
 */
public class FixSessionId implements IntSupplier {

    public static final String DEFAULT_GROUP = "default";

    private static final Map<String, FixSessionId> SESSIONS_IDS = new HashMap<>();

    @Getter
    private final String id;
    @Getter
    private final int serializedLen;
    @Getter
    private final FixVersion fixVersion;
    @Getter
    private final FieldAndValuePair senderCompID;
    @Getter
    private final FieldAndValuePair senderSubID;
    @Getter
    private final FieldAndValuePair senderLocationID;
    @Getter
    private final FieldAndValuePair targetCompID;
    @Getter
    private final FieldAndValuePair targetSubID;
    @Getter
    private final FieldAndValuePair targetLocationID;
    @Getter
    private final ApplVerID defaultApplVerID;
    @Getter
    private final String group;
    private final String toString;
    private int index;
    private FixSessionId inverted;

    private FixSessionId(FixVersion fixVersion, String id, String group, ApplVerID defaultApplVerID, FieldAndValuePair senderCompID, FieldAndValuePair senderSubID,
                         FieldAndValuePair senderLocationID, FieldAndValuePair targetCompID, FieldAndValuePair targetSubID, FieldAndValuePair targetLocationID) {
        this.fixVersion = fixVersion;
        this.id = id;
        this.group = group;
        this.defaultApplVerID = defaultApplVerID;
        this.senderCompID = senderCompID;
        this.senderSubID = senderSubID;
        this.senderLocationID = senderLocationID;
        this.targetCompID = targetCompID;
        this.targetSubID = targetSubID;
        this.targetLocationID = targetLocationID;
        this.serializedLen = calculateSerializedLen();
        this.toString = createToString();
    }

    private FixSessionId(FixVersion fixVersion, ApplVerID defaultApplVerID, FixSessionIdBuilder fixSessionIdBuilder) {
        if (fixVersion == null) {
            throw new IllegalArgumentException("Missing fix version");
        }
        this.id = fixSessionIdBuilder.getId();
        this.fixVersion = fixVersion;
        this.group = fixSessionIdBuilder.getGroup();
        if (fixVersion instanceof FixtVersion && defaultApplVerID == null) {
            throw new IllegalArgumentException("FIXT sessions must provide a defaultApplVerID value");
        }
        if (fixVersion instanceof FixRegularVersion && defaultApplVerID != null) {
            throw new IllegalArgumentException("defaultApplVerID cannot be provided with a non FIXT session");
        }
        this.defaultApplVerID = defaultApplVerID;
        this.senderCompID = value(CoreFields.SENDER_COMP_ID, fixSessionIdBuilder.getSenderCompID());
        this.senderSubID = value(CoreFields.SENDER_SUB_ID, fixSessionIdBuilder.getSenderSubID());
        this.senderLocationID = value(CoreFields.SENDER_LOCATION_ID, fixSessionIdBuilder.getSenderLocationID());
        this.targetCompID = value(CoreFields.TARGET_COMP_ID, fixSessionIdBuilder.getTargetCompID());
        this.targetSubID = value(CoreFields.TARGET_SUB_ID, fixSessionIdBuilder.getTargetSubID());
        this.targetLocationID = value(CoreFields.TARGET_LOCATION_ID, fixSessionIdBuilder.getTargetLocationID());
        this.serializedLen = calculateSerializedLen();
        this.toString = createToString();
    }

    public static FixSessionId of(String id, FixVersion fixVersion, String senderCompID, String targetCompID) {
        return getFromCache(new FixSessionId(fixVersion, null, FixSessionIdBuilder.builder()
                .id(id)
                .senderCompID(senderCompID)
                .targetCompID(targetCompID)
                .build()));
    }

    public static FixSessionId of(FixVersion fixVersion, FixSessionIdBuilder fixSessionIdBuilder) {
        return getFromCache(new FixSessionId(fixVersion, null, fixSessionIdBuilder));
    }

    public static FixSessionId of(FixVersion fixVersion, ApplVerID defaultApplVerID, FixSessionIdBuilder fixSessionIdBuilder) {
        return getFromCache(new FixSessionId(fixVersion, defaultApplVerID, fixSessionIdBuilder));
    }

    public static FixSessionId ofFIXT11(String id, FixApplVerID fixVersion, String senderCompID, String targetCompID) {
        return getFromCache(new FixSessionId(FixtVersion.FIXT_11, fixVersion, FixSessionIdBuilder.builder()
                .id(id)
                .senderCompID(senderCompID)
                .targetCompID(targetCompID)
                .build()));
    }

    public static FixSessionId ofFIXT11(FixApplVerID fixVersion, FixSessionIdBuilder fixSessionIdBuilder) {
        return getFromCache(new FixSessionId(FixtVersion.FIXT_11, fixVersion, fixSessionIdBuilder));
    }

    private static synchronized FixSessionId getFromCache(FixSessionId fixSessionId) {
        FixSessionId cachedSession = SESSIONS_IDS.get(fixSessionId.toString());
        if (cachedSession == null) {
            fixSessionId.index = SESSIONS_IDS.size(); // start from 0 index
            SESSIONS_IDS.put(fixSessionId.toString(), fixSessionId);
            return fixSessionId;
        }
        return cachedSession;
    }

    private static FieldAndValuePair value(int code, String value) {
        return value == null ? null : new FieldAndValuePair(code, value);
    }

    private static boolean matches(FieldAndValuePair fieldAndValuePair, String value) {
        if (fieldAndValuePair == null) {
            return value == null;
        }
        return fieldAndValuePair.getValue().equals(value);
    }

    @Override
    public int getAsInt() {
        return index;
    }

    private String createToString() {
        String applVerID = defaultApplVerID != null ? "(" + defaultApplVerID.getCode() + ")" : "";
        return id + ":" + fixVersion.getId()
                + applVerID
                + ":"
                + senderCompID.getValue()
                + (isSet(senderSubID) ? "/" + senderSubID.getValue() : "")
                + (isSet(senderLocationID) ? "/" + senderLocationID.getValue() : "")
                + "->"
                + targetCompID.getValue()
                + (isSet(targetSubID) ? "/" + targetSubID.getValue() : "")
                + (isSet(targetLocationID) ? "/" + targetLocationID.getValue() : "");
    }

    private boolean isSet(FieldAndValuePair value) {
        return value != null;
    }

    public String forFileName(String extension) {
        return id + extension;
    }

    public FixSessionId invert() {
        if (inverted == null) {
            inverted = getFromCache(new FixSessionId(fixVersion, id, group, defaultApplVerID, targetCompID, targetSubID, targetLocationID, senderCompID, senderSubID, senderLocationID));
        }
        return inverted;
    }

    public boolean matches(FixVersion fixVersion, ApplVerID fixApplVerId, String senderCompID, String senderSubID, String senderLocationId, String targetCompID, String targetSubID, String targetLocationID) {
        if (this.defaultApplVerID != null && !defaultApplVerID.equals(fixApplVerId)) {
            return false;
        }
        return matchesIgnoringApplVerId(fixVersion, senderCompID, senderSubID, senderLocationId, targetCompID, targetSubID, targetLocationID);
    }

    /**
     * Same as {@link #matches} but ignoring the {@code DefaultApplVerID(1137)}. Used to resolve which configured session a
     * connection belongs to when the incoming {@code DefaultApplVerID} is unknown or unsupported, so that an
     * {@code INVALID_UNSUPPORTED_APPL_VER} reject can still be addressed to the right session.
     */
    public boolean matchesIgnoringApplVerId(FixVersion fixVersion, String senderCompID, String senderSubID, String senderLocationId, String targetCompID, String targetSubID, String targetLocationID) {
        if (!this.fixVersion.equals(fixVersion)) {
            return false;
        }
        return matches(this.senderCompID, senderCompID)
                && matches(this.senderSubID, senderSubID)
                && matches(this.senderLocationID, senderLocationId)
                && matches(this.targetCompID, targetCompID)
                && matches(this.targetSubID, targetSubID)
                && matches(this.targetLocationID, targetLocationID);
    }

    private int calculateSerializedLen() {
        return serializedLen(senderCompID)
                + serializedLen(senderSubID)
                + serializedLen(senderLocationID)
                + serializedLen(targetCompID)
                + serializedLen(targetSubID)
                + serializedLen(targetLocationID);
    }

    private int serializedLen(FieldAndValuePair fieldAndValuePair) {
        if (fieldAndValuePair != null) {
            return fieldAndValuePair.getField().serialized().length + fieldAndValuePair.getSerializedValue().length + 1;
        }
        return 0;
    }

    public void serialize(FixFieldsEncoder<?> encoder) {
        encoder.addBytes(senderCompID.getField(), senderCompID.getSerializedValue());
        if (senderSubID != null) {
            encoder.addBytes(senderSubID.getField(), senderSubID.getSerializedValue());
        }
        if (senderLocationID != null) {
            encoder.addBytes(senderLocationID.getField(), senderLocationID.getSerializedValue());
        }
        encoder.addBytes(targetCompID.getField(), targetCompID.getSerializedValue());
        if (targetSubID != null) {
            encoder.addBytes(targetSubID.getField(), targetSubID.getSerializedValue());
        }
        if (targetLocationID != null) {
            encoder.addBytes(targetLocationID.getField(), targetLocationID.getSerializedValue());
        }
    }

    @Override
    public String toString() {
        return toString;
    }

    @Data
    @Builder
    public static final class FixSessionIdBuilder {

        @NonNull
        String id;
        @Builder.Default
        String group = DEFAULT_GROUP;
        @NonNull
        String senderCompID;
        String senderSubID;
        String senderLocationID;
        @NonNull
        String targetCompID;
        String targetSubID;
        String targetLocationID;
    }

    @Getter
    public static class FieldAndValuePair {
        private final FixField field;
        private final String value;
        private final byte[] serializedValue;

        public FieldAndValuePair(int fieldCode, String value) {
            this.field = FixField.of(fieldCode, FieldType.STRING, FieldLocation.HEADER);
            this.value = value.intern();
            this.serializedValue = this.value.getBytes(SerDe.CHARSET);
        }
    }
}