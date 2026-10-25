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
package org.lolaf.staffix.spring.boot;

import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.ha.FixEngineHa;
import org.lolaf.staffix.spring.boot.spi.HaSettingsContributor;
import org.lolaf.staffix.spring.boot.testfixtures.TestLoggerContributor;
import org.lolaf.staffix.spring.boot.testfixtures.TestSessionsSettingsStoreContributor;
import org.lolaf.staffix.spring.boot.testfixtures.TestStoreContributor;
import org.lolaf.staffix.tests.TestingHaSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestStaffixHighAvailability {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StaffixAutoConfiguration.class))
            .withBean(TestStoreContributor.class, () -> new TestStoreContributor("STORE"))
            .withBean(TestLoggerContributor.class, () -> new TestLoggerContributor("LOGGER"))
            .withBean(TestSessionsSettingsStoreContributor.class, () -> new TestSessionsSettingsStoreContributor("SESSIONS", List.of()));

    @Test
    void theEngineRunsTheContributedHighAvailability() {
        FixEngineHa ha = mock(FixEngineHa.class);
        runner.withBean(HaSettingsContributor.class, () -> sink -> sink.accept(TestingHaSettings.builder().mock(ha).build()))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    verify(ha).bind(any());
                    verify(ha).start();
                });
    }

    @Test
    void twoContributionsAreRefused() {
        HaSettingsContributor contributor = sink -> sink.accept(TestingHaSettings.builder().mock(mock(FixEngineHa.class)).build());
        runner.withBean("first", HaSettingsContributor.class, () -> contributor)
                .withBean("second", HaSettingsContributor.class, () -> contributor)
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("Multiple high availability contributors"));
    }
}
