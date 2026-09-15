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

import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.stores.messages.testkit.AbstractMessagesStoreTest;

import java.io.File;
import java.io.IOException;

class TestFileMessagesStore extends AbstractMessagesStoreTest {

    File storeFile;

    @Override
    protected FixMessagesStore createStore() {
        storeFile = new File("./target/store/" + System.currentTimeMillis());
        FileMessageStoreSettings settings = FileMessageStoreSettings.builder()
                .storageDirectoryPath(storeFile.getPath())
                .blockSize(32)
                .blocksCount(16)
                .build();
        return new FileMessagesStore(settings);
    }

    @Override
    protected void tearDown() throws Exception {
        deleteDir(storeFile);
        super.tearDown();
    }

    void deleteDir(File f) throws IOException {
        if (f.isDirectory()) {
            for (File c : f.listFiles()) {
                deleteDir(c);
            }
        }
        if (!f.delete()) {
            throw new IOException("Failed to delete file: " + f);
        }
    }
}