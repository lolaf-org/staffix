package com.example.fixclient.environment;

import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.api.FixEngineBuilder;
import org.lolaf.staffix.stores.core.async.AsyncStoreSettings;
import org.lolaf.staffix.stores.loggers.async.AsyncMessagesLoggerSettings;
import org.lolaf.staffix.stores.loggers.file.FileMessagesLoggerSettings;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;

import java.nio.file.Path;

/**
 * Messages stored on file with synchronous writes so a restart resumes the sequence numbers, and logged to file off
 * the session thread, all under one data directory.
 */
@RequiredArgsConstructor
public class ProdEnvironment implements StaffixEnvironment {

    private final Path dataDirectory;

    @Override
    public void addComponents(FixEngineBuilder.FixEngineBuilderBuilder engine) {
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
}
