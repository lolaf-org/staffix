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
package org.lolaf.staffix.api;

import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;

/**
 * A named component that can also build itself - {@link InstanceIdSupplier} plus {@link #instance()}.
 *
 * <p>{@link #getSpiInstance(Object, Class)} is the lookup every settings class goes through: it walks the
 * {@link java.util.ServiceLoader} for the given {@link Factory} SPI and takes the one whose settings class matches,
 * which is what lets an implementation be swapped on the runtime classpath alone.
 */
public interface InstanceProvider<T> extends InstanceIdSupplier {

    /**
     * The instance id a component gets when its configuration names none.
     */
    String DEFAULT_INSTANCE_ID = "default";

    /**
     * Builds the component the settings describe, through the first factory of the SPI whose settings class matches.
     *
     * @throws IllegalStateException if no implementation on the classpath serves these settings
     */
    static <I, F extends Factory<I, S>, S> I getSpiInstance(S settings, Class<F> spiClass) {
        for (ServiceLoader<F> loader : List.of(ServiceLoader.load(spiClass), ServiceLoader.load(spiClass, spiClass.getClassLoader()))) {
            for (F instance : loader) {
                if (instance.getSettingsClass().equals(settings.getClass())) {
                    return instance.newInstance(settings);
                }
            }
        }
        throw new IllegalStateException("Unable to find any SPI instance for target settings class " + settings.getClass());
    }

    /**
     * A consumer that does nothing, the default for an optional callback.
     */
    static <C> Consumer<C> emptyConsumer() {
        return t -> {
            // nothing to do
        };
    }

    /**
     * Builds the component these settings describe.
     */
    T instance();
}