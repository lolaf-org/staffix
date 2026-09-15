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
package org.lolaf.staffix.stores.loggers.otlp.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Properties for OTLP FIX message loggers.
 *
 * <p>Bound from {@code staffix.messages-loggers-otlp.instances.<key>.*}.
 */
@Data
@ConfigurationProperties(prefix = "staffix.messages-loggers-otlp")
public class OtlpMessagesLoggerProps {

    /**
     * The configured instances, keyed by the instance id a session names to select one.
     */
    private Map<String, OtlpLoggerEntryProps> instances = new LinkedHashMap<>();

}
