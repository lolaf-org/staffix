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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSessionId;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.io.IOException;
import java.net.InetAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A session's settings as JSON for display, with secret application setting values masked: those the application
 * declares secret, and, for a setting no application declared, those whose name looks secret.
 */
final class SettingsJson {

    static final String MASK = "******";
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|passwd|secret|token|credential|private.?key).*");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule()
                    .addSerializer(FixSessionId.class, ToStringSerializer.instance)
                    .addSerializer(Certificate.class, new CertificateSerializer())
                    .addSerializer(InetAddress.class, new AddressSerializer())
                    .addKeySerializer(FixApplicationSessionSettingDescriptor.class, new DescriptorKeySerializer()))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private SettingsJson() {
    }

    static JsonNode of(FixSessionSettings settings) {
        ObjectNode json = MAPPER.valueToTree(settings);
        JsonNode applicationSettings = json.get("fixApplicationSessionSettings");
        if (applicationSettings instanceof ObjectNode) {
            settings.getFixApplicationSessionSettings().keySet().stream()
                    .filter(SettingsJson::isSecret)
                    .forEach(descriptor -> ((ObjectNode) applicationSettings).put(descriptor.getId(), MASK));
        }
        return json;
    }

    static boolean isSecret(FixApplicationSessionSettingDescriptor descriptor) {
        return descriptor.isSecret() || SECRET_NAME.matcher(descriptor.getId()).matches();
    }

    private static class DescriptorKeySerializer extends JsonSerializer<FixApplicationSessionSettingDescriptor> {
        @Override
        public void serialize(FixApplicationSessionSettingDescriptor descriptor, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeFieldName(descriptor.getId());
        }
    }

    private static class AddressSerializer extends JsonSerializer<InetAddress> {
        @Override
        public void serialize(InetAddress address, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(address.getHostAddress());
        }
    }

    private static class CertificateSerializer extends JsonSerializer<Certificate> {
        @Override
        public void serialize(Certificate certificate, JsonGenerator generator, SerializerProvider provider) throws IOException {
            if (!(certificate instanceof X509Certificate)) {
                generator.writeString(certificate.getType());
                return;
            }
            X509Certificate x509 = (X509Certificate) certificate;
            generator.writeObject(Map.of(
                    "subject", x509.getSubjectX500Principal().getName(),
                    "issuer", x509.getIssuerX500Principal().getName(),
                    "serialNumber", x509.getSerialNumber().toString(16),
                    "notAfter", x509.getNotAfter().toInstant().toString()));
        }
    }
}
