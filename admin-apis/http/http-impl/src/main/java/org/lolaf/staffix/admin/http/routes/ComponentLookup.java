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

package org.lolaf.staffix.admin.http.routes;

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.admin.http.HttpProblemException;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.alerting.ProbeScope;

/**
 * Resolves the {@code {component}} of a path to the scope of the engine component with probes of that name, or
 * answers 404.
 */
@RequiredArgsConstructor
public final class ComponentLookup {

    private final AdminApi adminApi;

    ProbeScope scope(String component) {
        for (ProbeScope scope : adminApi.getComponentProbeScopes()) {
            if (scope.getName().equals(component)) {
                return scope;
            }
        }
        throw new HttpProblemException(404, "Engine " + adminApi.getInstanceId() + " has no component " + component + " with probes");
    }
}
