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
package org.lolaf.staffix.stores.messages.jdbc.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Properties for JDBC-backed FIX message stores.
 *
 * <p>Bound from {@code staffix.messages-stores-jdbc.instances.<key>.*}. The
 * {@code data-source-bean} field names a Spring bean of type {@link javax.sql.DataSource}
 * that the contributor resolves from the application context at startup.
 */
@Data
@ConfigurationProperties(prefix = "staffix.messages-stores-jdbc")
public class JdbcMessagesStoreProps {

    /**
     * The configured instances, keyed by the instance id a session names to select one.
     */
    private Map<String, JdbcStoreEntryProps> instances = new LinkedHashMap<>();

}
