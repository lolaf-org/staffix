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
/**
 * Messages as the engine sees them: the type of a message, its fields, and the registries a
 * dictionary provides for both.
 *
 * <p>The registries come from the generated FIX packages through {@link java.util.ServiceLoader}, so adding a
 * dictionary to the classpath is all it takes to speak that version.
 */
package org.lolaf.staffix.api.msg;
