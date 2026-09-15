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
package org.lolaf.staffix.api.version;

import org.lolaf.staffix.api.serde.SerDe;
import lombok.Getter;

import java.util.Optional;

/**
 * The ApplVerID(1128) values the specification defines, and their mapping to the FIX version each names.
 *
 * <p>The mapping is needed in both directions: a Logon announces a code and the session has to find the
 * dictionary for it, while a session configured by version has to announce the right code.
 */
@Getter
public enum FixApplVerID implements ApplVerID {

    FIX42("4"),
    FIX43("5"),
    FIX44("6"),
    FIX50("7"),
    FIX50SP1("8"),
    FIX50SP2("9"),
    FIX_LATEST("10");

    private final String code;
    private final byte[] serialized;

    FixApplVerID(String code) {
        this.code = code;
        this.serialized = code.getBytes(SerDe.CHARSET);
    }

    public static Optional<FixApplVerID> forCode(String code) {
        for (FixApplVerID verId : FixApplVerID.values()) {
            if (verId.getCode().equals(code)) {
                return Optional.of(verId);
            }
        }
        return Optional.empty();
    }

    public static FixRegularVersion getFixVersionForCode(String code) {
        switch (code) {
            case "4":
                return FixRegularVersion.VERSION_42;
            case "5":
                return FixRegularVersion.VERSION_43;
            case "6":
                return FixRegularVersion.VERSION_44;
            case "7":
                return FixRegularVersion.VERSION_50;
            case "8":
                return FixRegularVersion.VERSION_50_SP1;
            case "9":
                return FixRegularVersion.VERSION_50_SP2;
            case "10":
                return FixRegularVersion.VERSION_LATEST;
            default:
                throw new IllegalStateException("Unable to map fix version for code " + code);
        }
    }

    public static FixApplVerID forFixVersion(FixRegularVersion fixRegularVersion) {
        switch (fixRegularVersion) {
            case VERSION_42:
                return FIX42;
            case VERSION_43:
                return FIX43;
            case VERSION_44:
                return FIX44;
            case VERSION_50:
                return FIX50;
            case VERSION_50_SP1:
                return FIX50SP1;
            case VERSION_50_SP2:
                return FIX50SP2;
            case VERSION_LATEST:
                return FIX_LATEST;
            default:
                throw new IllegalStateException("Unable to map fix version for " + fixRegularVersion.toString());
        }
    }
}