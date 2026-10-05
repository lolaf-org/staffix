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
package org.lolaf.staffix.application.factories.simple;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SimpleApplicationFactoryTest {

    @Test
    void listsTheIdsItWasGivenApplicationsFor() {
        FixApplicationFactory factory = new SimpleApplicationFactory.SimpleApplicationFactoryImpl().newInstance(SimpleApplicationFactorySettings.builder()
                .application("orders", mock(FixApplication.class))
                .application("drop-copy", mock(FixApplication.class))
                .build());

        assertThat(factory.getApplicationIds()).containsExactlyInAnyOrder("drop-copy", "orders");
    }
}
