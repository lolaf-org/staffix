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

import lombok.Builder;
import lombok.Value;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version, used for the engine's own version and for an application's.
 *
 * <p>Nothing to do with FIX versions - those are {@link FixVersion}. This is what
 * {@link FixApiVersion} reports to a counterparty that asks what it is talking to.
 */
@Value
@Builder
public class SemVer {

    public static final SemVer SEM_VER_V1 = new SemVer(1, 0, 0, null);

    private static final Pattern SEMVER_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[+\\-]([\\w.]+))?$");

    int major;
    int minor;
    int patch;
    String metadata;

    public static SemVer of(int major, int minor, int patch) {
        return SemVer.builder().major(major).minor(minor).patch(patch).build();
    }

    public static SemVer from(String version) {
        Matcher matcher = SEMVER_PATTERN.matcher(version.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + version);
        }
        return SemVer.builder()
                .major(Integer.parseInt(matcher.group(1)))
                .minor(Integer.parseInt(matcher.group(2)))
                .patch(Integer.parseInt(matcher.group(3)))
                .metadata(matcher.group(4))
                .build();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(major).append(".").append(minor).append(".").append(patch);
        if (metadata != null) {
            sb.append("-").append(metadata);
        }
        return sb.toString();
    }
}