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
package org.lolaf.staffix.examples;

import org.lolaf.staffix.api.http.HttpSender;
import org.lolaf.staffix.api.http.HttpSenderSettings;
import org.lolaf.staffix.http.jetty.JettyHttpSender;

import java.util.function.Function;

/**
 * Points the OTLP tracing exporters at the Jetty HTTP client, so that all three OTLP paths in these
 * examples - logs, metrics and traces - publish through the same one.
 *
 * <p>Logs and metrics take their client as a {@code httpSenderFactory} on their settings, which is a
 * method reference. Tracing cannot: OpenTelemetry builds its sender through a {@code ServiceLoader},
 * which constructs the provider with no arguments and so leaves nowhere to pass a factory in. The client
 * is named by the
 * {@code org.lolaf.staffix.tracing.otlp.sender.StaffixHttpSenderProvider#FACTORY_PROPERTY}
 * system property instead, and a property can only name a class - which is what this is.
 *
 * <p>A deployment would set that property on the command line. These examples set it in
 * {@link FixExamplesBase} instead, so that running one from an IDE behaves the same as running it from
 * its script; an explicit {@code -D} still wins.
 */
public final class JettyOtlpHttpSenderFactory implements Function<HttpSenderSettings, HttpSender> {

    /**
     * Called reflectively, so this constructor must stay public and take no arguments.
     */
    public JettyOtlpHttpSenderFactory() {
        // Nothing to configure: the settings arrive with every call to apply.
    }

    @Override
    public HttpSender apply(HttpSenderSettings settings) {
        return new JettyHttpSender(settings);
    }
}
