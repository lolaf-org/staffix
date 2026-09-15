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

import org.lolaf.staffix.api.grpc.GrpcSender;
import org.lolaf.staffix.api.grpc.GrpcSenderSettings;
import org.lolaf.staffix.http.jetty.JettyGrpcSender;

import java.util.function.Function;

/**
 * Points the OTLP tracing exporter at the Jetty gRPC client, for {@code -otr GRPC}.
 *
 * <p>The gRPC counterpart of {@link JettyOtlpHttpSenderFactory}, and it exists for the same reason:
 * OpenTelemetry builds its sender through a {@code ServiceLoader}, which constructs the provider with no
 * arguments, so the client can only be named by a system property - and a property can only name a
 * class.
 *
 * <p>Jetty rather than OkHttp because it is the cheaper of the two on plaintext gRPC, by 2.3x, which is
 * the shape an in-cluster collector takes. The JDK client is not an option at any price: gRPC reports
 * its outcome in HTTP/2 trailers and {@code java.net.http.HttpResponse} exposes none.
 *
 * <p>Unlike the HTTP property, {@code org.lolaf.staffix.otlp.grpcSenderFactory} has <strong>no
 * default</strong>: the tracing sender module depends on no client that can serve gRPC, so it refuses to
 * construct rather than pretending. That refusal surfaces as a startup failure naming the property.
 */
public final class JettyOtlpGrpcSenderFactory implements Function<GrpcSenderSettings, GrpcSender> {

    /**
     * Called reflectively, so this constructor must stay public and take no arguments.
     */
    public JettyOtlpGrpcSenderFactory() {
        // Nothing to configure: the settings arrive with every call to apply.
    }

    @Override
    public GrpcSender apply(GrpcSenderSettings settings) {
        return new JettyGrpcSender(settings);
    }
}
