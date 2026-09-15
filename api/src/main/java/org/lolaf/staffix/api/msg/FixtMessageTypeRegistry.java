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
package org.lolaf.staffix.api.msg;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.version.FixtVersion;

import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * The message types of the FIXT.1.1 session layer, keyed by FIXT version rather than by dictionary.
 *
 * <p>Separate from {@link MessageTypeRegistry} because from FIX.5.0 the session layer and the application layer
 * are versioned apart: one FIXT registry serves sessions carrying any of the FIX.5.0 dictionaries.
 */
public interface FixtMessageTypeRegistry extends MessageTypeRegistry {

    FixtVersion getFixtVersion();

    @Slf4j
    @UtilityClass
    class Registry {

        private static final Collection<FixtMessageTypeRegistry> SPI_INSTANCES = loadInstances();

        public static FixtMessageTypeRegistry getInstance(FixtVersion fixtVersion) {
            return SPI_INSTANCES.stream().filter(r -> r.getFixtVersion().equals(fixtVersion)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unable to find any MessageTypeRegistry FIXT SPI instance for " + fixtVersion));
        }

        private static Collection<FixtMessageTypeRegistry> loadInstances() {
            List<FixtMessageTypeRegistry> instances = ServiceLoader.load(FixtMessageTypeRegistry.class)
                    .stream().map(ServiceLoader.Provider::get).collect(Collectors.toList());
            log.info("Loaded MessageTypeRegistry FIXT SPI instances for versions: {}", instances.stream().map(FixtMessageTypeRegistry::getFixtVersion).collect(Collectors.toList()));
            return instances;
        }
    }
}
