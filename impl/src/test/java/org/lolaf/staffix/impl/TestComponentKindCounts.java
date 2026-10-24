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
package org.lolaf.staffix.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.api.application.FixApplicationFactorySettings;
import org.lolaf.staffix.api.metadata.ComponentKind;
import org.lolaf.staffix.api.metadata.ComponentKind.Cardinality;
import org.lolaf.staffix.api.session.FixSessionsSettingsStoreSettings;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.application.factories.simple.SimpleApplicationFactorySettings;
import org.lolaf.staffix.stores.sessions.memory.MemorySessionsSettingsStoreSettings;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins each kind's {@link ComponentKind#cardinality()}, which the component metadata publishes, to what the engine does.
 */
class TestComponentKindCounts {

    @Test
    void everyKindIsFoundOnTheEngineBuilder() {
        assertThat(kinds()).hasSize(8);
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void eachKindDeclaresHowManyComponentsTheEngineTakes(Class<?> kind) {
        assertThat(kind.getAnnotation(ComponentKind.class).cardinality()).isEqualTo(observedCount(kind));
    }

    private static Cardinality observedCount(Class<?> kind) {
        if (builderField(kind).getType() == kind) {
            return Cardinality.AT_MOST_ONE;
        }
        try {
            engineWithout(kind).instance();
            return Cardinality.ANY;
        } catch (IllegalStateException e) {
            assertThat(e).hasMessage("Provide at least one " + kind.getSimpleName());
            return Cardinality.AT_LEAST_ONE;
        }
    }

    /**
     * The smallest engine the builder accepts, less any component of {@code kind}.
     */
    private static FixEngineBuilder engineWithout(Class<?> kind) {
        FixEngineBuilder.FixEngineBuilderBuilder<?, ?> builder = FixEngineBuilder.builder();
        if (kind != FixMessagesStoreSettings.class) {
            builder.fixMessagesStore(TestingFixMessagesStoreSettings.builder()
                    .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                    .build());
        }
        if (kind != FixApplicationFactorySettings.class) {
            builder.fixApplicationFactory(SimpleApplicationFactorySettings.builder().build());
        }
        if (kind != FixSessionsSettingsStoreSettings.class) {
            builder.fixSessionsSettingsStore(MemorySessionsSettingsStoreSettings.builder().build());
        }
        return builder.build();
    }

    static List<Class<?>> kinds() {
        return Stream.of(FixEngineBuilder.class.getDeclaredFields())
                .map(TestComponentKindCounts::componentType)
                .filter(type -> type.isAnnotationPresent(ComponentKind.class))
                .collect(Collectors.toList());
    }

    private static Field builderField(Class<?> kind) {
        return Stream.of(FixEngineBuilder.class.getDeclaredFields())
                .filter(field -> componentType(field) == kind)
                .findFirst()
                .orElseThrow();
    }

    /**
     * The field's type, or the element type of a list.
     */
    private static Class<?> componentType(Field field) {
        Type type = field.getGenericType();
        if (field.getType() == List.class && type instanceof ParameterizedType) {
            type = ((ParameterizedType) type).getActualTypeArguments()[0];
        }
        if (type instanceof ParameterizedType) {
            type = ((ParameterizedType) type).getRawType();
        }
        return type instanceof Class ? (Class<?>) type : Object.class;
    }
}
