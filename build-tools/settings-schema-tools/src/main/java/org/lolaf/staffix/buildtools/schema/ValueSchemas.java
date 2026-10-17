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
package org.lolaf.staffix.buildtools.schema;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.TimeZone;

/**
 * The JSON Schema of the values settings write as strings, with the pattern of what their parser accepts, so an editor
 * flags {@code 10x} or {@code 25:00} instead of the engine failing at load time.
 */
@UtilityClass
public class ValueSchemas {

    /**
     * What {@link Duration#parse} accepts, written in capitals: tighter than {@code format: duration}, which also
     * allows the years, months and weeks a {@code Duration} refuses, and an empty {@code P} or {@code T}.
     */
    public static final String ISO_DURATION = "^[-+]?P(?!$)(?:[-+]?\\d+D)?(?:T(?=[-+]?\\d)(?:[-+]?\\d+H)?(?:[-+]?\\d+M)?"
            + "(?:[-+]?\\d+(?:[.,]\\d+)?S)?)?$";

    /**
     * Spring Boot's {@code DurationStyle}: ISO-8601 in any case, or a number with an optional unit, milliseconds
     * without one.
     */
    public static final String SPRING_DURATION = "^(?:[-+]?[pP](?!$)(?:[-+]?\\d+[dD])?"
            + "(?:[tT](?=[-+]?\\d)(?:[-+]?\\d+[hH])?(?:[-+]?\\d+[mM])?(?:[-+]?\\d+(?:[.,]\\d+)?[sS])?)?"
            + "|[-+]?\\d+(?:[nNuUmM]?[sS]|[mMhHdD])?)$";

    /**
     * {@code ISO_LOCAL_TIME}, with no {@code format}: the standard {@code time} format requires a zone offset.
     */
    public static final String LOCAL_TIME = "^([01]\\d|2[0-3]):[0-5]\\d(?::[0-5]\\d(?:\\.\\d{1,9})?)?$";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /**
     * Who parses the values a schema describes.
     */
    public enum Syntax {

        /**
         * Settings read from a document, through each type's own parser.
         */
        SETTINGS(ISO_DURATION, "ISO-8601 duration, e.g. PT10S, PT1H30M, P2DT3H, PT0.0000015S", LOCAL_TIME,
                "Local time of day (ISO-8601, no zone), e.g. 09:30, 10:10:11, 10:10:10.099999999"),

        /**
         * Spring Boot properties, whose binder reads a time in the format of the application's locale.
         */
        SPRING(SPRING_DURATION, "ISO-8601 duration or a number with a unit (ns, us, ms, s, m, h, d), e.g. PT10S, 10s,"
                + " 500ms", null, "Local time of day");

        private final String durationPattern;
        private final String durationDescription;
        private final String localTimePattern;
        private final String localTimeDescription;

        Syntax(String durationPattern, String durationDescription, String localTimePattern,
               String localTimeDescription) {
            this.durationPattern = durationPattern;
            this.durationDescription = durationDescription;
            this.localTimePattern = localTimePattern;
            this.localTimeDescription = localTimeDescription;
        }
    }

    /**
     * @return a new schema, which the caller may complete, or empty when the type is not written as a string
     */
    public static Optional<ObjectNode> of(Class<?> type, Syntax syntax) {
        if (type == Duration.class) {
            return Optional.of(string("duration", syntax.durationPattern, syntax.durationDescription));
        }
        if (InetAddress.class.isAssignableFrom(type)) {
            return Optional.of(string(null, null, "Host name or IP address, e.g. 10.0.0.12"));
        }
        if (TimeZone.class.isAssignableFrom(type) || ZoneId.class.isAssignableFrom(type)) {
            return Optional.of(string(null, null, "Time zone id, e.g. Europe/Paris"));
        }
        if (type == LocalTime.class) {
            return Optional.of(string(null, syntax.localTimePattern, syntax.localTimeDescription));
        }
        return Optional.empty();
    }

    private static ObjectNode string(String format, String pattern, String description) {
        ObjectNode schema = NODES.objectNode().put("type", "string");
        if (format != null) {
            schema.put("format", format);
        }
        if (pattern != null) {
            schema.put("pattern", pattern);
        }
        return schema.put("description", description);
    }
}
