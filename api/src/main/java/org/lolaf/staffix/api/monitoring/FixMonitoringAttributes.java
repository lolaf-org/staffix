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
package org.lolaf.staffix.api.monitoring;

import lombok.experimental.UtilityClass;

/**
 * The attribute keys staffix puts on its meters, message log records and trace spans.
 *
 * <p>The same keys are used everywhere, so one query selects the same session across metrics, logs and traces.
 */
@UtilityClass
public class FixMonitoringAttributes {

    /**
     * The engine's {@link org.lolaf.staffix.api.FixEngineBuilder#getInstanceId() instance id}. With the session's
     * group and name, it tells a session apart from a same-named one in another engine.
     */
    public static final String FIX_ENGINE_ID = "fix.eid";
    /**
     * The id of the initiator or acceptor instance managing the session.
     */
    public static final String FIX_INSTANCE_ID = "fix.iid";
    /**
     * The session's {@link org.lolaf.staffix.api.session.FixSessionId#getName() name}, unique only within its group.
     */
    public static final String FIX_SESSION_NAME = "fix.sn";
    /**
     * The session's {@link org.lolaf.staffix.api.session.FixSessionId#getGroup() group}.
     */
    public static final String FIX_SESSION_GROUP = "fix.sg";
    /**
     * The message's MsgType(35) value, e.g. {@code D}.
     */
    public static final String FIX_MESSAGE_TYPE = "fix.msg.type";
    /**
     * Whether the message was received or sent: {@code in} or {@code out}.
     */
    public static final String FIX_MESSAGE_DIRECTION = "fix.msg.dir";

}