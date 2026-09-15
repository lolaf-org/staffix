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
package org.lolaf.staffix.stores.core.async;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.session.FixSessionId;

/**
 * Listener interface for monitoring the state of underlying storage resources in async stores.
 * <p>
 * This listener receives notifications when the underlying storage resource (e.g., database, message queue)
 * transitions between available and unavailable states for a specific FIX session.
 */
public interface StoreUnderlyingResourceStateListener {

    /**
     * Invoked when the underlying storage resource is up.
     *
     * @param fixSessionId               the FIX session identifier
     * @param underlyingStoreDescription a human-readable description of the underlying store
     */
    void onStoreStateUp(FixSessionId fixSessionId, String underlyingStoreDescription);

    /**
     * Invoked when the underlying storage resource becomes unavailable.
     *
     * @param fixSessionId               the FIX session identifier
     * @param underlyingStoreDescription a human-readable description of the underlying store
     */
    void onStoreStateDown(FixSessionId fixSessionId, String underlyingStoreDescription);

    /**
     * Implementation that logs state transitions using SLF4J.
     * <p>
     * State up events are logged at INFO level, while state down events are logged at ERROR level.
     */
    @Slf4j
    class LoggingStoreUnderlyingResourceStateListener implements StoreUnderlyingResourceStateListener {

        @Override
        public void onStoreStateUp(FixSessionId fixSessionId, String underlyingStoreDescription) {
            log.info("Underlying async store '{}' state is up for session {}", underlyingStoreDescription, fixSessionId);
        }

        @Override
        public void onStoreStateDown(FixSessionId fixSessionId, String underlyingStoreDescription) {
            log.error("Underlying async store '{}' state is down for session {}", underlyingStoreDescription, fixSessionId);
        }
    }

    /**
     * No-op implementation that ignores all state transition events.
     * <p>
     * Useful when state monitoring is not required or needs to be disabled.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class VoidStoreUnderlyingResourceStateListener implements StoreUnderlyingResourceStateListener {

        private static final VoidStoreUnderlyingResourceStateListener INSTANCE = new VoidStoreUnderlyingResourceStateListener();

        public static VoidStoreUnderlyingResourceStateListener getInstance() {
            return INSTANCE;
        }

        @Override
        public void onStoreStateUp(FixSessionId fixSessionId, String underlyingStoreDescription) {
            // nothing to do
        }

        @Override
        public void onStoreStateDown(FixSessionId fixSessionId, String underlyingStoreDescription) {
            // nothing to do
        }
    }

    /**
     * Fail-safe wrapper implementation that catches and logs any exceptions thrown by the wrapped listener.
     * <p>
     * This prevents listener failures from propagating and potentially disrupting the store operation.
     * All exceptions are logged at ERROR level with full stack traces.
     */
    @Slf4j
    @RequiredArgsConstructor
    class FailSafeStoreUnderlyingResourceStateListener implements StoreUnderlyingResourceStateListener {

        private final StoreUnderlyingResourceStateListener delegate;

        @Override
        public void onStoreStateUp(FixSessionId fixSessionId, String underlyingStoreDescription) {
            try {
                delegate.onStoreStateUp(fixSessionId, underlyingStoreDescription);
            } catch (Exception e) {
                log.error("Error when calling onStoreStateUp() for session {} and store '{}'", fixSessionId, underlyingStoreDescription, e);
            }
        }

        @Override
        public void onStoreStateDown(FixSessionId fixSessionId, String underlyingStoreDescription) {
            try {
                delegate.onStoreStateDown(fixSessionId, underlyingStoreDescription);
            } catch (Exception e) {
                log.error("Error when calling onStoreStateDown() for session {} and store '{}'", fixSessionId, underlyingStoreDescription, e);
            }
        }
    }

}
