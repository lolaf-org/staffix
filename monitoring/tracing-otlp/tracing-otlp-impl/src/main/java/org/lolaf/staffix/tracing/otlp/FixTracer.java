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
package org.lolaf.staffix.tracing.otlp;


import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.lolaf.staffix.api.session.plugins.PluginContext;

/**
 * The tracer a session holds, which can be turned off at runtime.
 *
 * <p>Enabling and disabling is on the interface because tracing every message is rarely what is wanted - it is
 * switched on to investigate something and off again.
 */
public interface FixTracer extends Tracer, PluginContext {

    void enable();

    void disable();

    boolean isEnabled();

    Span currentSpan();
}
