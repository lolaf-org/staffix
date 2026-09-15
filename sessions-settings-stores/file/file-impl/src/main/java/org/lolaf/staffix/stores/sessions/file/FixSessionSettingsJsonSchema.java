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
package org.lolaf.staffix.stores.sessions.file;

import lombok.experimental.UtilityClass;

import java.io.InputStream;

/**
 * Runtime access to the JSON Schema describing the on-disk session settings file format. The schema itself is produced
 * at build time by {@link FixSessionSettingsJsonSchemaGenerator} and packaged in this module's jar; here it is only
 * located and read.
 * <p>
 * This class deliberately references nothing beyond the JDK. The {@code com.github.victools} generator is an
 * {@code optional} dependency and is therefore absent from a consuming application, and the JVM links a class as a
 * whole: a single method touching a victools type would make every call into this class fail with
 * {@link NoClassDefFoundError}, even one that only opens a resource. Keep it that way — {@code TestSchemaResource}
 * asserts the generator does not appear in this class's constant pool.
 */
@UtilityClass
public class FixSessionSettingsJsonSchema {

    /**
     * The conventional file name under which the schema is written alongside the session files, and the name of the
     * resource inside this module's jar.
     */
    public static final String SCHEMA_FILE_NAME = "fix-session-settings.v1.schema.json";

    /**
     * Opens the schema generated at build time and packaged in this module's jar, next to this class. The name is
     * resolved through {@link Class#getResourceAsStream(String)} and so is relative to this class's package, which is
     * where the build writes it.
     *
     * @return a stream over the packaged schema, or {@code null} if the resource is missing — which means the build's
     * generation step did not run, since the jar always carries it.
     */
    public static InputStream openPackagedSchema() {
        return FixSessionSettingsJsonSchema.class.getResourceAsStream(SCHEMA_FILE_NAME);
    }
}
