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

import org.lolaf.staffix.buildtools.javadoc.SettingsJavadoc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The javadoc of the staffix classes a schema describes, read from the first source root holding each.
 */
public class SourceIndex {

    private static final String STAFFIX_PACKAGE = "org.lolaf.staffix.";

    private final List<Path> sourceRoots;
    private final SettingsJavadoc javadoc = new SettingsJavadoc();
    private final Set<String> read = new HashSet<>();

    public SourceIndex(List<Path> sourceRoots) {
        this.sourceRoots = List.copyOf(sourceRoots);
    }

    /**
     * The module's own source roots first, then every {@code src/main/java} of the build, so a class inherited from
     * another module is described whether that module comes from the reactor or from a repository jar.
     */
    public static SourceIndex of(List<Path> moduleSourceRoots, Path buildRoot) throws IOException {
        List<Path> roots = new ArrayList<>(moduleSourceRoots);
        Files.walkFileTree(buildRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = directory.getFileName() == null ? "" : directory.getFileName().toString();
                if (name.equals("target") || name.equals("node_modules") || name.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (name.equals("src")) {
                    Path mainJava = directory.resolve("main").resolve("java");
                    if (Files.isDirectory(mainJava) && !roots.contains(mainJava)) {
                        roots.add(mainJava);
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new SourceIndex(roots);
    }

    public String describe(Class<?> type) {
        return read(type).describe(type.getName().replace('$', '.'));
    }

    public String describe(Class<?> type, String field) {
        return read(type).describe(type.getName().replace('$', '.'), field);
    }

    private SettingsJavadoc read(Class<?> type) {
        Class<?> topLevel = type;
        while (topLevel.getEnclosingClass() != null) {
            topLevel = topLevel.getEnclosingClass();
        }
        String name = topLevel.getName();
        if (name.startsWith(STAFFIX_PACKAGE) && read.add(name)) {
            String relative = name.replace('.', '/') + ".java";
            Path source = sourceRoots.stream().map(root -> root.resolve(relative)).filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No source for " + name + " in " + sourceRoots));
            try {
                javadoc.read(source);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return javadoc;
    }
}
