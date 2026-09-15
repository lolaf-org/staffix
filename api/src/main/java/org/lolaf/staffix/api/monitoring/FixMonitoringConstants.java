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
 * The meter names the engine publishes, fixed here so a dashboard built against one backend keeps working
 * against another.
 */
@UtilityClass
public class FixMonitoringConstants {

    public static final String OTLP_TELEMETRY_SDK_NAME = "telemetry.sdk.name";
    public static final String OTLP_TELEMETRY_SDK_LANGUAGE = "telemetry.sdk.language";
    public static final String OTLP_TELEMETRY_SDK_VERSION = "telemetry.sdk.version";
    public static final String OTLP_SERVICE_NAME = "service.name";
    public static final String OTLP_SERVICE_INSTANCE_ID = "service.instance.id";
    public static final String OTLP_SERVICE_VERSION = "service.version";
}
