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

import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.regex.Pattern;

@UtilityClass
class Labels {

    /**
     * Prometheus' label syntax, since Alertmanager refuses a whole post over one invalid name.
     */
    private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    static Map<String, String> requireValid(Map<String, String> labels) {
        labels.forEach((labelName, value) -> {
            if (!LABEL_NAME.matcher(labelName).matches()) {
                throw new IllegalArgumentException("Label name " + labelName + " must match " + LABEL_NAME);
            }
            if (AlertLabel.isReserved(labelName)) {
                throw new IllegalArgumentException("Label name " + labelName + " is reserved by " + AlertLabel.class.getSimpleName());
            }
            if (value == null) {
                throw new IllegalArgumentException("Label " + labelName + " has no value");
            }
        });
        return labels;
    }
}
