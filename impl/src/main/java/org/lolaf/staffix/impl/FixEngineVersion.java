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
package org.lolaf.staffix.impl;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.version.FixApiVersion;
import org.lolaf.staffix.api.version.SemVer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The engine's own name and version, read once from the jar manifest.
 *
 * <p>Announced in the Logon's identification fields, which some venues record and a few require.
 */
@Slf4j
@UtilityClass
public class FixEngineVersion {

    private static final FixApiVersion INSTANCE = get();

    public static FixApiVersion getInstance() {
        return INSTANCE;
    }

    private static FixApiVersion get() {
        Properties props = new Properties();
        try (InputStream in = FixEngineVersion.class.getClassLoader().getResourceAsStream("engine-version.txt")) {
            if (in == null) {
                throw new IllegalStateException("Unable to find engine-version.txt in classpath");
            }
            props.load(in);
            return FixApiVersion.builder()
                    .version(SemVer.from(props.getProperty("engineVersion")))
                    .vendor(props.getProperty("engineVendor"))
                    .name(props.getProperty("engineName"))
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load engine version", e);
        }
    }
}