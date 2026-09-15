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

import java.util.*;

/**
 * Replaces every {@code ${...}} in a configured value, asking each {@link ConfigValueResolver} in turn and
 * stopping at the first that answers.
 *
 * <p>A value may be part placeholder and may hold several - {@code prefix-${sysprop:x}-${env:Y}} resolves
 * both and keeps the literal text around them. A value holding none is returned untouched, so an ordinary
 * setting never reaches a resolver.
 *
 * <p>When nothing answers, the placeholder's default is used; with no default this throws, because a
 * session started on an unresolved value is worse than one that fails to start. There is no way to write
 * an opening brace literally; nothing escapes it.
 */
public final class ConfigValueResolverChain {

    private static final String OPEN = "${";
    private static final char CLOSE = '}';

    private final List<ConfigValueResolver> resolvers;
    private final Set<String> sourceNames;

    /**
     * @param resolvers asked in order; the default chain resolves system properties then environment
     *                  variables
     */
    public ConfigValueResolverChain(List<ConfigValueResolver> resolvers) {
        this.resolvers = Collections.unmodifiableList(new ArrayList<>(resolvers));
        Set<String> names = new HashSet<>();
        for (ConfigValueResolver resolver : this.resolvers) {
            resolver.sourceName().ifPresent(names::add);
        }
        this.sourceNames = Collections.unmodifiableSet(names);
    }

    /**
     * @return a chain over system properties then environment variables
     */
    public static ConfigValueResolverChain defaultChain() {
        return new ConfigValueResolverChain(List.of(
                ConfigValueResolver.SystemPropertyConfigValueResolver.getInstance(),
                ConfigValueResolver.EnvironmentVariableConfigValueResolver.getInstance()));
    }

    /**
     * @return whether the value holds anything this chain would replace
     */
    public static boolean holdsPlaceholder(String value) {
        return value != null && value.contains(OPEN);
    }

    private static boolean answers(ConfigValueResolver resolver, ConfigValuePlaceholder placeholder) {
        Optional<String> name = resolver.sourceName();
        // A named resolver keeps to its own placeholders; an unnamed one is offered everything.
        return placeholder.getSource() == null
                || name.isEmpty()
                || name.get().equals(placeholder.getSource());
    }

    /**
     * Refreshes every resolver, once, before a load.
     */
    public void refresh() {
        resolvers.forEach(ConfigValueResolver::refresh);
    }

    /**
     * @param value the configured value, which may hold no placeholder at all
     * @param where what to name in the failure message, such as the file and field being read
     * @return the value with every placeholder replaced
     * @throws IllegalStateException if a placeholder is answered by no resolver and carries no default
     */
    public String resolve(String value, String where) {
        if (value == null || !value.contains(OPEN)) {
            return value;
        }
        StringBuilder resolved = new StringBuilder(value.length());
        int cursor = 0;
        while (true) {
            int open = value.indexOf(OPEN, cursor);
            if (open < 0) {
                break;
            }
            int close = value.indexOf(CLOSE, open);
            if (close < 0) {
                throw new IllegalStateException("Unterminated placeholder in " + where + ": " + value);
            }
            resolved.append(value, cursor, open)
                    .append(valueOf(value.substring(open + OPEN.length(), close), where));
            cursor = close + 1;
        }
        return resolved.append(value.substring(cursor)).toString();
    }

    private String valueOf(String text, String where) {
        ConfigValuePlaceholder placeholder = ConfigValuePlaceholder.parse(text, sourceNames);
        for (ConfigValueResolver resolver : resolvers) {
            if (answers(resolver, placeholder)) {
                Optional<String> resolved = resolver.resolve(placeholder);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            }
        }
        if (placeholder.getDefaultValue() != null) {
            return placeholder.getDefaultValue();
        }
        throw new IllegalStateException("No resolver answered " + placeholder + " in " + where
                + ", and it carries no default value");
    }
}
