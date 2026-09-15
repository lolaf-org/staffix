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
/**
 * The gRPC half of the sender abstraction, for collectors that speak OTLP over gRPC.
 *
 * <p>Only the OkHttp and Jetty clients can implement it: gRPC needs HTTP/2 trailers, which the JDK client does
 * not expose.
 */
package org.lolaf.staffix.api.grpc;
