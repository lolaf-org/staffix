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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.lolaf.staffix.api.application.FixApplicationSessionSettingDescriptor;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.sessions.settings.document.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A session's settings as the JSON document of the settings schema, application settings that are secret masked,
 * and back: a masked value sent back keeps the one the session has.
 */
final class SessionSettingsDocuments {

    static final String MASK = "******";
    /**
     * For an application setting no application declared: its name is all there is to go on.
     */
    private static final Pattern SECRET_NAME = Pattern.compile("(?i).*(password|passwd|secret|token|credential|private.?key).*");
    private static final ObjectMapper MAPPER = FixSessionSettingsDocumentMapper.configure(new ObjectMapper());

    private SessionSettingsDocuments() {
    }

    static byte[] write(FixSessionSettings settings) {
        FixSessionSettingsDocument document = ToFixSessionSettingsDocumentTransformer.toDocument(settings);
        Map<String, String> applicationSettings = document.getFixApplicationSessionSettings();
        if (applicationSettings != null) {
            Map<String, String> masked = new HashMap<>(applicationSettings);
            masked.replaceAll((id, value) -> isSecret(id) ? MASK : value);
            document.setFixApplicationSessionSettings(masked);
        }
        try {
            return MAPPER.writeValueAsBytes(document);
        } catch (JsonProcessingException e) {
            // the engine's own settings failed to write: its fault, not the request's
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param current the settings the session has, whose secrets and certificates a masked or absent value keeps;
     *                null for a new session
     * @throws HttpProblemException 400 when the document is invalid
     */
    static FixSessionSettings read(InputStream body, FixSessionSettings current) throws IOException {
        FixSessionSettingsDocument document = MAPPER.readValue(body, FixSessionSettingsDocument.class);
        restoreMaskedSecrets(document, current);
        try {
            FixSessionSettingsDocumentValidator.validate(document, "the request");
            FixSessionSettings settings = FromFixSessionSettingsDocumentTransformer.toFixSessionSettings(document);
            // a certificate cannot be written as a document, so the ones the session has stay
            return current == null || document.getAllowedCertificates() != null
                    ? settings
                    : settings.toBuilder().allowedCertificates(current.getAllowedCertificates()).build();
        } catch (RuntimeException e) {
            throw new HttpProblemException(400, "Invalid session settings: " + e.getMessage());
        }
    }

    static boolean isSecret(String applicationSettingId) {
        return FixApplicationSessionSettingDescriptor.of(applicationSettingId).isSecret()
                || SECRET_NAME.matcher(applicationSettingId).matches();
    }

    private static void restoreMaskedSecrets(FixSessionSettingsDocument document, FixSessionSettings current) {
        Map<String, String> applicationSettings = document.getFixApplicationSessionSettings();
        if (applicationSettings == null || !applicationSettings.containsValue(MASK)) {
            return;
        }
        Map<String, String> restored = new HashMap<>(applicationSettings);
        restored.replaceAll((id, value) -> {
            if (!MASK.equals(value)) {
                return value;
            }
            String stored = current == null ? null
                    : current.getFixApplicationSessionSettings().get(FixApplicationSessionSettingDescriptor.of(id));
            if (stored == null) {
                throw new HttpProblemException(400, "Application setting " + id + " is masked but the session has no value for it");
            }
            return stored;
        });
        document.setFixApplicationSessionSettings(restored);
    }
}