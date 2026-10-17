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
package org.lolaf.staffix.stores.loggers.file;

import org.jspecify.annotations.NullMarked;
import org.lolaf.staffix.api.msg.MessageTypeRegistry;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.concurrent.TimeUnit;

/**
 * The layout of the lines the file messages logger writes. Stateless and shared by every session: each session gets
 * its own {@link FileLogLineFormatter}, so what is constant for a session is worked out once rather than per line.
 */
@NullMarked
public interface FileLogLineFormat {

    /**
     * @param fixEngineId       the engine's instance id
     * @param fixInstanceId     the instance id of the initiator or acceptor running the session
     * @param logTimePrecision  the precision each line's time is written to
     */
    FileLogLineFormatter newFormatter(String fixEngineId, String fixInstanceId, FixSessionId fixSessionId,
                                      MessageTypeRegistry messageTypeRegistry, TimeUnit logTimePrecision);
}
