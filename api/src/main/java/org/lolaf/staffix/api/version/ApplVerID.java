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
 * ApplVerID(1128), the application version a FIXT.1.1 session carries.
 *
 * <p>An interface rather than just the enum so an application can name a version the shipped
 * {@link FixApplVerID} does not know, which is what a counterparty running a custom dictionary needs.
 */
public interface ApplVerID {

    String getCode();

}