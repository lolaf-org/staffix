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
package org.lolaf.staffix.api.application;

import org.lolaf.staffix.api.FixDictionaryId;
import org.lolaf.staffix.api.InstanceIdSupplier;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.session.FixSessionSettings;

import java.util.Set;

/**
 * Supplies the {@code FixApplication} that receives a session's messages.
 *
 * <p>A factory rather than an instance because the application is chosen per session, by the id its settings
 * name - which is what lets one engine serve several counterparties with different logic.
 */
public interface FixApplicationFactory extends InstanceIdSupplier, Startable<FixApplicationFactory> {

    /**
     * Get the FixApplication instance registered under the given application id.
     * The application id is selected by each session via {@link FixSessionSettings#getFixApplicationInstanceId()}.
     */
    FixApplication getInstance(String applicationId);

    /**
     * The application ids {@link #getInstance(String)} accepts
     */
    Set<String> getApplicationIds();

    /**
     * The dictionary of the application registered under the given id, which settings are checked against before a
     * session exists. Override it when {@link #getInstance(String)} creates or tracks an instance per call.
     */
    default FixDictionaryId getDictionaryId(String applicationId) {
        return getInstance(applicationId).getDictionaryId();
    }
}