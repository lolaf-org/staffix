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
package org.lolaf.staffix.buildtools.metadata.fixture.generate;

import org.lolaf.staffix.api.metadata.ComponentKind;
import org.lolaf.staffix.api.metadata.SelectedBy;

@ComponentKind(value = "fixture-store", cardinality = ComponentKind.Cardinality.AT_LEAST_ONE)
@SelectedBy(setting = "fixtureStoreInstanceId", whenUnnamed = SelectedBy.WhenUnnamed.DEFAULT_INSTANCE,
        whenUnknown = SelectedBy.WhenUnknown.REFUSED)
@SelectedBy(by = SelectedBy.By.ACCEPTOR, setting = "targetStores", springSetting = "staffix.acceptors.<name>.target-stores",
        whenUnnamed = SelectedBy.WhenUnnamed.DEFAULT_INSTANCE)
@SelectedBy(by = SelectedBy.By.INITIATOR, whenUnnamed = SelectedBy.WhenUnnamed.ALL)
public interface StoreSettings {
}
