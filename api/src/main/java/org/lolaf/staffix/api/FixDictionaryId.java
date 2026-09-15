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

import lombok.Value;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixVersion;

/**
 * Identifies one FIX dictionary, being a name and the version it describes.
 *
 * <p>Both are needed because the same name means different messages at different versions: a session on FIX.4.2
 * and one on FIX.4.4 both use the {@code default} dictionary and must not resolve to the same encoders. That is
 * why {@link #of(String, FixRegularVersion)} folds the version into the id rather than leaving them separate.
 */
@Value
public class FixDictionaryId {

    public static final String DEFAULT_ID = "default";

    String id;
    FixVersion targetFixVersion;

    public static FixDictionaryId of(String id, FixRegularVersion version) {
        return new FixDictionaryId(id + "-" + version, version);
    }
}
