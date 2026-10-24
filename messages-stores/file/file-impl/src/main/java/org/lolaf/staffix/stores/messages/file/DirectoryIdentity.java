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

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * A store directory's identity, kept in a file inside it, so two engines writing one directory, through a network
 * share for instance, read the same one.
 */
@UtilityClass
class DirectoryIdentity {

    static final String FILE_NAME = "staffix-storage-identity";

    /**
     * Creates the file the first time. It is written aside then linked in place, which fails if it exists, so two
     * engines starting together agree on one id and neither reads it half written.
     */
    static String of(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Path written = Files.createTempFile(directory, FILE_NAME, ".tmp");
            try {
                Files.write(written, UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
                Files.createLink(file, written);
            } catch (FileAlreadyExistsException e) {
                // the other engine sharing the directory made it first
            } finally {
                Files.deleteIfExists(written);
            }
        }
        String identity = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim();
        if (identity.isEmpty()) {
            throw new IOException(file + " is empty");
        }
        return "file:" + identity;
    }
}
