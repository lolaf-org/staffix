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

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.session.ConfigValuePlaceholder;
import org.lolaf.staffix.api.session.ConfigValueResolver;
import org.springframework.core.env.Environment;

import java.util.Optional;

/**
 * Resolves a session file's placeholders against Spring's {@link Environment}, so a YAML session file
 * reads from the same sources as {@code application.properties} - profiles, config server, mounted
 * secrets and the rest.
 *
 * <p>Session files need this because they are read by the store's own parser and never pass through
 * Spring; sessions declared in Spring properties are resolved by Spring itself and need nothing.
 *
 * <p>Unnamed, so it is offered every placeholder, which is why it goes <strong>last</strong> in the
 * chain: the environment already holds system properties and environment variables, so ahead of the
 * built-ins it would answer {@code ${sysprop:x}} before the resolver that owns that prefix.
 */
@RequiredArgsConstructor
public class EnvironmentConfigValueResolver implements ConfigValueResolver {

    private final Environment environment;

    @Override
    public Optional<String> resolve(ConfigValuePlaceholder placeholder) {
        return Optional.ofNullable(environment.getProperty(placeholder.getKey()));
    }
}
