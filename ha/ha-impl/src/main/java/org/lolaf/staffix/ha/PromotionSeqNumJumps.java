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
package org.lolaf.staffix.ha;

import lombok.extern.slf4j.Slf4j;
import org.lolaf.staffix.api.session.FixSessionSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore.FixSessionMessagesStore;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Moves ahead the outgoing seqnum of a session that starts while this node leads, unless it already jumped for this
 * promotion: the jump at promotion covers only the sessions configured then, and a session started later may still
 * hold a seqnum the old leader sent unreplicated.
 */
@Slf4j
final class PromotionSeqNumJumps {

    private static final long FIRST_SEQ_NUM = 1;

    private final long jump;
    /**
     * The promotion each session last jumped for, by type and id.
     */
    private final Map<String, Long> jumpedFor = new ConcurrentHashMap<>();
    /**
     * A session outside these was created under this leadership: the old leader never ran it.
     */
    private volatile Set<String> configuredAtPromotion = Set.of();
    /**
     * 0 while this node does not lead.
     */
    private volatile long currentPromotion;
    /**
     * Role thread only.
     */
    private long promotions;

    PromotionSeqNumJumps(long jump) {
        this.jump = jump;
    }

    /**
     * Role thread only.
     */
    void promoted(Collection<FixSessionSettings> configured) {
        configuredAtPromotion = configured.stream().map(PromotionSeqNumJumps::sessionKey).collect(Collectors.toUnmodifiableSet());
        currentPromotion = ++promotions;
    }

    void demoted() {
        currentPromotion = 0;
    }

    /**
     * The session's seqnum needs no jump for this promotion: it jumped with the others, or is no longer replicated.
     */
    void jumped(FixSessionSettings sessionSettings) {
        long current = currentPromotion;
        if (current != 0) {
            jumpedFor.put(sessionKey(sessionSettings), current);
        }
    }

    /**
     * On the session's thread, once its store started and before it writes.
     */
    void jumpOnStart(FixSessionSettings sessionSettings, FixSessionMessagesStore store) {
        long current = currentPromotion;
        if (current == 0) {
            return;
        }
        Long previous = jumpedFor.put(sessionKey(sessionSettings), current);
        if (previous != null && previous == current) {
            return;
        }
        long next = store.getOutgoingSeqNum();
        if (next == FIRST_SEQ_NUM && !configuredAtPromotion.contains(sessionKey(sessionSettings))) {
            return;
        }
        store.storeNextOutgoingSeqNum(next + jump);
        log.warn("FIX session {} {} started after this node took the lead: outgoing sequence number moved from {} to {}",
                sessionSettings.getFixSessionType(), sessionSettings.getFixSessionId(), next, next + jump);
    }

    private static String sessionKey(FixSessionSettings sessionSettings) {
        return sessionSettings.getFixSessionType() + " " + sessionSettings.getFixSessionId();
    }
}
