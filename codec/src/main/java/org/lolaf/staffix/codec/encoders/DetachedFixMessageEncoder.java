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
package org.lolaf.staffix.codec.encoders;

import org.lolaf.staffix.api.codec.FixMessageEncoder;

import java.util.function.Consumer;

/**
 * Carries a message moved out of the encoder it was begun on (see
 * {@link FixMessageEncoderImpl#transferPendingMessageTo}), for a send that has to wait. Takes the message type of
 * whatever it is handed, so one pool serves every type.
 */
public final class DetachedFixMessageEncoder extends FixMessageEncoderImpl<DetachedFixMessageEncoder> {

    /**
     * @param releaser takes the carrier back once its message is sent
     */
    public DetachedFixMessageEncoder(Consumer<FixMessageEncoder<?>> releaser) {
        super(releaser, null, null, null, null);
    }
}
