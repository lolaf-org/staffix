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
 * The FIX versions that appear in BeginString(8) directly, FIX.4.0 through FIX.5.0 SP2.
 *
 * <p>Distinct from {@link FixtVersion}: a FIX.4.x session puts its application version in BeginString, where a
 * FIXT.1.1 session puts the transport version there and names the application version separately.
 */
public enum FixRegularVersion implements FixVersion {
    VERSION_42(4, 2),
    VERSION_43(4, 3),
    VERSION_44(4, 4),
    VERSION_50(5, 0),
    VERSION_50_SP1(5, 0, "SP1"),
    VERSION_50_SP2(5, 0, "SP2"),
    VERSION_LATEST(5, 0, "latest");

    @Getter
    private final byte[] beginString;
    @Getter
    private final int minor;
    @Getter
    private final int major;
    @Getter
    private final String servicePack;
    private final String toString;

    FixRegularVersion(int major, int minor) {
        this(major, minor, null);
    }

    FixRegularVersion(int major, int minor, String servicePack) {
        this.major = major;
        this.minor = minor;
        this.toString = "FIX." + major + "." + minor + (servicePack == null ? "" : "-" + servicePack);
        this.beginString = ("FIX." + major + "." + minor).getBytes(SerDe.CHARSET);
        this.servicePack = servicePack;
    }

    public static Optional<FixRegularVersion> fromString(String fixVersion) {
        switch (fixVersion) {
            case "FIX.4.2":
                return Optional.of(VERSION_42);
            case "FIX.4.3":
                return Optional.of(VERSION_43);
            case "FIX.4.4":
                return Optional.of(VERSION_44);
            case "FIX.5.0":
                return Optional.of(VERSION_50);
            case "FIX.5.0-SP1":
                return Optional.of(VERSION_50_SP1);
            case "FIX.5.0-SP2":
                return Optional.of(VERSION_50_SP2);
            case "FIX.Latest":
            case "FIX.5.0-latest":
                return Optional.of(VERSION_LATEST);
            default:
                return Optional.empty();
        }
    }

    public static FixRegularVersion fromVersion(int major, int minor, String servicePack) {
        if (major == 4) {
            if (minor == 2) {
                return VERSION_42;
            }
            if (minor == 3) {
                return VERSION_43;
            }
            if (minor == 4) {
                return VERSION_44;
            }
        }

        if (major == 5 && minor == 0) {
            if (servicePack == null) {
                return VERSION_50;
            }
            switch (servicePack) {
                case "SP1":
                    return VERSION_50_SP1;
                case "SP2":
                    return VERSION_50_SP2;
                case "Latest":
                    return VERSION_LATEST;
            }
        }
        throw new IllegalArgumentException("Unknown fix version for " + major + " " + minor);
    }

    @Override
    public String getId() {
        return name();
    }

    @Override
    public String toString() {
        return toString;
    }
}