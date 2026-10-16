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
package org.lolaf.staffix.buildtools.metadata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestComponentScanner {

    private static final Path TEST_CLASSES = Path.of("target/test-classes");

    @Test
    void findsConcreteClassesOfAnnotatedKindsAndSkipsUnloadableOnes() throws IOException {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{TEST_CLASSES.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            List<Component> components = ComponentScanner.scan(TEST_CLASSES, loader);

            assertThat(components.stream().map(component -> component.getSettingsClass().getName() + " "
                    + component.getKind()).collect(Collectors.toList()))
                    .containsExactly(
                            "org.lolaf.staffix.buildtools.metadata.fixture.ConcreteSettings fixture-kind",
                            "org.lolaf.staffix.buildtools.metadata.fixture.Holder$NestedSettings fixture-kind");
        }
    }

    @Test
    void aMissingDirectoryHasNoComponent() throws IOException {
        assertThat(ComponentScanner.scan(Path.of("target/missing"), getClass().getClassLoader())).isEmpty();
    }
}
