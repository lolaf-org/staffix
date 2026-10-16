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
package org.lolaf.staffix.buildtools.metadata.fixture.spring;

import lombok.Data;
import org.lolaf.staffix.api.metadata.ConfiguresComponent;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.WrapperSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Properties for the fixture stores.
 */
@ConfiguresComponent(WrapperSettings.class)
@Data
@ConfigurationProperties(prefix = "staffix.fixture-stores")
public class FixtureStoreProps {

    /**
     * Bean name of the registry every instance shares.
     */
    private String registryBean;

    /**
     * The configured instances, keyed by instance id.
     */
    private Map<String, FixtureStoreEntryProps> instances = new LinkedHashMap<>();
}
