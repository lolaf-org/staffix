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
package org.lolaf.staffix.buildtools.quickfixj;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QuickFIX/J's own constants are the list of keys a {@code .cfg} can hold: the converter and {@code cfg-keys.md} must
 * name each of them, so a QuickFIX/J upgrade that adds or renames one fails here.
 */
class TestQuickfixjKeys {

    private static final List<String> DECLARING_CLASSES = List.of(
            "quickfix.Session", "quickfix.SessionSettings", "quickfix.SessionFactory", "quickfix.Initiator",
            "quickfix.Acceptor", "quickfix.FileStoreFactory", "quickfix.SleepycatStoreFactory", "quickfix.JdbcSetting",
            "quickfix.FileLogFactory", "quickfix.ScreenLogFactory", "quickfix.SLF4JLogFactory",
            "quickfix.mina.NetworkingOptions", "quickfix.mina.ssl.SSLSupport");
    private static final Set<String> SESSION_ID_CONSTANTS = Set.of("BEGINSTRING", "SENDERCOMPID", "SENDERSUBID",
            "SENDERLOCID", "TARGETCOMPID", "TARGETSUBID", "TARGETLOCID", "SESSION_QUALIFIER");
    private static final Path CFG_KEYS = Path.of("../../claude-plugin/skills/migrate-from-quickfixj/references/cfg-keys.md");

    @Test
    void theConverterHandlesEveryKeyQuickfixjDeclaresAndNoOther() throws ReflectiveOperationException {
        Set<String> converted = new TreeSet<>(CfgToStaffix.SESSION_KEYS);
        converted.addAll(CfgToStaffix.COMPONENT_KEYS.keySet());
        converted.addAll(CfgToStaffix.NO_EQUIVALENT.keySet());

        assertThat(converted).isEqualTo(quickfixjKeys());
    }

    @Test
    void eachKeyIsHandledOnce() {
        Set<String> seen = new HashSet<>();
        Set<String> twice = new TreeSet<>();
        for (Set<String> keys : List.of(CfgToStaffix.SESSION_KEYS, CfgToStaffix.COMPONENT_KEYS.keySet(),
                CfgToStaffix.NO_EQUIVALENT.keySet())) {
            for (String key : keys) {
                if (!seen.add(key)) {
                    twice.add(key);
                }
            }
        }

        assertThat(twice).isEmpty();
    }

    @Test
    void cfgKeysNamesEveryKeyQuickfixjDeclares() throws IOException, ReflectiveOperationException {
        Set<String> documented = new HashSet<>();
        Matcher code = Pattern.compile("`([A-Za-z0-9]+)(?:<n>)?(?:=[^`]*)?`").matcher(Files.readString(CFG_KEYS));
        while (code.find()) {
            documented.add(code.group(1));
        }

        assertThat(quickfixjKeys()).isSubsetOf(documented);
    }

    private static Set<String> quickfixjKeys() throws ReflectiveOperationException {
        Set<String> keys = new TreeSet<>();
        for (String className : DECLARING_CLASSES) {
            for (Field field : Class.forName(className).getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean setting = field.getName().startsWith("SETTING_")
                        || className.equals("quickfix.SessionSettings") && SESSION_ID_CONSTANTS.contains(field.getName());
                if (setting && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                        && field.getType() == String.class) {
                    field.setAccessible(true);
                    keys.add((String) field.get(null));
                }
            }
        }
        return keys;
    }
}
