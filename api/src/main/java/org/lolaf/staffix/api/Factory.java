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

/**
 * How a settings object becomes the thing it configures. Implementations are found through {@link java.util.ServiceLoader},
 * and {@link #getSettingsClass()} is what the lookup matches on, so a settings class chooses its implementation
 * without the caller naming one.
 *
 * <p>That indirection is the reason an application depends on {@code staffix-api} at compile time and on an
 * implementation module only at runtime.
 *
 * @param <T> what is built
 * @param <S> the settings that describe it
 */
public interface Factory<T, S> {

    Class<S> getSettingsClass();

    T newInstance(S settings);
}
