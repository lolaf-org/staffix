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
 * FIX versions, and the two ways a message states one.
 *
 * <p>Before FIX.5.0 the version is in BeginString(8) - {@link org.lolaf.staffix.api.version.FixRegularVersion}.
 * From FIX.5.0 BeginString carries the transport version, {@link org.lolaf.staffix.api.version.FixtVersion}, and
 * the application version moves to ApplVerID(1128).
 */
package org.lolaf.staffix.api.version;
