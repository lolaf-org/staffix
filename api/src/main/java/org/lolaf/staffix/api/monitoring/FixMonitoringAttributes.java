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
 * The tag keys the engine puts on its meters - session, direction, message type.
 *
 * <p>Shared with the loggers and the tracing plugin so one query selects the same session across metrics, logs
 * and traces.
 */
@UtilityClass
public class FixMonitoringAttributes {

    public static final String FIX_INSTANCE_ID = "fix.iid";
    public static final String FIX_SESSION_ID = "fix.sid";
    public static final String FIX_SESSION_GROUP_ID = "fix.sgid";
    public static final String FIX_MESSAGE_TYPE = "fix.msg.type";
    public static final String FIX_MESSAGE_DIRECTION = "fix.msg.dir";

}