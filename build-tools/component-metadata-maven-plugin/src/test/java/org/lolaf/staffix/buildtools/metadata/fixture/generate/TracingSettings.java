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
package org.lolaf.staffix.buildtools.metadata.fixture.generate;

import lombok.Builder;
import lombok.Getter;

import java.util.Set;

@Getter
@Builder
public class TracingSettings implements PluginSettings<String> {

    private final String endpoint;

    @Override
    public Set<Class<?>> getPluginTypes() {
        return Set.of(Integer.class, String.class);
    }
}
