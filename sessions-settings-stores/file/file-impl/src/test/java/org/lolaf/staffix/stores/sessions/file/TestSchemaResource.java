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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the boundary that keeps the JSON schema generator off the runtime path.
 * <p>
 * {@code com.github.victools} is an {@code optional} dependency, so it is absent from any application that depends on
 * this module. The JVM links a class as a unit: if a class reachable at runtime so much as mentions a victools type in
 * a method body or signature, verification resolves it and every call into that class dies with
 * {@link NoClassDefFoundError} — including calls that only read a resource. Inspecting the compiled constant pool is
 * the cheapest way to assert that boundary really holds, because a plain unit test would pass regardless: victools is
 * on this module's own test classpath.
 */
class TestSchemaResource {

    /**
     * Reads a compiled class file as ISO-8859-1, which maps every byte to one char and so leaves the UTF-8 constant
     * pool entries (class and member names) searchable as plain text.
     */
    private static String classBytesAsLatin1(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        try (InputStream stream = type.getResourceAsStream(resource)) {
            assertThat(stream).describedAs("compiled class file for %s", type.getName()).isNotNull();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    void runtimeSchemaClassDoesNotReferenceTheGenerator() throws IOException {
        String constantPool = classBytesAsLatin1(FixSessionSettingsJsonSchema.class);

        assertThat(constantPool)
                .describedAs("FixSessionSettingsJsonSchema is reached at runtime, where the optional victools "
                        + "dependency is absent; keep generator references in FixSessionSettingsJsonSchemaGenerator")
                .doesNotContain("victools")
                .doesNotContain("FixSessionSettingsJsonSchemaGenerator");
    }

    @Test
    void storeDoesNotReferenceTheGenerator() throws IOException {
        String constantPool = classBytesAsLatin1(FileFixSessionsSettingsStore.class);

        assertThat(constantPool)
                .describedAs("FileFixSessionsSettingsStore.writeSchemaFile runs on startup and must not pull in the "
                        + "schema generator")
                .doesNotContain("victools")
                .doesNotContain("FixSessionSettingsJsonSchemaGenerator");
    }
}
