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
package org.lolaf.staffix.admin.jmx;

import lombok.Value;
import org.lolaf.staffix.api.FixInitiatorTarget;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The engine-level MBean: the sessions it holds, and engine-wide operations.
 */
public class FixAdminMBeanImpl implements FixAdminMXBean {

    private final AdminApi delegate;

    FixAdminMBeanImpl(AdminApi delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<String> getFixSessionsSettingsStoresInstanceIds() {
        return delegate.getFixSessionsSettingsStoresInstanceIds();
    }

    @Override
    public void reloadFixSessionsSettingsStore(String instanceId) {
        delegate.reloadFixSessionsSettingsStore(instanceId);
    }

    @Override
    public List<InitiatorTargets> getInitiatorsTargets() {
        return delegate.getInitiatorsTargets().stream()
                .map(targets -> new InitiatorTargetsImpl(targets.getInstanceId(), targets.getActiveFixSessionId().toString(),
                        targets.getTargets().stream().map(target -> target.getFixSessionId().toString()).collect(Collectors.toList())))
                .collect(Collectors.toList());
    }

    @Override
    public void switchInitiatorSession(String fixSessionId) {
        List<FixSessionId> targetIds = delegate.getInitiatorsTargets().stream()
                .flatMap(targets -> targets.getTargets().stream())
                .map(FixInitiatorTarget::getFixSessionId)
                .distinct()
                .collect(Collectors.toList());
        delegate.switchInitiatorSession(targetIds.stream()
                .filter(id -> id.toString().equals(fixSessionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No initiator has FIX session " + fixSessionId
                        + " as a target, targets are: " + targetIds)));
    }

    @Value
    private static class InitiatorTargetsImpl implements InitiatorTargets {
        String instanceId;
        String activeFixSessionId;
        List<String> fixSessionIds;
    }
}
