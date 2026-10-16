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

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * Describes each component of the module in {@code META-INF/staffix/components/}, packaged in its jar.
 */
@Mojo(name = "generate-component-metadata", defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class GenerateComponentMetadataMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File classesDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        try (URLClassLoader loader = new URLClassLoader(compileClasspath(), ClassLoader.getPlatformClassLoader())) {
            List<Component> components = ComponentScanner.scan(classesDirectory.toPath(), loader);
            if (components.isEmpty()) {
                getLog().info("No staffix component in " + classesDirectory);
            }
            components.forEach(component -> getLog().info("Found " + component.getKind() + " "
                    + component.getSettingsClass().getName()));
        } catch (IOException e) {
            throw new MojoExecutionException("Unable to scan " + classesDirectory, e);
        }
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
