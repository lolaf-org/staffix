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
package org.lolaf.staffix.stores.messages.file;

import lombok.Getter;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists messages and sequence numbers to files, so a restarted session can still answer a ResendRequest.
 */
public class FileMessagesStore extends Startable.SimpleStartable<FixMessagesStore> implements FixMessagesStore {

    private final FileMessageStoreSettings settings;
    private final Map<FixSessionId, FixSessionMessagesStore> fileFixSessionStoreContext;
    @Getter
    private final String instanceId;

    protected FileMessagesStore(FileMessageStoreSettings settings) {
        this.settings = settings;
        this.fileFixSessionStoreContext = new ConcurrentHashMap<>();
        this.instanceId = settings.getInstanceId();
    }

    @Override
    public FixSessionMessagesStore getStore(FixSessionId fixSessionId) {
        return fileFixSessionStoreContext.computeIfAbsent(fixSessionId, sid -> new FileFixSessionMessagesStore(settings, fixSessionId));
    }

    @Override
    protected void startMe() throws StartStopException {
        if (settings.getStorageDirectoryPath() == null) {
            throw new IllegalArgumentException("Missing StorageDirectoryPath setting");
        }
        File directory = new File(settings.getStorageDirectoryPath());
        if (directory.exists() && !directory.isDirectory()) {
            throw new IllegalArgumentException(directory.getPath() + " is not a directory");
        }
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalArgumentException("Cannot create directory " + directory.getPath());
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        fileFixSessionStoreContext.clear();
    }

    public static class FileStoreFactoryImpl implements FixMessagesStoreSettings.FixMessagesStoreFactory<FileMessageStoreSettings> {

        @Override
        public FixMessagesStore newInstance(FileMessageStoreSettings settings) {
            return new FileMessagesStore(settings);
        }

        @Override
        public Class<FileMessageStoreSettings> getSettingsClass() {
            return FileMessageStoreSettings.class;
        }
    }
}