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
 * The engine, the initiator and the acceptor - the runtime behind the {@code staffix-api} interfaces.
 *
 * <p>The {@code FailSafe*} wrappers here share one purpose: application code, plugins and stores all run on the
 * session thread, and a fault in any of them must not end a connection.
 */
package org.lolaf.staffix.impl;
