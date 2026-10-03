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
package org.lolaf.staffix.admin.http;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

import java.net.URI;
import java.time.Duration;
import java.time.temporal.Temporal;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The engine's messages loggers and session plugins as JSON, so the console can tell where sessions' telemetry
 * goes. Only plain values are kept: functions, executors and live objects say nothing an admin can act on, and
 * calling their getters could have side effects. Each settings object, nested ones included, carries its
 * {@code type}; a plugin also lists its {@link FixSessionsPluginSettings#getPluginTypes() pluginTypes}.
 */
final class EmittersJson {

    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|passwd|secret|token|credential|private.?key|authorization|api.?key).*");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule().setSerializerModifier(new PlainValuesOnly()))
            .addMixIn(FixMessagesLoggerSettings.class, Typed.class)
            .addMixIn(FixSessionsPluginSettings.class, Typed.class)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private EmittersJson() {
    }

    static ObjectNode of(AdminApi adminApi) {
        ObjectNode json = MAPPER.createObjectNode();
        ArrayNode loggers = json.putArray("messagesLoggers");
        adminApi.getFixMessagesLoggersSettings().forEach(settings -> loggers.add(settings(settings)));
        ArrayNode plugins = json.putArray("sessionsPlugins");
        adminApi.getFixSessionsPluginsSettings().forEach(settings -> {
            ObjectNode plugin = (ObjectNode) settings(settings);
            ArrayNode pluginTypes = plugin.putArray("pluginTypes");
            settings.getPluginTypes().forEach(type -> pluginTypes.add(type.getSimpleName()));
            plugins.add(plugin);
        });
        return json;
    }

    private static JsonNode settings(Object settings) {
        JsonNode json = MAPPER.valueToTree(settings);
        redact(json);
        return json;
    }

    private static void redact(JsonNode json) {
        if (json instanceof ObjectNode) {
            ObjectNode object = (ObjectNode) json;
            for (Iterator<Map.Entry<String, JsonNode>> fields = object.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SECRET_NAME.matcher(field.getKey()).matches() && !field.getValue().isContainerNode()) {
                    field.setValue(object.textNode(SettingsJson.MASK));
                } else if (field.getKey().equals("headers") && field.getValue() instanceof ObjectNode) {
                    ObjectNode headers = (ObjectNode) field.getValue();
                    headers.fieldNames().forEachRemaining(name -> headers.put(name, SettingsJson.MASK));
                } else {
                    redact(field.getValue());
                }
            }
        } else if (json instanceof ArrayNode) {
            json.forEach(EmittersJson::redact);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    private interface Typed {
    }

    private static final class PlainValuesOnly extends BeanSerializerModifier {
        @Override
        public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription description,
                                                         List<BeanPropertyWriter> properties) {
            properties.removeIf(property -> !isPlain(property.getType()));
            return properties;
        }

        private static boolean isPlain(JavaType type) {
            if (type.isContainerType()) {
                return (type.getKeyType() == null || isPlain(type.getKeyType())) && isPlain(type.getContentType());
            }
            Class<?> raw = type.getRawClass();
            return raw.isPrimitive() || raw.isEnum() || Number.class.isAssignableFrom(raw) || raw == Boolean.class
                    || raw == Character.class || CharSequence.class.isAssignableFrom(raw) || raw == Duration.class
                    || Temporal.class.isAssignableFrom(raw) || raw == URI.class || raw.getSimpleName().endsWith("Settings");
        }
    }
}
