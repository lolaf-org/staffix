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
 * The engine and its connections: build a {@link org.lolaf.staffix.api.FixEngine} from a
 * {@link org.lolaf.staffix.api.FixEngineBuilder}, then a {@link org.lolaf.staffix.api.FixInitiator} to dial out
 * or a {@link org.lolaf.staffix.api.FixAcceptor} to listen.
 *
 * <p>Start here. Everything an application compiles against is in this module; the implementation is chosen on
 * the runtime classpath through the {@link org.lolaf.staffix.api.Factory} SPI, which is why no interface here
 * names one.
 */
package org.lolaf.staffix.api;
