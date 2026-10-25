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

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.alerting.ProbeScope;

/**
 * The probes of one of the engine's own components, such as its high availability: a scope of type {@value #TYPE},
 * named after the component and labelled with it, so an alert routes without a session.
 */
@UtilityClass
public class ComponentProbeScope {

    public static final String TYPE = "component";
    public static final String COMPONENT_LABEL = "component";

    public static ProbeScope of(String component) {
        return ProbeScope.builder().type(TYPE).name(component).label(COMPONENT_LABEL, component).build();
    }

    public static boolean isComponent(ProbeScope scope) {
        return TYPE.equals(scope.getType());
    }
}
