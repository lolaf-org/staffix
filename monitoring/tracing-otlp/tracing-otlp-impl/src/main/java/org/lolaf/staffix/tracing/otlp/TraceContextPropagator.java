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
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

import java.util.concurrent.atomic.AtomicReference;

class TraceContextPropagator {

    private final TrappingSpanContext trappingSpanContext;
    private final W3CTraceContextPropagator w3cTraceContextPropagator;
    private final TextMapGetterImpl textMapGetterImpl;
    private final TextMapSetterImpl textMapSetterImpl;

    TraceContextPropagator() {
        this.trappingSpanContext = new TrappingSpanContext();
        this.w3cTraceContextPropagator = W3CTraceContextPropagator.getInstance();
        this.textMapGetterImpl = new TextMapGetterImpl();
        this.textMapSetterImpl = new TextMapSetterImpl();
    }

    SpanContext toSpanContext(String w3cParentTrace) {
        w3cTraceContextPropagator.extract(trappingSpanContext, w3cParentTrace, textMapGetterImpl);
        io.opentelemetry.api.trace.Span propagatedSpan = trappingSpanContext.getTrappedSpan();
        return propagatedSpan.getSpanContext();
    }

    String toW3CTrace(Span span) {
        w3cTraceContextPropagator.inject(trappingSpanContext.setSpan(span), null, textMapSetterImpl);
        return textMapSetterImpl.getW3cTrace();
    }

    private static class TextMapGetterImpl implements TextMapGetter<String> {

        @Override
        public Iterable<String> keys(String carrier) {
            throw new IllegalStateException("should not be called");
        }

        @Override
        public String get(String carrier, String key) {
            if (key.equals("traceparent")) {
                return carrier;
            } else if (key.equals("tracestate")) {
                return null;
            }
            throw new IllegalStateException("unsupported key: " + key);
        }
    }

    private static class TextMapSetterImpl implements TextMapSetter<Void> {

        AtomicReference<String> result = new AtomicReference<>();

        @Override
        public void set(Void carrier, String key, String value) {
            if (key.equals("traceparent")) {
                result.set(value);
            } else if (key.equals("tracestate")) {
                //nothing to do
            } else {
                throw new IllegalStateException("unsupported key: " + key);
            }
        }

        public String getW3cTrace() {
            return result.getAndSet(null);
        }
    }

    private static class TrappingSpanContext implements Context {
        private final AtomicReference<io.opentelemetry.api.trace.Span> span = new AtomicReference<>();

        @Override
        public <V> V get(ContextKey<V> key) {
            if (key.toString().equals("opentelemetry-trace-span-key")) {
                return (V) span.getAndSet(null);
            }
            throw new IllegalStateException("Key not supported " + key);
        }

        @Override
        public <V> Context with(ContextKey<V> k1, V v1) {
            span.set((io.opentelemetry.api.trace.Span) v1);
            return this;
        }

        public io.opentelemetry.api.trace.Span getTrappedSpan() {
            return span.getAndSet(null);
        }

        public TrappingSpanContext setSpan(io.opentelemetry.api.trace.Span span) {
            this.span.set(span);
            return this;
        }
    }
}