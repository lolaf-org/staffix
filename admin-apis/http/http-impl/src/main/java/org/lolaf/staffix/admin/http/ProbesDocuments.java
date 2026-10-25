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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lolaf.staffix.admin.http.dto.ProbeDescription;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.alerting.ProbeDefinition;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.alerting.ProbesListener;
import org.lolaf.staffix.api.alerting.ProbeScope;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each scope's probe definitions encoded once, dropped when a probe of that scope is added or removed and
 * rebuilt at the next read, so a poll never re-encodes them. Built on read rather than from the events alone, because
 * the sessions an engine had before this listener was registered were announced to nobody.
 */
public final class ProbesDocuments implements ProbesListener {

    private final AdminApi adminApi;
    private final ObjectMapper mapper;
    private final Map<ProbeScope, ProbesDocument> documents = new ConcurrentHashMap<>();
    /**
     * Starts at the wall clock rather than zero, so a console polling across an engine restart still sees it move.
     */
    private final AtomicLong alertsVersion = new AtomicLong(System.currentTimeMillis());
    private final Function<ProbeScope, ProbesDocument> builder = this::build;

    public ProbesDocuments(AdminApi adminApi, ObjectMapper mapper) {
        this.adminApi = adminApi;
        this.mapper = mapper;
    }

    /**
     * @throws IllegalArgumentException if the engine no longer evaluates the scope
     */
    public ProbesDocument get(ProbeScope scope) {
        ProbesDocument cached = documents.get(scope);
        return cached != null ? cached : documents.computeIfAbsent(scope, builder);
    }

    public long getAlertsVersion() {
        return alertsVersion.get();
    }

    @Override
    public void onProbeAdded(ProbeScope scope, ProbeDefinition probe) {
        documents.remove(scope);
    }

    @Override
    public void onProbeRemoved(ProbeScope scope, String probeName) {
        documents.remove(scope);
    }

    @Override
    public void onAlertTransition(ProbeScope scope, ProbeState state) {
        alertsVersion.incrementAndGet();
    }

    private ProbesDocument build(ProbeScope scope) {
        List<ProbeDescription> probes = adminApi.getProbeDefinitions(scope).stream()
                .map(ProbeDescription::of)
                .collect(Collectors.toList());
        try {
            byte[] json = mapper.writeValueAsBytes(probes);
            return new ProbesDocument(Sha256.hex(json), json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class ProbesDocument {

        private final String version;
        private final byte[] json;
    }
}
