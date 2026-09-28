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
package org.lolaf.staffix.api.utils;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestResourceLocation {

    private static final File BASEDIR = new File(".").getAbsoluteFile();
    private static final File RESOURCE = new File(BASEDIR, "src/test/resources/utils/resource-location.xml");
    private static final ClassLoader CLASS_LOADER = TestResourceLocation.class.getClassLoader();

    private static byte[] read(String location) throws Exception {
        try (InputStream in = ResourceLocation.open(location, BASEDIR, CLASS_LOADER)) {
            return in.readAllBytes();
        }
    }

    @Test
    void opensClasspathResource() throws Exception {
        assertThat(read("classpath:utils/resource-location.xml")).isEqualTo(Files.readAllBytes(RESOURCE.toPath()));
    }

    @Test
    void opensFileRelativeToBasedir() throws Exception {
        assertThat(read("src/test/resources/utils/resource-location.xml")).isEqualTo(Files.readAllBytes(RESOURCE.toPath()));
    }

    @Test
    void opensAbsoluteFile() throws Exception {
        assertThat(read(RESOURCE.getAbsolutePath())).isEqualTo(Files.readAllBytes(RESOURCE.toPath()));
    }

    @Test
    void missingClasspathResourceNamesPluginDependencies() {
        assertThatThrownBy(() -> read("classpath:NOPE.xml"))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("NOPE.xml")
                .hasMessageContaining("<dependencies>");
    }

    @Test
    void missingFileFails() {
        assertThatThrownBy(() -> read("src/test/resources/utils/NOPE.xml")).isInstanceOf(FileNotFoundException.class);
    }
}
