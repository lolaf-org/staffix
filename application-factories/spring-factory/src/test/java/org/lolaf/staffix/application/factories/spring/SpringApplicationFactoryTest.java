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
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.application.FixApplication;
import org.lolaf.staffix.api.application.FixApplicationFactory;
import org.lolaf.staffix.api.codec.FixMessageDecoder;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.springframework.context.support.StaticApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void checkingAPrototypeApplicationsDictionaryCreatesOneInstanceItNeverDestroys() {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerPrototype("orders", CountedApplication.class);
        context.refresh();
        FixApplicationFactory factory = new SpringApplicationFactory.SpringApplicationFactoryImpl().newInstance(SpringApplicationFactorySettings.builder()
                .applicationContext(context)
                .build());
        factory.start();
        CountedApplication.CREATED.set(0);
        CountedApplication.DESTROYED.set(0);

        assertThat(factory.getDictionaryId("orders")).isEqualTo(CountedApplication.DICTIONARY_ID);
        assertThat(factory.getDictionaryId("orders")).isEqualTo(CountedApplication.DICTIONARY_ID);
        factory.getInstance("orders");
        factory.stop(Deadline.unlimited());

        assertThat(CountedApplication.CREATED).hasValue(2);
        assertThat(CountedApplication.DESTROYED).hasValue(1);
    }

    public static class CountedApplication implements FixApplication {

        static final FixDictionaryId DICTIONARY_ID = FixDictionaryId.of(FixRegularVersion.VERSION_44);
        static final AtomicInteger CREATED = new AtomicInteger();
        static final AtomicInteger DESTROYED = new AtomicInteger();

        public CountedApplication() {
            CREATED.incrementAndGet();
        }

        @Override
        public void destroy() {
            DESTROYED.incrementAndGet();
        }

        @Override
        public FixApiVersion getFixApiVersion() {
            return FixApiVersion.of(FixRegularVersion.VERSION_44);
        }

        @Override
        public FixDictionaryId getDictionaryId() {
            return DICTIONARY_ID;
        }

        @Override
        public List<FixMessageDecoder> setup(FixSessionSettings fixSessionSettings, FixSession fixSession, Set<MessageType> encodedMessagesTypes) {
            return List.of();
        }
    }
}
