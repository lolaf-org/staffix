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
package org.lolaf.staffix.examples.plugins;

import lombok.Builder;
import lombok.Getter;
import org.lolaf.staffix.api.InstanceProvider;
import org.lolaf.staffix.api.msg.MessageType;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;

import java.util.function.Supplier;

/**
 * How the {@link StampingFixSessionsPlugin} is configured, and — through the factory at the bottom — how the engine
 * gets from these settings to an instance of it.
 *
 * <p>Every pluggable part of Staffix is registered this way: a settings object handed to
 * {@code FixEngineBuilder.fixSessionsPlugin(…)}, and an implementation the engine finds through the
 * {@code ServiceLoader}. The declaration lives in
 * {@code src/main/resources/META-INF/services/org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings$FixSessionsPluginFactory}
 * — without that file the engine throws {@code Unable to find any SPI instance for target settings class} at startup.
 */
@Getter
@Builder(toBuilder = true)
public class StampingFixSessionsPluginSettings implements FixSessionsPluginSettings<StampingFixSessionsPlugin> {

    /**
     * The id a session names in {@code fixSessionPluginsInstanceId(StampingFixSessionsPlugin.class, …)}.
     */
    @Builder.Default
    private final String instanceId = InstanceProvider.DEFAULT_INSTANCE_ID;

    /**
     * The message type to stamp. Sessions that do not encode it are declined outright, so they never pay for a
     * plugin they have no use for.
     */
    private final MessageType stampedMessageType;

    /**
     * The tag the stamp is written under. Must be in the user defined range 5000..39999, see
     * {@link org.lolaf.staffix.api.fields.FixField#isUserDefined(int)} — a tag the standard owns is refused by the
     * fields registry rather than registered.
     */
    private final int stampFieldCode;

    /**
     * Produces the value to stamp, and is called on the thread that produced the message, from
     * {@link org.lolaf.staffix.api.session.plugins.FixSessionPlugin#getMessageEncodingToken} — which is the point of
     * it. Anything the stamp depends on that only the producing thread knows (a thread local, the current span, the
     * caller's identity) has to be read there rather than when the message is encoded on the I/O thread.
     *
     * <p>Being shared by every session, and called from whatever application thread is sending,
     * <b>it must be thread-safe</b>. Returning {@code null} means "nothing to stamp on this message".
     */
    private final Supplier<String> stampSupplier;

    /**
     * The SPI factory the {@code ServiceLoader} finds. {@code getSettingsClass} is how the engine matches a settings
     * instance to the factory that can build its plugin.
     */
    public static class StampingFixSessionsPluginFactory
            implements FixSessionsPluginFactory<StampingFixSessionsPluginSettings> {

        @Override
        public Class<StampingFixSessionsPluginSettings> getSettingsClass() {
            return StampingFixSessionsPluginSettings.class;
        }

        @Override
        public FixSessionsPlugin<?> newInstance(StampingFixSessionsPluginSettings settings) {
            return new StampingFixSessionsPlugin(settings);
        }
    }
}
