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
import org.junit.jupiter.api.io.TempDir;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.StoreSettings;
import org.lolaf.staffix.buildtools.metadata.fixture.generate.WrapperSettings;
import org.lolaf.staffix.buildtools.metadata.fixture.spring.FixtureStoreProps;
import org.lolaf.staffix.buildtools.metadata.fixture.spring.UnlinkedProps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestMetadataChecks {

    private static final Component WRAPPER = new Component(WrapperSettings.class, "messages-store");

    @TempDir
    Path classesDirectory;

    @Test
    void aModuleWithEveryGuideAndLinkKeepsTheRules() throws IOException {
        writeGuideBeside(WrapperSettings.class);
        writeGuideBeside(FixtureStoreProps.class);

        assertThat(MetadataChecks.problems(classesDirectory, List.of(WRAPPER),
                List.of(WrapperSettings.class, FixtureStoreProps.class))).isEmpty();
    }

    @Test
    void reportsAMissingGuideAnUnlinkedPropertiesClassAndAnOrphanedGuide() throws IOException {
        writeGuideBeside(StoreSettings.class);

        assertThat(MetadataChecks.problems(classesDirectory, List.of(WRAPPER),
                List.of(WrapperSettings.class, StoreSettings.class, UnlinkedProps.class))).containsExactly(
                "No guide for " + WrapperSettings.class.getName() + ": write WrapperSettings.guide.yaml in"
                        + " src/main/resources beside it",
                UnlinkedProps.class.getName() + " has @ConfigurationProperties but no @ConfiguresComponent",
                StoreSettings.class.getName() + ".guide.yaml belongs to no component nor @ConfiguresComponent class");
    }

    @Test
    void aMissingDirectoryHasNoOrphanedGuide() throws IOException {
        assertThat(MetadataChecks.problems(classesDirectory.resolve("missing"), List.of(), List.of())).isEmpty();
    }

    private void writeGuideBeside(Class<?> type) throws IOException {
        Path guide = ComponentGuide.besideClass(type, classesDirectory);
        Files.createDirectories(guide.getParent());
        Files.writeString(guide, "summary: A fixture.\n");
    }
}
