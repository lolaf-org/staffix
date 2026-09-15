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
package org.lolaf.staffix.stores.sessions.memory.spring;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.util.ArrayList;
import java.util.List;

/**
 * One configured instance of the in-memory session settings store: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class MemoryStoreInstanceProps {
    /**
     * The sessions this store serves, keyed by a name of your choosing.
     */
    private List<FixSessionSettingsProps> sessions = new ArrayList<>();
    /**
     * Default values applied to every session in this instance — both the ones declared under {@code sessions} and any
     * added at runtime. Each non-null field here is set on a session unless it was explicitly set by the user (or by a
     * {@code FixSessionSettingsPostProcessor} in the spring stack).
     */
    @NestedConfigurationProperty
    private FixSessionSettingsProps defaults;
}
