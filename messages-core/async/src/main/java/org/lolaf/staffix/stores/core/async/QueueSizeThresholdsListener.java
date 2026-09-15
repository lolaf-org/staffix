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
import lombok.Value;
import org.lolaf.staffix.api.session.FixSessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener interface for monitoring queue size threshold events in async stores.
 * <p>
 * This listener receives notifications when the async queue size crosses configured thresholds,
 * allowing applications to react to queue pressure situations (e.g., apply backpressure, shed load,
 * or alert operators).
 */
public interface QueueSizeThresholdsListener {

    /**
     * Invoked when the queue size crosses a configured threshold.
     *
     * @param fixSessionId       the FIX session identifier
     * @param queueSizeThreshold the threshold level that was reached
     * @param thresholdValue     the configured threshold value
     * @param currentQueueSize   the current size of the queue
     */
    void onQueueSizeThresholdReached(FixSessionId fixSessionId, QueueSizeThreshold queueSizeThreshold, int thresholdValue, int currentQueueSize);

    /**
     * Enumeration of queue size threshold levels.
     */
    enum QueueSizeThreshold {
        /**
         * Queue size is within normal operating parameters.
         */
        NORMAL,
        /**
         * Queue size has exceeded warning threshold and should be monitored.
         */
        WARNING,
        /**
         * Queue size has reached critical levels and requires immediate attention.
         */
        CRITICAL;
    }

    /**
     * Implementation that logs queue size threshold events using SLF4J with configurable prefix.
     * <p>
     * Logs at different levels depending on the threshold:
     * <ul>
     *   <li>NORMAL: INFO level</li>
     *   <li>WARNING: WARN level</li>
     *   <li>CRITICAL: ERROR level</li>
     * </ul>
     */
    @Value
    class LoggingQueueSizeThresholdsListener implements QueueSizeThresholdsListener {

        String logPrefix;

        @Override
        public void onQueueSizeThresholdReached(FixSessionId fixSessionId, QueueSizeThreshold queueSizeThreshold, int thresholdValue, int currentQueueSize) {
            Logger logger = LoggerFactory.getLogger(QueueSizeThresholdsListener.class);
            switch (queueSizeThreshold) {
                case NORMAL:
                    logger.info("{} queue size for session {} is back to normal ({}): {}", logPrefix, fixSessionId, thresholdValue, currentQueueSize);
                    break;
                case WARNING:
                    logger.warn("{} queue size for session {} is above normal ({}): {}", logPrefix, fixSessionId, thresholdValue, currentQueueSize);
                    break;
                case CRITICAL:
                    logger.error("{} queue size for session {} is critical ({}): {}", logPrefix, fixSessionId, thresholdValue, currentQueueSize);
                    break;
            }
        }
    }

    /**
     * No-op implementation that ignores all queue size threshold events.
     * <p>
     * Useful when queue size monitoring is not required or needs to be disabled.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    class VoidQueueSizeThresholdsListener implements QueueSizeThresholdsListener {

        private static final VoidQueueSizeThresholdsListener INSTANCE = new VoidQueueSizeThresholdsListener();

        public static VoidQueueSizeThresholdsListener getInstance() {
            return INSTANCE;
        }

        @Override
        public void onQueueSizeThresholdReached(FixSessionId fixSessionId, QueueSizeThreshold queueSizeThreshold, int thresholdValue, int currentQueueSize) {
            // nothing to do
        }
    }
}
