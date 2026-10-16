package com.example.fixclient.environment;

import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.stores.loggers.slf4j.Slf4jMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.memory.MemoryMessageStoreSettings;

/**
 * Nothing on disk and every message logged: sequence numbers reset on each logon, see the session file.
 */
public class DevEnvironment implements StaffixEnvironment {

    @Override
    public void addComponents(FixEngineBuilder.FixEngineBuilderBuilder engine) {
        engine.fixMessagesStore(MemoryMessageStoreSettings.builder().build())
                .fixMessagesLogger(Slf4jMessagesLoggerSettings.builder().messageFieldsDelimiter('|').build())
                .fixSessionsSettingsStore(SessionsSettings.fromClasspath());
    }
}
