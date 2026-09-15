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
package org.lolaf.staffix.api.version;

/**
 * A FIX version, being what BeginString(8) carries.
 *
 * <p>Implemented by both {@link FixRegularVersion} and {@link FixtVersion} because from FIX.5.0 the version on
 * the wire is FIXT.1.1 and the application version moves to ApplVerID(1128); code that only needs "which
 * BeginString" should not have to care which of the two it has.
 */
public interface FixVersion {

    int getMinor();

    int getMajor();

    byte[] getBeginString();

    String getId();
}