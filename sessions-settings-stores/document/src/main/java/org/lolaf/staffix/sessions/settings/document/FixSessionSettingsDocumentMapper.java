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
package org.lolaf.staffix.sessions.settings.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.net.InetAddress;

/**
 * How a {@link FixSessionSettingsDocument} is read and written, whatever the format: YAML for a file, JSON over HTTP.
 */
@UtilityClass
public class FixSessionSettingsDocumentMapper {

    /**
     * @param mapper a mapper of the format to read and write, e.g. one built on a YAML factory
     * @return the same mapper, configured
     */
    public static ObjectMapper configure(ObjectMapper mapper) {
        // explicitly rather than through findAndRegisterModules, whose service files a fat jar can lose
        mapper.registerModule(new JavaTimeModule());
        // Jackson's built-in InetAddress deserializer (2.19+) rejects any string that is not an IP literal
        SimpleModule inetAddressModule = new SimpleModule();
        inetAddressModule.addDeserializer(InetAddress.class, new JsonDeserializer<>() {
            @Override
            public InetAddress deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return InetAddress.getByName(p.getValueAsString().trim());
            }
        });
        mapper.registerModule(inetAddressModule);
        mapper.configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false); // LocalTime as "10:10:11", not [10,10,11]
        mapper.setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
        // Strict reading: a typo'd field name, an unknown enum constant or trailing junk fails loudly instead of
        // silently leaving a field null. Bean Validation then enforces value-level rules.
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        mapper.configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
        mapper.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, false);
        return mapper;
    }
}
