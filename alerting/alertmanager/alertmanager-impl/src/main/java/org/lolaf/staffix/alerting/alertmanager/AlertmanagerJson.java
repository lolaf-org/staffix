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
package org.lolaf.staffix.alerting.alertmanager;

import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Encodes alerts by hand: the payload is a few flat string maps, not worth a JSON library dependency.
 */
@UtilityClass
class AlertmanagerJson {

    String encode(List<PostableAlert> alerts) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < alerts.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendAlert(json, alerts.get(i));
        }
        return json.append(']').toString();
    }

    private void appendAlert(StringBuilder json, PostableAlert alert) {
        json.append("{\"labels\":");
        appendObject(json, alert.getLabels());
        json.append(",\"annotations\":");
        appendObject(json, alert.getAnnotations());
        json.append(",\"startsAt\":");
        appendInstant(json, alert.getStartsAt());
        json.append(",\"endsAt\":");
        appendInstant(json, alert.getEndsAt());
        json.append('}');
    }

    private void appendObject(StringBuilder json, Map<String, String> entries) {
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendString(json, entry.getKey());
            json.append(':');
            appendString(json, entry.getValue());
        }
        json.append('}');
    }

    private void appendInstant(StringBuilder json, Instant instant) {
        appendString(json, instant.toString());
    }

    private void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                json.append('\\').append(c);
            } else if (c < 0x20) {
                json.append(String.format("\\u%04x", (int) c));
            } else {
                json.append(c);
            }
        }
        json.append('"');
    }
}
