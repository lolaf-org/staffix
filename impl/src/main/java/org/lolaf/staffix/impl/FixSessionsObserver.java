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
package org.lolaf.staffix.impl;

import org.lolaf.staffix.api.session.FixSession;

/**
 * Internal sink through which an initiator/acceptor tells the owning engine that it has started or stopped managing a
 * session, so that the engine can fan the change out to the {@link org.lolaf.staffix.api.admin.AdminApi.SessionLifecycleListener}s
 * registered by {@link org.lolaf.staffix.api.admin.AdminApiExporter exporters}.
 */
interface FixSessionsObserver {

    /**
     * Invoked when a control starts managing the given session.
     *
     * @param fixSession the session now managed
     */
    void onSessionRegistered(FixSession fixSession);

    /**
     * Invoked when a control stops managing the given session.
     *
     * @param fixSession the session no longer managed
     */
    void onSessionUnregistered(FixSession fixSession);
}
