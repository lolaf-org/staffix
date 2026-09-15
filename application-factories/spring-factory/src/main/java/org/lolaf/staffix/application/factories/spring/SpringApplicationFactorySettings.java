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
package org.lolaf.staffix.application.factories.spring;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import org.lolaf.staffix.api.application.FixApplicationFactorySettings;
import org.springframework.context.ApplicationContext;

/**
 * The bean name or type each session's application is resolved by.
 */
@Getter
@Builder(toBuilder = true)
public class SpringApplicationFactorySettings implements FixApplicationFactorySettings {

    /**
     * Names this instance, so a session can select it by id when more than one is configured.
     */
    @Builder.Default
    private final String instanceId = DEFAULT_INSTANCE_ID;

    /**
     * The Spring context each session's application bean is resolved from.
     */
    @NonNull
    private final ApplicationContext applicationContext;
}
