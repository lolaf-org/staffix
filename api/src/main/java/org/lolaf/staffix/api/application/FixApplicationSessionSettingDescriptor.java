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
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class FixApplicationSessionSettingDescriptor {

    private static final Map<String, FixApplicationSessionSettingDescriptor> SETTINGS = new ConcurrentHashMap<>();

    /**
     * The only part of equality: a descriptor is a key in each session's settings, and its description and secrecy
     * may be declared after it was first used.
     */
    @EqualsAndHashCode.Include
    private final String id;
    private volatile String description;
    /**
     * Whether the value is a credential, such as a Logon password, that administration tools must never show.
     */
    private volatile boolean secret;

    /**
     * The descriptor registered under this id, registering one without a description on first use.
     */
    public static FixApplicationSessionSettingDescriptor of(String id) {
        return SETTINGS.computeIfAbsent(id, i -> new FixApplicationSessionSettingDescriptor(id, null, false));
    }

    /**
     * As {@link #of(String)}, setting the description if the descriptor has none yet.
     */
    public static FixApplicationSessionSettingDescriptor of(String id, String description) {
        FixApplicationSessionSettingDescriptor setting = of(id);
        if (setting.description == null) {
            setting.description = description;
        }
        return setting;
    }

    /**
     * As {@link #of(String, String)}, declaring the value a credential. A setting declared secret stays secret, even
     * when it was first read from a session's configuration before the application declared it.
     */
    public static FixApplicationSessionSettingDescriptor secret(String id, String description) {
        FixApplicationSessionSettingDescriptor setting = of(id, description);
        setting.secret = true;
        return setting;
    }
}
