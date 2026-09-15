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
package org.lolaf.staffix.tests;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ports a test binds have to be its own across the whole machine, not just within its own JVM: a build runs
 * several modules at once, each in a surefire JVM of its own, and two of them handed the same port is a BindException
 * in whichever binds second - which is what this was, intermittently and in a different module every time.
 * <p>
 * So the interesting case is two JVMs, and this test runs two, both started on the same offset through
 * {@link TestPorts#FORCED_OFFSET_PROPERTY}. Left to their random offsets they would walk two sequences that only
 * rarely meet, which is precisely why the problem took so long to be seen at all: a test that reproduces it four
 * times out of a hundred guards nothing. On the same offset they walk the same ports, and the reservations are the
 * only thing that can keep them apart.
 */
class TestPortsReservation {

    private static final int PORTS_PER_JVM = 100;

    /**
     * Entry point of the child JVMs: hands out its share of ports, prints them, then waits for its parent to close
     * stdin. The wait is what makes the test meaningful - a JVM that exits releases its reservations, and the second
     * child has to allocate while the first one's are still held.
     */
    public static void main(String[] args) throws Exception {
        StringBuilder allocated = new StringBuilder();
        for (int i = 0; i < PORTS_PER_JVM; i++) {
            allocated.append(TestPorts.findFree()).append('\n');
        }
        System.out.print(allocated);
        System.out.flush();
        System.in.read();
    }

    private static Process startChild(int offset) throws Exception {
        return new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-cp", System.getProperty("java.class.path"),
                "-D" + TestPorts.FORCED_OFFSET_PROPERTY + "=" + offset,
                TestPortsReservation.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private static void stop(Process child) throws Exception {
        child.getOutputStream().close();
        child.destroy();
    }

    private static List<Integer> readPorts(Process child) throws Exception {
        List<Integer> ports = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
        for (int i = 0; i < PORTS_PER_JVM; i++) {
            String line = reader.readLine();
            if (line == null) {
                break;
            }
            ports.add(Integer.parseInt(line.trim()));
        }
        return ports;
    }

    @Test
    void testTwoJvmsWalkingTheSamePortsAreNeverHandedTheSameOne() throws Exception {
        int sharedOffset = new SecureRandom().nextInt(10_000);
        Process first = startChild(sharedOffset);
        try {
            List<Integer> firstPorts = readPorts(first);
            assertThat(firstPorts).as("the first child JVM must have handed out its share").hasSize(PORTS_PER_JVM);

            // started only now, so that it walks into reservations that are held rather than into free ports
            Process second = startChild(sharedOffset);
            try {
                List<Integer> secondPorts = readPorts(second);
                assertThat(secondPorts).as("the second child JVM must have handed out its share").hasSize(PORTS_PER_JVM);

                assertThat(secondPorts).doesNotHaveDuplicates();
                assertThat(secondPorts)
                        .as("a port another live JVM holds may not be handed out again")
                        .doesNotContainAnyElementsOf(firstPorts);
            } finally {
                stop(second);
            }
        } finally {
            stop(first);
        }
    }
}
