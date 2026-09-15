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
 * A minimal HTTP client abstraction, so the parts of the engine that push to a collector do not
 * depend on a particular HTTP library.
 *
 * <p>Implementations live in the {@code staffix-http-client-*} modules; the choice is the consumer's.
 */
package org.lolaf.staffix.api.http;
