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
package org.lolaf.staffix.benchmarks.impl;

import lombok.extern.slf4j.Slf4j;
import org.openjdk.jmh.annotations.*;

@Slf4j
@State(Scope.Benchmark)
public class AbstractBenchmark {

    @Param({"STOCK", "LOW_LATENCY"})
    FixEngineSettings fixEngineSettings;

    public void setFixEngineSettings(FixEngineSettings fixEngineSettings) {
        this.fixEngineSettings = fixEngineSettings;
    }

    void startAndWaitForConnection() throws Exception {
        // override me if needed
    }

    void shutdownEngine() throws Exception {
        // override me if needed
    }

    @Setup
    public void setup() throws Exception {
        log.info("Starting benchmark for {} latency setting and fix engine {}, calling system gc", fixEngineSettings, this.getClass().getSimpleName());
        System.gc();
        log.info("System gc done");
        fixEngineSetup(fixEngineSettings);
        setupAcceptor(fixEngineSettings);
        setupInitiator(fixEngineSettings);
        startAndWaitForConnection();
        log.info("Fix engine {} ready", this.getClass().getSimpleName());
    }

    void sendMessageAndWaitForResponse() {
        // override me if needed
    }

    void fixEngineSetup(FixEngineSettings fixEngineSettings) throws Exception {
        // override me if needed
    }

    void setupInitiator(FixEngineSettings fixEngineSettings) throws Exception {
        // override me if needed
    }

    void setupAcceptor(FixEngineSettings fixEngineSettings) throws Exception {
        // override me if needed
    }

    @TearDown
    public void tearDown() throws Exception {
        log.info("Shutdown engine");
        shutdownEngine();
        log.info("Shutdown engine terminated");
    }
}