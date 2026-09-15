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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.ToString;
import lombok.Value;

/**
 * What an engine tells a counterparty about itself: a product name, a {@link SemVer} and a vendor.
 *
 * <p>Sent in the Logon's optional identification fields, which some venues log and a few require. The overload
 * taking a {@link FixVersion} exists because an application commonly versions itself by the FIX version it
 * speaks rather than independently.
 */
@Value
@AllArgsConstructor
@Builder
@ToString(includeFieldNames = false)
public class FixApiVersion {

    String name;
    SemVer version;
    String vendor;

    public static FixApiVersion of(String name, FixVersion fixVersion, String apiVendor) {
        return FixApiVersion.builder()
                .name(name)
                .vendor(apiVendor)
                .version(SemVer.builder().major(fixVersion.getMajor()).minor(fixVersion.getMinor()).patch(0).build()).build();
    }

    public static FixApiVersion of(String name, SemVer version, String apiVendor) {
        return FixApiVersion.builder()
                .name(name)
                .vendor(apiVendor)
                .version(version).build();
    }

    public static FixApiVersion of(String name, SemVer version) {
        return of(name, version, null);
    }

    public static FixApiVersion of(FixVersion fixVersion) {
        return of("default-fix-api", fixVersion, null);
    }
}