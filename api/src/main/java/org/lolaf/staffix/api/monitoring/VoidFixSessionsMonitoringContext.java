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
package org.lolaf.staffix.api.monitoring;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * The context a session gets when nothing is monitoring it - a singleton whose meters record nothing, so the
 * message path has no monitoring branch to take.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class VoidFixSessionsMonitoringContext implements FixSessionsMonitoringContext {

    private static final VoidFixSessionsMonitoringContext INSTANCE = new VoidFixSessionsMonitoringContext();

    public static VoidFixSessionsMonitoringContext getInstance() {
        return INSTANCE;
    }

    @Override
    public Timer getTimer(String id, String description, Map<String, String> tags) {
        return Timer.VoidTimer.getInstance();
    }
}