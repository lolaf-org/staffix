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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.concurrent.TimeUnit;

/**
 * The tracer used when tracing is off: every method does nothing, so a disabled tracer costs an inlined empty
 * call rather than a branch per message.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class VoidFixTracer implements FixTracer {

    private static final VoidFixTracer INSTANCE = new VoidFixTracer();
    private final VoidSpanBuilder voidSpanBuilder = new VoidSpanBuilder();

    public static VoidFixTracer getInstance() {
        return INSTANCE;
    }

    @Override
    public void enable() {
        // nothing to do
    }

    @Override
    public void disable() {
        // nothing to do
    }

    @Override
    public boolean isEnabled() {
        return false;
    }


    @Override
    public SpanBuilder spanBuilder(String spanName) {
        return voidSpanBuilder;
    }

    @Override
    public Span currentSpan() {
        return Span.getInvalid();
    }

    private static class VoidSpanBuilder implements SpanBuilder {

        @Override
        public SpanBuilder setParent(Context context) {
            return this;
        }

        @Override
        public SpanBuilder setNoParent() {
            return this;
        }

        @Override
        public SpanBuilder addLink(SpanContext spanContext) {
            return this;
        }

        @Override
        public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, String value) {
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, long value) {
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, double value) {
            return this;
        }

        @Override
        public SpanBuilder setAttribute(String key, boolean value) {
            return this;
        }

        @Override
        public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
            return this;
        }

        @Override
        public SpanBuilder setSpanKind(SpanKind spanKind) {
            return this;
        }

        @Override
        public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
            return this;
        }

        @Override
        public Span startSpan() {
            return Span.getInvalid();
        }
    }
}