package com.example.gateway.environment;

import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.loggers.async.AsyncMessagesLoggerSettings;
import org.lolaf.staffix.stores.loggers.file.FileMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;

import java.nio.file.Path;

/**
 * Messages stored on file with synchronous writes so a restart resumes the sequence numbers, and logged to file off
 * the session thread. Files go under {@code STAFFIX_DATA_DIR}.
 */
public class ProdEnvironment implements StaffixEnvironment {

    @Override
    public void addComponents(FixEngineBuilder.FixEngineBuilderBuilder engine) {
        Path dataDirectory = Path.of(requiredEnvironmentVariable("STAFFIX_DATA_DIR"));
        engine.fixMessagesStore(FileMessageStoreSettings.builder()
                        .storageDirectoryPath(dataDirectory.resolve("store").toString())
                        .build())
                .fixMessagesLogger(AsyncMessagesLoggerSettings.builder()
                        .asyncStoreSettings(AsyncStoreSettings.builder()
                                .asyncQueueDirectory(dataDirectory.resolve("log-queue").toString())
                                .build())
                        .wrappedLoggerSettings(FileMessagesLoggerSettings.builder()
                                .logDirectory(dataDirectory.resolve("log").toString())
                                .build())
                        .build())
                .fixSessionsSettingsStore(SessionsSettings.fromClasspath());
    }

    private static String requiredEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set in the prod environment");
        }
        return value;
    }
}
