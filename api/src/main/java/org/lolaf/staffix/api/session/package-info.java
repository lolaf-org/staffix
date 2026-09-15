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
 * A session and everything that configures it - its identity on the wire, its settings, and the
 * store its configuration comes from.
 *
 * <p>{@link org.lolaf.staffix.api.session.FixSessionId} is the identity a counterparty is known by;
 * {@link org.lolaf.staffix.api.session.FixSessionSettings} is everything else, and its nested
 * {@code ValidationSettings} is worth reading before changing, since each flag there costs something on the
 * message path.
 */
package org.lolaf.staffix.api.session;
