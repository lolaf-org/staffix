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
package org.lolaf.staffix.buildtools.quickfixj;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The skill runs the plugin's copy with {@code java} alone; since JDK 22 the source launcher refuses a file whose path
 * does not end in its package, so this runs it on the build's own JDK, from the path the skill names.
 */
class TestSourceLauncher {

    private static final Path PLUGIN_COPY = Path.of("../../claude-plugin/skills/migrate-from-quickfixj/scripts/"
            + "org/lolaf/staffix/buildtools/quickfixj/CfgToStaffix.java");

    @TempDir
    Path out;

    @Test
    void thePluginCopyRunsWithTheSourceLauncher() throws IOException, InterruptedException {
        Process java = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                PLUGIN_COPY.toString(), "--out", out.toString(),
                Path.of("src/test/resources/cfg/quickfixj-examples/executor.cfg").toString())
                .redirectErrorStream(true).start();
        String output = new String(java.getInputStream().readAllBytes());

        assertThat(java.waitFor(2, TimeUnit.MINUTES)).isTrue();
        assertThat(java.exitValue()).as(output).isZero();
        assertThat(out.resolve("report.md")).exists();
        assertThat(Files.isSymbolicLink(PLUGIN_COPY)).isTrue();
    }
}
