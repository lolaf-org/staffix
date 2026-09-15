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

import org.lolaf.staffix.api.admin.AdminApi;

import java.util.List;

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
}
