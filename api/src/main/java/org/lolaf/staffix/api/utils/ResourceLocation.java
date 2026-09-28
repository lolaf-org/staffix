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
package org.lolaf.staffix.api.utils;

import lombok.experimental.UtilityClass;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Opens a plugin input given either as {@code classpath:<resource>} or as a file path.
 */
@UtilityClass
public class ResourceLocation {

    public static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * @param location {@code classpath:<resource>}, looked up in {@code classLoader}, which for a mojo is the
     *                 plugin's own: a jar holding the resource must be listed in the plugin's {@code <dependencies>}.
     *                 Anything else is a file, resolved against {@code basedir} when relative.
     */
    public static InputStream open(String location, File basedir, ClassLoader classLoader) throws IOException {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            String resource = location.substring(CLASSPATH_PREFIX.length());
            InputStream stream = classLoader.getResourceAsStream(resource.startsWith("/") ? resource.substring(1) : resource);
            if (stream == null) {
                throw new FileNotFoundException("Classpath resource " + resource + " not found; add the jar holding it "
                        + "to the plugin's <dependencies>");
            }
            return stream;
        }
        return new FileInputStream(toFile(location, basedir));
    }

    private static File toFile(String location, File basedir) {
        File file = new File(location);
        return file.isAbsolute() || basedir == null ? file : new File(basedir, location);
    }
}
