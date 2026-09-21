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
package org.lolaf.staffix.spring.boot.props;

import lombok.Data;

/**
 * Engine-level configuration: which stores, loggers, plugins and application factories exist, and the instance
 * ids a session names them by.
 */
@Data
public class EngineProps {

    /**
     * Names the engine, so more than one in a process can be told apart.
     */
    private String instanceId = "default";

    /**
     * Spring bean name of the {@link java.util.concurrent.ExecutorService} running the work of sessions that have
     * no connection, which <b>must be single threaded</b>. Left unset, the engine makes a thread of its own and
     * shuts it down with itself; naming a bean pools that work with the rest of the application's threads and
     * leaves its lifecycle to the application.
     */
    private String disconnectedSessionsExecutorBean;

}