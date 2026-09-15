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
package org.lolaf.staffix.spring.boot.spi;

import lombok.experimental.UtilityClass;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

/**
 * Helper for resolving an optional Spring bean reference declared as a {@code *Bean} property name
 * on a configuration-properties class. Used by SPI contributors to inject programmatic types
 * (Consumer/Supplier/Function/ExecutorService/...) that cannot bind from YAML directly.
 */
@UtilityClass
public final class BeanRef {

    /**
     * Resolve {@code beanName} to a bean of {@code type}, or {@link Optional#empty()} if {@code beanName} is null/blank.
     * Throws {@link IllegalArgumentException} with {@code propPath} in the message if the bean cannot
     * be resolved or is of the wrong type.
     *
     * <p>The bean type parameter is {@code Class<?>} (rather than {@code Class<T>}) so that callers
     * resolving generic types (e.g. {@code Consumer<X>} via {@code Consumer.class}) can receive a
     * properly parameterized {@code Optional<T>} without an additional unchecked cast at the call site.
     */
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> resolveOptional(ApplicationContext ctx, String beanName, Class<?> type, String propPath) {
        if (beanName == null || beanName.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of((T) ctx.getBean(beanName, type));
        } catch (BeansException e) {
            throw new IllegalArgumentException(propPath + "='" + beanName
                    + "' does not resolve to a bean of type " + type.getName(), e);
        }
    }
}
