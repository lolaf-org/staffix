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
package org.lolaf.staffix.ha.replication.encryption;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * One connection's keys, derived from the shared secret and both peers' nonces, so no two connections share them.
 * Deliberately without {@code toString}.
 */
@Getter
@RequiredArgsConstructor
public final class ConnectionEncryptionKeys {

    /**
     * Keys the follower's KEY_CONFIRMATION.
     */
    private final byte[] confirmationKey;
    private final DirectionKeys leaderToFollower;
    private final DirectionKeys followerToLeader;

    /**
     * The keys of one direction: the cipher's, the MAC's for a stream cipher, and the initial IV.
     */
    @Getter
    @RequiredArgsConstructor
    public static final class DirectionKeys {
        private final byte[] cipherKey;
        private final byte[] macKey;
        private final byte[] iv;
    }
}
