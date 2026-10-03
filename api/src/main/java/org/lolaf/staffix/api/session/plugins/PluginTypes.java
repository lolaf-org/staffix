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
package org.lolaf.staffix.api.session.plugins;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Resolves the plugin type a settings class declares as its {@link FixSessionsPluginSettings} type argument.
 */
final class PluginTypes {

    private PluginTypes() {
    }

    @SuppressWarnings("unchecked")
    static Class<? extends FixSessionsPlugin<?>> declaredBy(Class<?> settingsClass) {
        return (Class<? extends FixSessionsPlugin<?>>) find(settingsClass).orElseThrow(() -> new IllegalStateException(
                settingsClass + " does not declare its plugin type; override getPluginTypes()"));
    }

    private static Optional<Class<?>> find(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterized = (ParameterizedType) type;
            if (parameterized.getRawType() == FixSessionsPluginSettings.class) {
                Type plugin = parameterized.getActualTypeArguments()[0];
                Type raw = plugin instanceof ParameterizedType ? ((ParameterizedType) plugin).getRawType() : plugin;
                return raw instanceof Class ? Optional.of((Class<?>) raw) : Optional.empty();
            }
            type = parameterized.getRawType();
        }
        if (!(type instanceof Class)) {
            return Optional.empty();
        }
        Class<?> clazz = (Class<?>) type;
        for (Type implemented : clazz.getGenericInterfaces()) {
            Optional<Class<?>> found = find(implemented);
            if (found.isPresent()) {
                return found;
            }
        }
        return clazz.getGenericSuperclass() == null ? Optional.empty() : find(clazz.getGenericSuperclass());
    }
}
