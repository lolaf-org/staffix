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

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Gathers the metadata of every staffix module the project depends on into its own classes, unchanged, with
 * {@value ComponentsCatalogue#INDEX} indexing them, so a skill resolves one artifact to learn every component.
 */
@Mojo(name = "generate-components-catalogue", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class GenerateComponentsCatalogueMojo extends AbstractMojo {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final String PLUGIN = "staffix-component-metadata-maven-plugin";
    private static final String DESCRIBE_GOAL = "generate-component-metadata";
    private static final String SPRING_METADATA = "META-INF/spring-configuration-metadata.json";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File classesDirectory;

    /**
     * Directories whose markdown and properties files may only name staffix properties that a module binds; links are
     * not followed.
     */
    @Parameter
    private List<File> documentation = List.of();

    /**
     * Keys the documentation writes like a staffix property that are not one, such as a Maven property in prose.
     */
    @Parameter
    private List<String> ignoredKeys = List.of();

    /**
     * Where the documentation's paths are shown from.
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", required = true)
    private File buildRoot;

    /**
     * The dependency whose Spring Boot metadata declares the starter's own properties; it holds no component.
     */
    @Parameter(defaultValue = "staffix-spring-boot-starter", required = true)
    private String starter;

    /**
     * A skill reads the whole index before choosing any component, so it must stay small enough to read every time.
     */
    @Parameter(property = "staffix.catalogue.indexBudget", defaultValue = "25600")
    private int indexBudget;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            Map<String, byte[]> files = new TreeMap<>();
            Map<String, Integer> contributed = new TreeMap<>();
            for (Artifact artifact : project.getArtifacts()) {
                if (artifact.getGroupId().equals(project.getGroupId())) {
                    int before = files.size();
                    collect(artifact, files);
                    contributed.put(artifact.getArtifactId(), files.size() - before);
                }
            }
            Set<String> dependencies = dependencies();
            dependencies.remove(starter);
            List<String> problems = ComponentsCatalogue.coverageProblems(describingModules(), dependencies, contributed);
            if (!problems.isEmpty()) {
                throw new MojoFailureException("The catalogue misses components:\n- " + String.join("\n- ", problems));
            }
            Map<String, ObjectNode> parsed = new TreeMap<>();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                parsed.put(file.getKey(), (ObjectNode) JSON.readTree(file.getValue()));
                write(file.getKey(), file.getValue());
            }
            ObjectNode index = ComponentsCatalogue.index(project.getVersion(), parsed);
            byte[] indexContent = (JSON.writeValueAsString(index) + "\n").getBytes(StandardCharsets.UTF_8);
            if (indexContent.length > indexBudget) {
                throw new MojoFailureException("The catalogue index takes " + indexContent.length
                        + " bytes, over its budget of " + indexBudget + ": shorten the guide summaries or raise it");
            }
            checkDocumentation(parsed);
            write(ComponentsCatalogue.INDEX, indexContent);
            getLog().info("Catalogued " + index.get("components").size() + " components in " + indexContent.length
                    + " bytes of index");
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("Unable to catalogue the staffix components", e);
        }
    }

    private void checkDocumentation(Map<String, ObjectNode> files) throws IOException, MojoFailureException {
        List<ObjectNode> springFiles = files.entrySet().stream()
                .filter(file -> file.getKey().startsWith(ComponentsCatalogue.SPRING))
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        DocumentedPropertyKeys keys = DocumentedPropertyKeys.of(springFiles, starterProperties(), ignoredKeys);
        Path root = buildRoot.toPath();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (File directory : documentation) {
            for (Path document : documents(directory.toPath())) {
                problems.addAll(keys.problems(root, root.relativize(document)));
                checked++;
            }
        }
        getLog().info("Checked the staffix properties named in " + checked + " documents");
        if (!problems.isEmpty()) {
            throw new MojoFailureException("The documentation names properties no module binds:\n- "
                    + String.join("\n- ", problems));
        }
    }

    private static List<Path> documents(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IOException("No documentation directory " + directory);
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".md") || file.toString().endsWith(".properties"))
                    .filter(file -> !file.toString().contains(File.separator + "target" + File.separator))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private List<String> starterProperties() throws IOException {
        Artifact starterArtifact = project.getArtifacts().stream()
                .filter(artifact -> artifact.getArtifactId().equals(starter))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The catalogue must depend on " + starter
                        + ", whose properties the documentation names"));
        byte[] metadata = read(starterArtifact.getFile(), SPRING_METADATA);
        List<String> names = new ArrayList<>();
        JSON.readTree(metadata).path("properties").forEach(property -> names.add(property.path("name").asText()));
        return names;
    }

    private static byte[] read(File artifact, String entry) throws IOException {
        if (artifact.isDirectory()) {
            return Files.readAllBytes(artifact.toPath().resolve(entry));
        }
        try (ZipFile zip = new ZipFile(artifact)) {
            ZipEntry zipEntry = zip.getEntry(entry);
            if (zipEntry == null) {
                throw new IOException(artifact + " has no " + entry);
            }
            try (InputStream content = zip.getInputStream(zipEntry)) {
                return content.readAllBytes();
            }
        }
    }

    private Set<String> describingModules() {
        return session.getAllProjects().stream()
                .filter(module -> module != project && module.getGroupId().equals(project.getGroupId()))
                .filter(GenerateComponentsCatalogueMojo::describesComponents)
                .map(MavenProject::getArtifactId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean describesComponents(MavenProject module) {
        return module.getBuildPlugins().stream()
                .filter(plugin -> plugin.getArtifactId().equals(PLUGIN))
                .flatMap(plugin -> plugin.getExecutions().stream())
                .anyMatch(execution -> execution.getGoals().contains(DESCRIBE_GOAL)
                        && !"none".equals(execution.getPhase()));
    }

    private Set<String> dependencies() {
        return project.getDependencies().stream()
                .filter(dependency -> dependency.getGroupId().equals(project.getGroupId()))
                .map(Dependency::getArtifactId)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Inside a reactor build that stops before {@code package}, a module's artifact is its classes directory.
     */
    private static void collect(Artifact artifact, Map<String, byte[]> files) throws IOException {
        File source = artifact.getFile();
        if (source == null) {
            return;
        }
        if (source.isDirectory()) {
            collectFromDirectory(artifact, source.toPath().resolve(ComponentsCatalogue.ROOT), files);
        } else {
            collectFromJar(artifact, source, files);
        }
    }

    private static void collectFromDirectory(Artifact artifact, Path root, Map<String, byte[]> files)
            throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        }
        for (Path path : paths) {
            add(artifact, root.relativize(path).toString().replace(File.separatorChar, '/'), Files.readAllBytes(path),
                    files);
        }
    }

    private static void collectFromJar(Artifact artifact, File jar, Map<String, byte[]> files) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(ComponentsCatalogue.ROOT)) {
                    try (InputStream content = zip.getInputStream(entry)) {
                        add(artifact, entry.getName().substring(ComponentsCatalogue.ROOT.length()),
                                content.readAllBytes(), files);
                    }
                }
            }
        }
    }

    private static void add(Artifact artifact, String path, byte[] content, Map<String, byte[]> files) {
        if (files.putIfAbsent(path, content) != null) {
            throw new IllegalStateException(artifact + " holds " + ComponentsCatalogue.ROOT + path
                    + ", which another staffix module already holds");
        }
    }

    private void write(String path, byte[] content) throws IOException {
        Path file = classesDirectory.toPath().resolve(ComponentsCatalogue.ROOT + path);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }
}
