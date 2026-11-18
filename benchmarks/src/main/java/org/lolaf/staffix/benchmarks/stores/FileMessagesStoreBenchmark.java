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
package org.lolaf.staffix.benchmarks.stores;

import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.messages.file.FileMessageStoreSettings;
import org.lolaf.staffix.stores.messages.file.FileMessagesStore;
import org.openjdk.jmh.annotations.Param;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

/**
 * The file store by how it syncs, on tmpfs and on the disk holding the working directory.
 */
public class FileMessagesStoreBenchmark extends AbstractMessagesStoreBenchmark {

    /**
     * {@code ONE_FLUSH} stands for a store syncing once per call: no {@code syncWrites}, then one fdatasync of the file,
     * which writes back the mapping's dirty pages, messages and header alike.
     */
    public enum Sync {
        TWO_FLUSHES,
        ONE_FLUSH,
        NONE
    }

    @Param({"SHM", "DISK"})
    FileSystem fileSystem;
    @Param({"TWO_FLUSHES", "ONE_FLUSH", "NONE"})
    Sync sync;

    private FileChannel sessionFile;

    @Override
    protected FixMessagesStore newStore() {
        return new FileMessagesStore.FileStoreFactoryImpl().newInstance(FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeDirectory().toString())
                .syncWrites(sync == Sync.TWO_FLUSHES)
                .build());
    }

    @Override
    protected void afterStart() throws IOException {
        if (sync == Sync.ONE_FLUSH) {
            sessionFile = FileChannel.open(sessionFileIn(storeDirectory()), StandardOpenOption.WRITE);
        }
    }

    @Override
    protected void afterStore() throws IOException {
        if (sessionFile != null) {
            sessionFile.force(false);
        }
    }

    @Override
    protected void cleanUp() throws IOException {
        if (sessionFile != null) {
            sessionFile.close();
            sessionFile = null;
        }
        deleteDirectory(storeDirectory());
    }

    private Path storeDirectory() {
        return fileSystem.directory("file-store");
    }

    private static Path sessionFileIn(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.toString().endsWith(".bin")).findFirst().orElseThrow();
        }
    }
}
