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
package org.lolaf.staffix.api.session.plugins;

/**
 * What a plugin keeps per session, created when the session is and handed back on every callback.
 *
 * <p>Held by the engine rather than looked up by the plugin, so a callback on the message path costs no map
 * lookup.
 */
public interface PluginContext {
    // marker interface

    class VoidPluginContext implements PluginContext {

    }
}
