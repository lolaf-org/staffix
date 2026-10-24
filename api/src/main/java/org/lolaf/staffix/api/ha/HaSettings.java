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
package org.lolaf.staffix.api.ha;

import lombok.Builder;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NullMarked;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.metadata.ComponentKind;

/**
 * Settings for an engine's high availability, resolved through the {@link org.lolaf.staffix.api.Factory} SPI.
 */
@ComponentKind(value = "high-availability", cardinality = ComponentKind.Cardinality.AT_MOST_ONE)
@NullMarked
@Getter
@SuperBuilder(toBuilder = true)
public abstract class HaSettings implements InstanceProvider<FixEngineHa> {

    /**
     * Names this instance in logs and metrics.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;

    @SuppressWarnings("unchecked")
    @Override
    public FixEngineHa instance() {
        return (FixEngineHa) InstanceProvider.getSpiInstance(this, FixEngineHa.FixEngineHaFactory.class);
    }
}
