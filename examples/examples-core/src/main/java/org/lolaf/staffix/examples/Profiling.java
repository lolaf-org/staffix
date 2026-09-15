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
package org.lolaf.staffix.examples;

import lombok.Data;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import one.profiler.AsyncProfiler;
import picocli.CommandLine;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

@Slf4j
@UtilityClass
public class Profiling {

    public static void startProfilingIfNeeded(ProfilingOptions profilingOptions, Class<?> sampleClass) {
        if (profilingOptions.isListEventNames()) {
            AsyncProfiler profiler = AsyncProfiler.getInstance();
            try {
                String cmd = profiler.execute("list");
                log.info("AsyncProfiler available event names:\n{}", cmd);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        if (profilingOptions.isEnable()) {
            log.info("Ensure you call for proper profiling:\n" +
                    "sudo sysctl kernel.perf_event_paranoid=1\n" +
                    "sudo sysctl kernel.kptr_restrict=0");
            new Thread(() -> {
                LockSupport.parkNanos(Duration.ofSeconds(profilingOptions.getStartDelayInSeconds()).toNanos());
                AsyncProfiler profiler = start(profilingOptions);
                Runtime.getRuntime().addShutdownHook(new Thread(() ->
                        stop(profiler, sampleClass)));
            }).start();
        }
    }

    public static AsyncProfiler start(ProfilingOptions profilingOptions) {
        AsyncProfiler profiler = AsyncProfiler.getInstance();
        try {
            String started = profiler.execute("start,event=" + profilingOptions.getPerfEventName() + "," + profilingOptions.getProfilerAdditionalParams());
            log.info("AsyncProfiler start: {}", started);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return profiler;
    }

    public static void stop(AsyncProfiler profiler, Class<?> sampleClass) {
        try {
            String stopped = profiler.execute("stop,flamegraph,file=" + sampleClass.getSimpleName() + ".html");
            log.info("AsyncProfiler stop: {}", stopped);
            log.info("AsyncProfiler flamegraph saved in {}", sampleClass.getSimpleName() + ".html");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Data
    public static class ProfilingOptions {
        @CommandLine.Option(names = "-pe", description = "Enable profiling with async profiler", defaultValue = "false")
        private boolean enable;

        @CommandLine.Option(names = "-pn", description = "Profiling event name", defaultValue = "alloc")
        private String perfEventName;

        @CommandLine.Option(names = "-ps", description = "Profiling start delay in seconds", defaultValue = "10")
        private long startDelayInSeconds;

        @CommandLine.Option(names = "-pl", description = "List the available profiling event names, you can also use 'perf list'", defaultValue = "false")
        private boolean listEventNames;

        @CommandLine.Option(names = "-pp", description = "Profiler command additional params", defaultValue = "threads,norm")
        private String profilerAdditionalParams;

    }
}
