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

import lombok.experimental.UtilityClass;
import org.lolaf.replicos.spi.StreamAnswer;

/**
 * A follower's answer as the status and alerts name it, in terms of FIX sessions rather than the replication's
 * streams.
 */
@UtilityClass
public class NotReplicatedReason {

    public static String of(StreamAnswer answer) {
        return answer == StreamAnswer.UNKNOWN_STREAM ? "UNKNOWN_SESSION" : answer.name();
    }
}
