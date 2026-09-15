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
package org.lolaf.staffix.plugins.throttling.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Properties for THROTTLING FIX sessions plugins (wrappers).
 *
 * <p>Bound from {@code staffix.throttling-plugin.instances.<key>.*}. Each entry's {@code wraps} field is a
 * key contributed by any other sessions-plugin contributor (micrometer, tracing, ...); the throttling
 * wrapper rate-limits that plugin's per-message callbacks so it is not overwhelmed at peak throughput.
 */
@Data
@ConfigurationProperties(prefix = "staffix.throttling-plugin")
public class ThrottlingPluginProps {

    /**
     * The configured instances, keyed by the instance id a session names to select one.
     */
    private Map<String, ThrottlingPluginEntryProps> instances = new LinkedHashMap<>();

}
