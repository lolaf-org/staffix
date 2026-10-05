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
package org.lolaf.staffix.application.factories.spring;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationFactory;
import org.springframework.context.support.StaticApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringApplicationFactoryTest {

    @Test
    void listsTheFixApplicationBeansOnly() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.getBeanFactory().registerSingleton("orders", mock(FixApplication.class));
        context.getBeanFactory().registerSingleton("dropCopy", mock(FixApplication.class));
        context.getBeanFactory().registerSingleton("clock", new Object());

        FixApplicationFactory factory = new SpringApplicationFactory.SpringApplicationFactoryImpl().newInstance(SpringApplicationFactorySettings.builder()
                .applicationContext(context)
                .build());

        assertThat(factory.getApplicationIds()).containsExactlyInAnyOrder("dropCopy", "orders");
    }
}
