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

package org.lolaf.staffix.api.alerting;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.Startable;

/**
 * The publisher an engine uses when none is configured, so probes are still evaluated and their state still readable
 * through the admin API.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class VoidAlertsPublisher extends Startable.VoidStartable<FixAlertsPublisher> implements FixAlertsPublisher {

    private static final VoidAlertsPublisher INSTANCE = new VoidAlertsPublisher();

    /**
     * The shared instance; it holds no state, so one serves every engine.
     */
    public static VoidAlertsPublisher getInstance() {
        return INSTANCE;
    }

    /**
     * The default id, so sessions that never name a publisher resolve to this one.
     */
    @Override
    public String getInstanceId() {
        return InstanceProvider.DEFAULT_INSTANCE_ID;
    }

    @Override
    public void publish(AlertEvent event) {
        // nothing to do
    }
}
