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
package org.lolaf.staffix.generator;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.utils.io.FileUtils;


import java.io.File;


/**
 * The Maven goal that runs {@link CodeGenerator} over a dictionary.
 */
@Mojo(name = "code-generator")
@Execute(goal = "code-generator", phase = LifecyclePhase.GENERATE_SOURCES)
public class CodeGeneratorMojo extends AbstractMojo {

    @Parameter(name = "dictionaryFile", required = true)
    private File dictionaryFile;

    @Parameter(property = "sourcesOutputDirectory", required = true)
    private File sourcesOutputDirectory;

    @Parameter(property = "resourcesOutputDirectory", defaultValue = "${project.build.directory}/classes")
    private File resourcesOutputDirectory;

    @Parameter(property = "packageName", required = true)
    private String packageName;

    @Parameter(property = "dictionaryId", required = true)
    private String dictionaryId;

    @Parameter(property = "addFIXEngineAndAppInfoFields", defaultValue = "true")
    private boolean addFIXEngineAndAppInfoFields;

    /**
     * Registers the generated sources as a test source root instead of a compile one, for a dictionary
     * that exists only to exercise the encoders. Without it the fixtures compile into
     * {@code target/classes} and ship in the module's published jar.
     */
    @Parameter(property = "testSources", defaultValue = "false")
    private boolean testSources;

    @Component
    private MavenProject project;

    public void execute() throws MojoExecutionException {
        if (!sourcesOutputDirectory.exists()) {
            FileUtils.mkdir(sourcesOutputDirectory.getAbsolutePath());
        }
        if (!resourcesOutputDirectory.exists()) {
            FileUtils.mkdir(resourcesOutputDirectory.getAbsolutePath());
        }
        try {
            getLog().info("Processing " + dictionaryFile);
            CodeGenerator.process(packageName, dictionaryFile, sourcesOutputDirectory, resourcesOutputDirectory, dictionaryId, getLog(), addFIXEngineAndAppInfoFields);

        } catch (Throwable t) {
            throw new MojoExecutionException("Code generator execution failed", t);
        }

        if (project != null) {
            // The generated sources, and only those. resourcesOutputDirectory is target/classes - the build's
            // *output*, where this mojo writes the fieldsInfo files and the SPI descriptors so that they are
            // packaged without a resource copying step. It was once added here as well, which compiled nothing
            // extra (there is no .java under it) and went unnoticed until maven-source-plugin was configured:
            // the source plugin packages every compile source root, so every generated FIX package's
            // -sources.jar carried the whole of target/classes. That was 8,256 class files and 10.8 MB of
            // bytecode inside fix-latest's sources jar alone.
            if (testSources) {
                project.addTestCompileSourceRoot(sourcesOutputDirectory.getAbsolutePath());
            } else {
                project.addCompileSourceRoot(sourcesOutputDirectory.getAbsolutePath());
            }
        }
    }
}