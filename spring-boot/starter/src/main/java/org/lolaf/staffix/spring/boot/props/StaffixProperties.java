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
package org.lolaf.staffix.spring.boot.props;

import org.springframework.boot.context.properties.NestedConfigurationProperty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The root of the {@code staffix.*} configuration tree - the engine, its initiators and its acceptors.
 */
@Data
@ConfigurationProperties(prefix = "staffix")
public class StaffixProperties {

    /**
     * Whether the starter builds an engine at all. Set false to keep staffix on the classpath without it running.
     */
    private boolean enabled = true;

    /**
     * The engine itself - the stores, loggers, plugins and factories its sessions may name.
     */
    @NestedConfigurationProperty
    private EngineProps engine = new EngineProps();

    /**
     * The acceptors to run, keyed by a name of your choosing that appears in logs and metrics.
     */
    private Map<String, AcceptorProps> acceptors = new LinkedHashMap<>();

    /**
     * The initiators to run, keyed by a name of your choosing that appears in logs and metrics.
     */
    private Map<String, InitiatorProps> initiators = new LinkedHashMap<>();
}
