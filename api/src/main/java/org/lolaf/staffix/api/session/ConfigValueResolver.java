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
package org.lolaf.staffix.api.session;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Optional;

/**
 * Supplies the value behind a {@code ${...}} placeholder in a session settings file, so one file can be
 * deployed unchanged across environments.
 *
 * <p>Resolvers are held in a {@link ConfigValueResolverChain} and asked in order until one answers.
 * Answer empty for a placeholder this resolver does not own, or whose key its source does not hold: a
 * default is the chain's business, not a resolver's, or the first resolver asked would always answer and
 * the rest would never be reached.
 */
public interface ConfigValueResolver {

    /**
     * @param placeholder the placeholder as written in the file, its braces already stripped
     * @return the value, or empty if this resolver does not answer for it
     */
    Optional<String> resolve(ConfigValuePlaceholder placeholder);

    /**
     * Called once before a settings load, so a resolver backed by something that changes - a remote
     * configuration service, a mounted secret, a file - can pick up what is current.
     *
     * <p>Once per load rather than per file, so every file in one load resolves against the same
     * snapshot.
     */
    default void refresh() {
        // Nothing to reload: the built-ins read their source on every call.
    }

    /**
     * The name this resolver is addressed by in {@code ${name:key}}, if it has one.
     *
     * <p>What disambiguates the two supported forms: the chain knows every registered name, so in
     * {@code ${a:b}} it can tell a source called {@code a} from a key {@code a} defaulting to {@code b}.
     * A resolver with no name is offered every placeholder and decides for itself.
     *
     * @return the source name, or empty to be offered everything
     */
    default Optional<String> sourceName() {
        return Optional.empty();
    }

    /**
     * Resolves against system properties, addressed as {@code ${sysprop:key}}.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class SystemPropertyConfigValueResolver implements ConfigValueResolver {

        public static final String SOURCE = "sysprop";

        private static final SystemPropertyConfigValueResolver INSTANCE =
                new SystemPropertyConfigValueResolver();

        public static SystemPropertyConfigValueResolver getInstance() {
            return INSTANCE;
        }

        @Override
        public Optional<String> resolve(ConfigValuePlaceholder placeholder) {
            return Optional.ofNullable(System.getProperty(placeholder.getKey()));
        }

        @Override
        public Optional<String> sourceName() {
            return Optional.of(SOURCE);
        }
    }

    /**
     * Resolves against environment variables, addressed as {@code ${env:KEY}}.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class EnvironmentVariableConfigValueResolver implements ConfigValueResolver {

        public static final String SOURCE = "env";

        private static final EnvironmentVariableConfigValueResolver INSTANCE =
                new EnvironmentVariableConfigValueResolver();

        public static EnvironmentVariableConfigValueResolver getInstance() {
            return INSTANCE;
        }

        @Override
        public Optional<String> resolve(ConfigValuePlaceholder placeholder) {
            return Optional.ofNullable(System.getenv(placeholder.getKey()));
        }

        @Override
        public Optional<String> sourceName() {
            return Optional.of(SOURCE);
        }
    }
}
