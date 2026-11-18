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

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a probe belongs to, such as one session; probe names are unique within a scope. Its type and name identify it,
 * its labels only describe it.
 */
@Value
public class ProbeScope {

    /**
     * The kind of thing watched, such as {@code session}.
     */
    String type;
    /**
     * Unique among the scopes of its type.
     */
    String name;
    /**
     * Carried on every alert of the scope, so a label-based system such as Alertmanager can route it. Named as probe
     * labels are, and a probe cannot reuse one.
     */
    @EqualsAndHashCode.Exclude
    Map<String, String> labels;

    @Builder
    private ProbeScope(@NonNull String type, @NonNull String name, @Singular Map<String, String> labels) {
        this.type = type;
        this.name = name;
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(Labels.requireValid(labels)));
    }

    /**
     * A scope without labels.
     */
    public static ProbeScope of(String type, String name) {
        return builder().type(type).name(name).build();
    }

    /**
     * {@code type/name}, for logs and messages.
     */
    public String getQualifiedName() {
        return type + "/" + name;
    }

    @Override
    public String toString() {
        return getQualifiedName();
    }
}
