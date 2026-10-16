package com.example.gateway.environment;

import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;

/**
 * Nothing on disk and every message logged.
 */
public class DevEnvironment implements StaffixEnvironment {

    @Override
    public void addComponents(FixEngineBuilder.FixEngineBuilderBuilder engine) {
        engine.fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().messageFieldsDelimiter('|').build())
                .fixSessionsSettingsStore(SessionsSettings.fromClasspath());
    }
}
