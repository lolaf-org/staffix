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
package org.lolaf.staffix.stores.messages.async.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Properties for ASYNC FIX message stores (wrappers).
 *
 * <p>Bound from {@code staffix.messages-stores-async.instances.<key>.*}. Each entry's
 * {@code wraps} field is a key contributed by any other store contributor (memory, file, jdbc).
 * The async wrapper replaces the wrapped entry in the registry; the wrapped store's
 * {@code instance-id} stays in effect (the wrapper itself has no separate instance id).
 */
@Data
@ConfigurationProperties(prefix = "staffix.messages-stores-async")
public class AsyncMessagesStoreProps {

    /**
     * The configured instances, keyed by the instance id a session names to select one.
     */
    private Map<String, AsyncStoreEntryProps> instances = new LinkedHashMap<>();

}
