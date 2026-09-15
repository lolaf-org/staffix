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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.w3c.dom.Element;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lists what an orchestration contains, version by version: where each one starts, which extension packs belong to
 * it, how much it brought, and whether it has a base release at all.
 * <p>
 * The point is to make a cut choosable. {@code upToVersion} and {@code upToExtensionPack} only mean something
 * against the ladder of the file in hand, and that ladder moves every time a new extension pack is published. It
 * also shows the property the cut semantics rest on - that extension pack ranges are contiguous and do not overlap
 * between versions - rather than leaving it as folklore.
 * <p>
 * Reads a file and prints, so it wants no project:
 * <pre>
 * mvn staffix-fix-orchestra-dictionary-generator:versions \
 *     -Dorchestration=fix-packages/fix-orchestra/src/main/resources/fix-orchestra-latest.zip
 * </pre>
 */
@Mojo(name = "versions", requiresProject = false)
public class VersionsMojo extends AbstractMojo {

    /**
     * The orchestration to inspect, either the XML or a zip holding it.
     */
    @Parameter(property = "orchestration", required = true)
    private File orchestration;

    /**
     * Where to write the listing. Left out, it goes to the build log.
     */
    @Parameter(property = "outputFile", defaultValue = "${project.build.directory}/generated-resources/fix-versions.txt")
    private File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            OrchestraRepository repository = OrchestraRepository.load(orchestration);
            List<String> lines = describe(repository);
            if (outputFile != null) {
                Files.createDirectories(outputFile.toPath().toAbsolutePath().getParent());
                Files.write(outputFile.toPath(), String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8));
                getLog().info("Wrote the version listing of " + orchestration + " to " + outputFile);
                return;
            }
            lines.forEach(getLog()::info);
        } catch (Exception ex) {
            throw new MojoExecutionException("Failed to list the versions of " + orchestration, ex);
        }
    }

    private List<String> describe(OrchestraRepository repository) {
        Map<OrchestraVersion, VersionSummary> ladder = new TreeMap<>();
        for (String elementName : OrchestraRepository.VERSIONED_ELEMENTS) {
            if (elementName.endsWith("Ref")) {
                // a reference has a history of its own, but it is the definitions that make up a version's content
                continue;
            }
            for (Element element : repository.elementsByTagName(elementName)) {
                OrchestraVersion version = OrchestraRepository.version(element, "added");
                if (version == null) {
                    continue;
                }
                ladder.computeIfAbsent(version, v -> new VersionSummary())
                        .add(elementName, OrchestraRepository.extensionPack(element, "addedEP"));
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add(repository.getRepositoryName() + " " + repository.getRepositoryVersion() + " - " + orchestration);
        lines.add(String.format("%-12s %12s %10s %10s  %s", "version", "EP range", "elements", "base", "cut"));
        for (Map.Entry<OrchestraVersion, VersionSummary> entry : ladder.entrySet()) {
            OrchestraVersion version = entry.getKey();
            VersionSummary summary = entry.getValue();
            lines.add(String.format("%-12s %12s %10d %10d  %s",
                    version,
                    summary.extensionPackRange(),
                    summary.total,
                    summary.base,
                    summary.base > 0
                            ? "upToVersion=" + version + " [upToExtensionPack=0 for the base release]"
                            : "upToVersion=" + version + " [no base release, an extension pack cut is the only one]"));
            if (!version.isKnown()) {
                getLog().warn("Version " + version + " is not one this plugin can place on the ladder, so it sorts "
                        + "last and a cut cannot include it deliberately");
            }
        }
        for (Map.Entry<OrchestraVersion, VersionSummary> entry : ladder.entrySet()) {
            lines.add("  " + entry.getKey() + ": " + entry.getValue().describeElements());
        }
        return lines;
    }

    private static final class VersionSummary {

        private final Map<String, Integer> byElement = new TreeMap<>();
        private int total;
        private int base;
        private Integer firstExtensionPack;
        private Integer lastExtensionPack;

        void add(String elementName, Integer extensionPack) {
            byElement.merge(elementName.replace("fixr:", ""), 1, Integer::sum);
            total++;
            if (extensionPack == null) {
                base++;
                return;
            }
            firstExtensionPack = firstExtensionPack == null ? extensionPack : Math.min(firstExtensionPack, extensionPack);
            lastExtensionPack = lastExtensionPack == null ? extensionPack : Math.max(lastExtensionPack, extensionPack);
        }

        String extensionPackRange() {
            if (firstExtensionPack == null) {
                return "-";
            }
            return firstExtensionPack.equals(lastExtensionPack)
                    ? String.valueOf(firstExtensionPack)
                    : firstExtensionPack + "-" + lastExtensionPack;
        }

        String describeElements() {
            List<String> parts = new ArrayList<>(byElement.size());
            byElement.forEach((name, count) -> parts.add(count + " " + name));
            return String.join(", ", parts);
        }
    }
}
