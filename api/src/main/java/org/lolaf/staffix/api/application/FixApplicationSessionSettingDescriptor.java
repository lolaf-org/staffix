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
package org.lolaf.staffix.api.application;

import lombok.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Declares a setting an application understands, so it can be given in a session's configuration and validated
 * there rather than read blindly at runtime.
 *
 * <p>Registered statically, which is what lets a YAML session file be checked against it before the engine
 * starts instead of failing on the first message.
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@ToString
@EqualsAndHashCode
public class FixApplicationSessionSettingDescriptor {

    private static final Map<String, FixApplicationSessionSettingDescriptor> SETTINGS = new ConcurrentHashMap<>();

    private final String id;
    private String description;

    public static FixApplicationSessionSettingDescriptor of(String id) {
        return SETTINGS.computeIfAbsent(id, i -> new FixApplicationSessionSettingDescriptor(id, null));
    }

    public static FixApplicationSessionSettingDescriptor of(String id, String description) {
        FixApplicationSessionSettingDescriptor setting = of(id);
        if (setting.description == null) {
            setting.description = description;
        }
        return setting;
    }
}
