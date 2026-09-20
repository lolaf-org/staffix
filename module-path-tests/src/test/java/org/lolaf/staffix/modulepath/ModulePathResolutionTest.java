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
package org.lolaf.staffix.modulepath;

import org.junit.jupiter.api.Test;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Every published staffix jar must be usable on the module path, not only the classpath.
 *
 * <p>Nothing else in the build can see these failures. Every other test runs on the classpath, where
 * the one-package-one-module rule does not exist and a jar's module name is never read, so a split
 * package or a name derived from a file name stays invisible until a consumer with a
 * {@code module-info} — or anyone running {@code jlink} — cannot use the library at all.
 *
 * <p>It reads {@code target/module-path}, filled by {@code maven-dependency-plugin} at
 * {@code process-test-classes}, rather than the test classpath: on a bare {@code mvn test} a reactor
 * dependency resolves to an exploded {@code target/classes} directory, which {@link ModuleFinder} would
 * name {@code classes}. Run it through {@code mvn install}, so the jars exist.
 */
class ModulePathResolutionTest {

    private static final Path MODULE_PATH =
            Paths.get(System.getProperty("staffix.module.path", "target/module-path"));

    /**
     * The published non-pom modules, each of which names itself with an {@code Automatic-Module-Name}.
     * The count is the gate: publishing a new module means raising this too, and the alternative is a
     * module whose name nobody checked. Modules held back from the release bundle keep the parent's
     * sentinel name and are deliberately not counted here.
     */
    private static final int PUBLISHED_MODULES = 66;

    /**
     * {@code PooledBytesString} extends protobuf's {@code ByteString} to serialize OTLP log records
     * without allocating per message, and only a class inside {@code com.google.protobuf} reaches the
     * package-private helpers that contract needs. The split with {@code protobuf-java} is the price,
     * and it costs this one jar the module path.
     */
    private static final Map<String, String> ALLOWED_FOREIGN_PACKAGE =
            Map.of("org.lolaf.staffix.stores.loggers.otlp", "com.google.protobuf");

    private static Set<ModuleReference> modules() {
        assertThat(MODULE_PATH)
                .as("the module path directory should have been filled at process-test-classes")
                .exists();
        Set<ModuleReference> found = ModuleFinder.of(MODULE_PATH).findAll();
        // an empty module path resolves perfectly and proves nothing, so make that a failure
        assertThat(found)
                .as("no staffix jars found in %s, so this test would pass vacuously", MODULE_PATH)
                .hasSize(PUBLISHED_MODULES);
        return found;
    }

    /**
     * The readable half. {@link Configuration#resolve} names one offending pair and stops, so work the
     * whole set out first and report every clash at once.
     */
    @Test
    void everyPackageBelongsToExactlyOneModule() {
        Map<String, Set<String>> owners = new TreeMap<>();
        for (ModuleReference module : modules()) {
            for (String pkg : module.descriptor().packages()) {
                owners.computeIfAbsent(pkg, p -> new TreeSet<>()).add(module.descriptor().name());
            }
        }

        Map<String, Set<String>> split = owners.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));

        assertThat(split)
                .as("a package carried by more than one jar cannot be used on the module path")
                .isEmpty();
    }

    /**
     * The real half: the check the JVM itself makes when it builds the boot layer.
     */
    @Test
    void theWholeSetResolvesAsModules() {
        ModuleFinder finder = ModuleFinder.of(MODULE_PATH);
        Set<String> roots = modules().stream()
                .map(m -> m.descriptor().name())
                .collect(Collectors.toSet());

        assertThatCode(() -> Configuration.resolve(
                finder, List.of(ModuleLayer.boot().configuration()), ModuleFinder.of(), roots))
                .doesNotThrowAnyException();
    }

    /**
     * Every jar must carry an explicit {@code Automatic-Module-Name}. Left to the file name,
     * {@code staffix-fix-42} and {@code staffix-fixt-11} derive a digit-leading component that is not a
     * valid identifier and {@link ModuleFinder} refuses the jar outright; the rest would derive a name
     * that silently changes the day an artifact is renamed.
     */
    @Test
    void everyJarDeclaresItsOwnModuleName() {
        assertThat(modules()).extracting(m -> m.descriptor().name())
                .as("a module still named after its jar, or one that forgot the property")
                .allSatisfy(name -> assertThat(name).startsWith("org.lolaf.staffix").doesNotContain("NOT.SET"));
    }

    /**
     * The naming rule itself: a module is named after the package it owns, so a consumer's
     * {@code requires} and its {@code import} agree. This is what catches a class dropped into someone
     * else's package, which {@link #everyPackageBelongsToExactlyOneModule} cannot see while the other
     * owner is a third-party jar that never reaches this module path.
     */
    @Test
    void everyPackageSitsUnderItsModuleName() {
        Map<String, Set<String>> strays = new TreeMap<>();
        for (ModuleReference module : modules()) {
            String name = module.descriptor().name();
            Set<String> outside = module.descriptor().packages().stream()
                    .filter(pkg -> !pkg.equals(name) && !pkg.startsWith(name + "."))
                    .filter(pkg -> !pkg.equals(ALLOWED_FOREIGN_PACKAGE.get(name)))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!outside.isEmpty()) {
                strays.put(name, outside);
            }
        }

        assertThat(strays)
                .as("a published package outside the module named for it splits with whoever owns it")
                .isEmpty();
    }

    /**
     * Guards the reading of the directory itself: a typo in the plugin configuration would leave it
     * empty, and every test above would then pass without checking anything.
     */
    @Test
    void theModulePathHoldsTheStaffixJars() throws Exception {
        try (var entries = Files.list(MODULE_PATH)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .allMatch(name -> name.startsWith("staffix-") && name.endsWith(".jar"));
        }
        assertThat(modules()).extracting(m -> m.descriptor().name())
                .contains("org.lolaf.staffix.api", "org.lolaf.staffix.codec", "org.lolaf.staffix.fix42");
    }
}
