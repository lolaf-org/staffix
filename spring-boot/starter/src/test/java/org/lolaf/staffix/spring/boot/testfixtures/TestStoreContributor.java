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
package org.lolaf.staffix.spring.boot.testfixtures;

import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.spring.boot.spi.FixMessagesStoreSettingsContributor;
import org.lolaf.staffix.tests.TestingFixMessagesStoreSettings;
import org.lolaf.staffix.tests.TestingFixSessionMessagesStore;

import java.util.Map;

public class TestStoreContributor implements FixMessagesStoreSettingsContributor {

    private final String instanceId;
    private final int order;

    public TestStoreContributor(String instanceId) {
        this(instanceId, 0);
    }

    public TestStoreContributor(String instanceId, int order) {
        this.instanceId = instanceId;
        this.order = order;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public void contribute(Map<String, FixMessagesStoreSettings> registry) {
        registry.put(instanceId, TestingFixMessagesStoreSettings.builder()
                .instanceId(instanceId)
                .testingFixSessionMessagesStore(new TestingFixSessionMessagesStore())
                .build());
    }
}
