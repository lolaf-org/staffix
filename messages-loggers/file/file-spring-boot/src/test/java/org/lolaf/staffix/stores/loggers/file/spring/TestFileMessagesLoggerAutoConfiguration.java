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
package org.lolaf.staffix.stores.loggers.file.spring;

import org.lolaf.staffix.stores.loggers.file.FileLogLineFormat;
import org.lolaf.staffix.stores.loggers.file.FileMessagesLoggerSettings;
import org.lolaf.staffix.stores.loggers.file.LogfmtFileLogLineFormat;
import org.lolaf.staffix.stores.loggers.file.PlainFileLogLineFormat;
import org.junit.jupiter.api.Test;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFileMessagesLoggerAutoConfiguration {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileMessagesLoggerAutoConfiguration.class));

    @Test
    void bindsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-loggers-file.instances.LOGS.log-directory=target/logs",
                        "staffix.messages-loggers-file.instances.LOGS.resource-check-interval=2s",
                        "staffix.messages-loggers-file.instances.LOGS.alert-clear-delay=1m")
                .run(context -> {
                    FixMessagesLoggerSettings settings = contribute(context.getBean(FileMessagesLoggerContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(settings.getAlertClearDelay()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void defaultsTheResourceCheckIntervalAndTheAlertClearDelay() {
        runner.withPropertyValues("staffix.messages-loggers-file.instances.LOGS.log-directory=target/logs")
                .run(context -> {
                    FixMessagesLoggerSettings settings = contribute(context.getBean(FileMessagesLoggerContributor.class));

                    assertThat(settings.getResourceCheckInterval()).isEqualTo(FixMessagesLoggerSettings.DEFAULT_RESOURCE_CHECK_INTERVAL);
                    assertThat(settings.getAlertClearDelay()).isEqualTo(FixMessagesLoggerSettings.DEFAULT_ALERT_CLEAR_DELAY);
                });
    }

    @Test
    void writesPlainLinesWithTheirPrefixesByDefault() {
        runner.withPropertyValues("staffix.messages-loggers-file.instances.LOGS.log-directory=target/logs",
                        "staffix.messages-loggers-file.instances.LOGS.plain.log-in-prefix=IN:")
                .run(context -> {
                    FileLogLineFormat format = lineFormat(context.getBean(FileMessagesLoggerContributor.class));

                    assertThat(format).isInstanceOf(PlainFileLogLineFormat.class);
                    assertThat(((PlainFileLogLineFormat) format).getLogInPrefix()).isEqualTo("IN:");
                    assertThat(((PlainFileLogLineFormat) format).getLogOutPrefix()).isEqualTo(" ->: ");
                });
    }

    @Test
    void writesLogfmtLinesWithTheirDelimiter() {
        runner.withPropertyValues("staffix.messages-loggers-file.instances.LOGS.log-directory=target/logs",
                        "staffix.messages-loggers-file.instances.LOGS.line-format=logfmt",
                        "staffix.messages-loggers-file.instances.LOGS.logfmt.fix-message-fields-delimiter=^")
                .run(context -> assertThat(lineFormat(context.getBean(FileMessagesLoggerContributor.class)))
                        .isInstanceOfSatisfying(LogfmtFileLogLineFormat.class,
                                logfmt -> assertThat(logfmt.getFixMessageFieldsDelimiter()).isEqualTo('^')));
    }

    @Test
    void keepsTheSohWithAnEmptyDelimiter() {
        runner.withPropertyValues("staffix.messages-loggers-file.instances.LOGS.log-directory=target/logs",
                        "staffix.messages-loggers-file.instances.LOGS.line-format=logfmt",
                        "staffix.messages-loggers-file.instances.LOGS.logfmt.fix-message-fields-delimiter=")
                .run(context -> assertThat(lineFormat(context.getBean(FileMessagesLoggerContributor.class)))
                        .isInstanceOfSatisfying(LogfmtFileLogLineFormat.class,
                                logfmt -> assertThat(logfmt.getFixMessageFieldsDelimiter()).isNull()));
    }

    @Test
    void usesTheLineFormatBeanItNames() {
        FileLogLineFormat custom = LogfmtFileLogLineFormat.builder().build();
        runner.withBean("customFormat", FileLogLineFormat.class, () -> custom)
                .withPropertyValues("staffix.messages-loggers-file.instances.LOGS.log-directory=target/logs",
                        "staffix.messages-loggers-file.instances.LOGS.line-format-bean=customFormat")
                .run(context -> assertThat(lineFormat(context.getBean(FileMessagesLoggerContributor.class)))
                        .isSameAs(custom));
    }

    @Test
    void refusesALineFormatAndABeanTogether() {
        runner.withBean("customFormat", FileLogLineFormat.class, () -> LogfmtFileLogLineFormat.builder().build())
                .withPropertyValues("staffix.messages-loggers-file.instances.LOGS.log-directory=target/logs",
                        "staffix.messages-loggers-file.instances.LOGS.line-format=logfmt",
                        "staffix.messages-loggers-file.instances.LOGS.line-format-bean=customFormat")
                .run(context -> assertThatThrownBy(() -> contribute(context.getBean(FileMessagesLoggerContributor.class)))
                        .hasMessageContaining("line-format-bean both choose the line format"));
    }

    private static FileLogLineFormat lineFormat(FileMessagesLoggerContributor contributor) {
        return ((FileMessagesLoggerSettings) contribute(contributor)).getLineFormat();
    }

    private static FixMessagesLoggerSettings contribute(FileMessagesLoggerContributor contributor) {
        Map<String, FixMessagesLoggerSettings> registry = new HashMap<>();
        contributor.contribute(registry);
        return registry.get("LOGS");
    }
}
