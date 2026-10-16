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
 * A Spring Boot module gets {@code META-INF/staffix/spring/} instead, from its properties classes.
 */
@Mojo(name = "generate-component-metadata", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class GenerateComponentMetadataMojo extends AbstractMojo {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final String COMPONENTS = "META-INF/staffix/components/";
    private static final String SPRING = "META-INF/staffix/spring/";
    private static final String SCHEMA_SUFFIX = ".v" + ComponentSchemaGenerator.FORMAT_VERSION + ".schema.json";

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
            List<Class<?>> springProperties = ComponentScanner.publicConcreteClasses(classesDirectory.toPath(), loader)
                    .stream().filter(type -> SpringComponentGenerator.configuredComponent(type) != null)
                    .collect(Collectors.toList());
            if (components.isEmpty() && springProperties.isEmpty()) {
                getLog().info("No staffix component in " + classesDirectory);
                return;
            }
            List<Path> moduleSourceRoots = project.getCompileSourceRoots().stream().map(Path::of)
                    .collect(Collectors.toList());
            SourceIndex sources = SourceIndex.of(moduleSourceRoots, buildRoot.toPath());
            ComponentSchemaGenerator generator = new ComponentSchemaGenerator(sources);
            for (Component component : components) {
                writeComponent(generator, component);
            }
            SpringComponentGenerator springGenerator = new SpringComponentGenerator(sources);
            for (Class<?> properties : springProperties) {
                writeSpringComponent(springGenerator, properties, loader);
            }
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException("Unable to describe the components of " + classesDirectory, e);
        }
    }

    private void writeComponent(ComponentSchemaGenerator generator, Component component) throws IOException {
        Class<?> type = component.getSettingsClass();
        ObjectNode description = generator.generate(component, project.getGroupId(), project.getArtifactId());
        ComponentGuide.readBeside(type, classesDirectory.toPath())
                .ifPresentOrElse(guide -> ((ObjectNode) description.get("x-staffix")).set("guide", guide),
                        () -> getLog().warn("No guide for " + type.getName() + ": write " + type.getSimpleName()
                                + ".guide.yaml in src/main/resources beside it"));
        write(COMPONENTS + type.getName() + SCHEMA_SUFFIX, description);
        getLog().info("Described " + component.getKind() + " " + type.getName());
    }

    private void writeSpringComponent(SpringComponentGenerator generator, Class<?> properties, ClassLoader loader)
            throws IOException {
        Class<?> settings = SpringComponentGenerator.configuredComponent(properties);
        String componentSchema = COMPONENTS + settings.getName() + SCHEMA_SUFFIX;
        URL schema = loader.getResource(componentSchema);
        if (schema == null) {
            throw new IllegalStateException(properties.getName() + " configures " + settings.getName()
                    + ", whose jar has no " + componentSchema + ": build its module with this plugin");
        }
        ObjectNode description = generator.generate(properties, (ObjectNode) JSON.readTree(schema),
                project.getGroupId(), project.getArtifactId());
        ComponentGuide.readBeside(properties, classesDirectory.toPath()).ifPresent(guide -> description.set("guide", guide));
        write(SPRING + settings.getName() + ".v" + ComponentSchemaGenerator.FORMAT_VERSION + ".json", description);
        getLog().info("Described the Spring Boot properties of " + settings.getName());
    }

    private void write(String resource, ObjectNode content) throws IOException {
        Path file = classesDirectory.toPath().resolve(resource);
        Files.createDirectories(file.getParent());
        Files.write(file, (JSON.writeValueAsString(content) + "\n").getBytes(StandardCharsets.UTF_8));
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
