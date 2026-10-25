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
package org.lolaf.staffix.api.alerting;

import lombok.Getter;

import java.util.Locale;

/**
 * The labels every alert carries, with its scope's, for a publisher to a label-based system such as Alertmanager.
 * Neither a probe's nor a scope's labels can use these names.
 */
public enum AlertLabel {

    ALERT_NAME("alertname") {
        @Override
        public String valueFor(AlertEvent event) {
            return event.getProbeId().getName();
        }
    },
    /**
     * The {@link AlertEvent#getSource() source}; the scope and the probe name only identify an alert within it.
     */
    SOURCE("source") {
        @Override
        public String valueFor(AlertEvent event) {
            return event.getSource();
        }
    },
    /**
     * The level in lower case, as Prometheus alerting rules conventionally write it.
     */
    SEVERITY("severity") {
        @Override
        public String valueFor(AlertEvent event) {
            return event.getAlert().getLevel().name().toLowerCase(Locale.ROOT);
        }
    };

    @Getter
    private final String labelName;

    AlertLabel(String labelName) {
        this.labelName = labelName;
    }

    public abstract String valueFor(AlertEvent event);

    public static boolean isReserved(String labelName) {
        for (AlertLabel label : values()) {
            if (label.labelName.equals(labelName)) {
                return true;
            }
        }
        return false;
    }
}
