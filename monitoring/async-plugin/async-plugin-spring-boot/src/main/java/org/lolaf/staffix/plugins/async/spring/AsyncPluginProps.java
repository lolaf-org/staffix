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
package org.lolaf.staffix.plugins.async.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Properties for ASYNC FIX sessions plugins (wrappers).
 *
 * <p>Bound from {@code staffix.async-plugin.instances.<key>.*}. Each entry's {@code wraps} field is a
 * key contributed by any other sessions-plugin contributor (micrometer, tracing, ...); the async
 * wrapper defers that plugin's fire-and-forget callbacks onto a dedicated consumer thread pool.
 */
@Data
@ConfigurationProperties(prefix = "staffix.async-plugin")
public class AsyncPluginProps {

    /**
     * The configured instances, keyed by the instance id a session names to select one.
     */
    private Map<String, AsyncPluginEntryProps> instances = new LinkedHashMap<>();

}
