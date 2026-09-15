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
package org.lolaf.staffix.stores.sessions.file.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * One configured instance of the YAML session settings store: its instance id and the settings a session naming that id gets.
 */
@Data
@ConfigurationPropertiesSource
public class FileStoreInstanceProps {
    /**
     * Path to a directory containing one YAML file per
     * {@link org.lolaf.staffix.stores.sessions.file.YamlFixSessionSettings} session. An optional {@code default.yaml}
     * in the same directory provides defaults merged into every other session file.
     */
    private String directory;

    /**
     * Session files to read from somewhere other than a local directory. Mutually exclusive with
     * {@link #directory}; settings loaded this way are never written back.
     */
    private List<URI> uris = new ArrayList<>();

    /**
     * Bean names of extra {@link org.lolaf.staffix.api.session.ConfigValueResolver}s for the
     * {@code ${...}} placeholders in a session file, asked after the system property and environment
     * resolvers and before Spring's own {@code Environment}.
     */
    private List<String> configValueResolverBeans = new ArrayList<>();
}
