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

import lombok.Getter;

import java.util.Set;

/**
 * One {@code ${...}} occurrence, split into the source that answers it, the key it asks for and the value
 * to fall back on.
 *
 * <p>Two forms are accepted, {@code ${source:key:default}} and Spring's own {@code ${key:default}}. They
 * collide at two tokens - {@code ${a:b}} is either source {@code a} asking for key {@code b}, or key
 * {@code a} defaulting to {@code b} - so parsing needs the set of registered source names to tell them
 * apart. A default may itself contain {@code :}; it is everything after the key.
 */
@Getter
public final class ConfigValuePlaceholder {

    private static final char SEPARATOR = ':';

    /**
     * What was written inside the braces.
     */
    private final String text;

    /**
     * The source addressed, or {@code null} for the prefix-less form.
     */
    private final String source;

    private final String key;

    /**
     * The fallback, or {@code null} when none was written.
     */
    private final String defaultValue;

    private ConfigValuePlaceholder(String text, String source, String key, String defaultValue) {
        this.text = text;
        this.source = source;
        this.key = key;
        this.defaultValue = defaultValue;
    }

    /**
     * @param text        what was written inside the braces
     * @param sourceNames every source name registered on the chain
     * @return the parsed placeholder
     * @throws IllegalArgumentException if there is no key to look up
     */
    public static ConfigValuePlaceholder parse(String text, Set<String> sourceNames) {
        int firstSeparator = text.indexOf(SEPARATOR);
        if (firstSeparator < 0) {
            return new ConfigValuePlaceholder(text, null, requireKey(text, text), null);
        }
        String head = text.substring(0, firstSeparator);
        String tail = text.substring(firstSeparator + 1);
        if (!sourceNames.contains(head)) {
            return new ConfigValuePlaceholder(text, null, requireKey(head, text), tail);
        }
        int secondSeparator = tail.indexOf(SEPARATOR);
        return secondSeparator < 0
                ? new ConfigValuePlaceholder(text, head, requireKey(tail, text), null)
                : new ConfigValuePlaceholder(text, head,
                requireKey(tail.substring(0, secondSeparator), text),
                tail.substring(secondSeparator + 1));
    }

    private static String requireKey(String key, String text) {
        if (key.trim().isEmpty()) {
            throw new IllegalArgumentException("The placeholder ${" + text + "} names no key to look up");
        }
        return key;
    }

    @Override
    public String toString() {
        return "${" + text + "}";
    }
}
