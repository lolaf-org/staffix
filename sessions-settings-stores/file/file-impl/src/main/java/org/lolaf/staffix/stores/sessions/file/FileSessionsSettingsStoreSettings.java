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
package org.lolaf.staffix.stores.sessions.file;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import org.lolaf.staffix.api.session.ConfigValueResolver;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;

import java.io.File;
import java.net.URI;
import java.util.List;

/**
 * The directory of session files, and whether changes to them are picked up while the engine runs.
 */
@Getter
@Builder(toBuilder = true)
public class FileSessionsSettingsStoreSettings implements FixSessionsSettingsStoreSettings {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;
    /**
     * Resolvers for the {@code ${...}} placeholders in a session file, asked in the order given. Empty
     * falls back to system properties then environment variables.
     */
    @Singular
    private final List<ConfigValueResolver> configValueResolvers;
    /**
     * Session files to read from somewhere other than a local directory - a network location, or a
     * classpath resource the caller has resolved with {@code getResource(...).toURI()}. Each URI names one
     * file; one may end in {@code default.yaml}, giving the defaults merged into the others.
     *
     * <p>Mutually exclusive with {@link #fixSessionSettingsDirectory}, and settings loaded this way are
     * never written back.
     */
    @Singular("fixSessionSettingsUri")
    private final List<URI> fixSessionSettingsUris;
    /**
     * The directory of YAML session files. Required.
     */
    private File fixSessionSettingsDirectory;
}