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
package org.lolaf.staffix.fix.orchestra;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class OrchestraRepositoryTest {

    private static final Path FIXTURE = Path.of("src/test/resources/orchestration-fixture.xml");

    private static InputStream zipOf(String... entryNames) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String entryName : entryNames) {
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(Files.readAllBytes(FIXTURE));
                zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }

    @Test
    public void testXmlStream() throws Exception {
        try (InputStream in = Files.newInputStream(FIXTURE)) {
            assertFalse(OrchestraRepository.load(in, "fixture.xml").elementsByTagName("fixr:message").isEmpty());
        }
    }

    @Test
    public void testZipStreamWithOneXmlEntry() throws Exception {
        OrchestraRepository repository = OrchestraRepository.load(zipOf("readme.txt", "orchestration.xml"), "fixture.zip");

        assertFalse(repository.elementsByTagName("fixr:message").isEmpty());
    }

    @Test
    public void testZipStreamWithTwoXmlEntriesIsAmbiguous() {
        IOException thrown = assertThrows(IOException.class,
                () -> OrchestraRepository.load(zipOf("a.xml", "b.xml"), "fixture.zip"));

        assertTrue(thrown.getMessage().contains("a.xml and b.xml"));
    }

    @Test
    public void testZipStreamWithoutXmlEntry() {
        IOException thrown = assertThrows(IOException.class,
                () -> OrchestraRepository.load(zipOf("readme.txt"), "fixture.zip"));

        assertTrue(thrown.getMessage().contains("holds no XML entry"));
    }
}
