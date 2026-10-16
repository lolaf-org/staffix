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

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Describes each component of the module in {@code META-INF/staffix/components/}, packaged in its jar: a JSON Schema of
 * its settings, holding the {@code <settings class>.guide.yaml} written beside the class, which the jar then leaves out.
 */
@Mojo(name = "generate-component-metadata", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class GenerateComponentMetadataMojo extends AbstractMojo {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File classesDirectory;

    /**
     * Where the javadoc of classes from other modules is looked for, under every {@code src/main/java}.
     */
    @Parameter(defaultValue = "${maven.multiModuleProjectDirectory}", required = true)
    private File buildRoot;

    @Override
    public void execute() throws MojoExecutionException {
        try (URLClassLoader loader = new URLClassLoader(compileClasspath(), ClassLoader.getPlatformClassLoader())) {
            List<Component> components = ComponentScanner.scan(classesDirectory.toPath(), loader);
            if (components.isEmpty()) {
                getLog().info("No staffix component in " + classesDirectory);
                return;
            }
            List<Path> moduleSourceRoots = project.getCompileSourceRoots().stream().map(Path::of)
                    .collect(Collectors.toList());
            ComponentSchemaGenerator generator =
                    new ComponentSchemaGenerator(SourceIndex.of(moduleSourceRoots, buildRoot.toPath()));
            Path outputDirectory = classesDirectory.toPath().resolve("META-INF/staffix/components");
            Files.createDirectories(outputDirectory);
            for (Component component : components) {
                write(generator, component, outputDirectory);
            }
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("Unable to describe the components of " + classesDirectory, e);
        }
    }

    private void write(ComponentSchemaGenerator generator, Component component, Path outputDirectory)
            throws IOException {
        Class<?> type = component.getSettingsClass();
        Path schema = outputDirectory.resolve(type.getName() + ".v" + ComponentSchemaGenerator.FORMAT_VERSION
                + ".schema.json");
        ObjectNode description = generator.generate(component, project.getGroupId(), project.getArtifactId());
        Path guide = classesDirectory.toPath().resolve(type.getName().replace('.', '/') + ".guide.yaml");
        if (Files.isRegularFile(guide)) {
            ((ObjectNode) description.get("x-staffix")).set("guide", ComponentGuide.read(guide));
            Files.delete(guide);
        } else {
            getLog().warn("No guide for " + type.getName() + ": write " + type.getSimpleName()
                    + ".guide.yaml in src/main/resources beside it");
        }
        Files.write(schema, (JSON.writeValueAsString(description) + "\n").getBytes(StandardCharsets.UTF_8));
        getLog().info("Described " + component.getKind() + " " + type.getName());
    }

    private URL[] compileClasspath() throws MojoExecutionException {
        try {
            List<String> elements = project.getCompileClasspathElements();
            URL[] urls = new URL[elements.size()];
            for (int i = 0; i < urls.length; i++) {
                urls[i] = new File(elements.get(i)).toURI().toURL();
            }
            return urls;
        } catch (DependencyResolutionRequiredException | MalformedURLException e) {
            throw new MojoExecutionException("Unable to resolve the compile classpath", e);
        }
    }
}
