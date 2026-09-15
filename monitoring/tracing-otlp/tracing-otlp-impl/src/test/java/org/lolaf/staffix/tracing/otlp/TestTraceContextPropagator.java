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

import io.opentelemetry.api.trace.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class TestTraceContextPropagator {

    @Test
    void testFromW3CTraceCreatesSpanWithPropagatedContext() {

        String parentTraceId = TraceId.fromLongs(123456789L, 987654321L);
        String parentSpanId = SpanId.fromLong(111222333L);

        SpanContext spanContext = new TraceContextPropagator().toSpanContext("00-" + parentTraceId + "-" + parentSpanId + "-01");

        assertThat(spanContext).isNotNull();
        assertThat(spanContext.getTraceId()).isEqualTo(parentTraceId);
        assertThat(spanContext.getSpanId()).isNotEmpty();
    }

    @Test
    void testToW3CTrace() {
        Span toExport = Mockito.mock(Span.class);
        SpanContext spanContext = Mockito.mock(SpanContext.class);

        String traceId = TraceId.fromLongs(123456789L, 987654321L);
        String spanId = SpanId.fromLong(111222333L);

        when(toExport.getSpanContext()).thenReturn(spanContext);
        when(spanContext.getTraceId()).thenReturn(traceId);
        when(spanContext.getSpanId()).thenReturn(spanId);
        when(spanContext.isValid()).thenReturn(true);
        when(spanContext.getTraceFlags()).thenReturn(TraceFlags.fromByte((byte) 1));
        when(spanContext.getTraceState()).thenReturn(TraceState.getDefault());

        assertThat(new TraceContextPropagator().toW3CTrace(toExport)).isEqualTo("00-" + spanContext.getTraceId() + "-" + spanContext.getSpanId() + "-01");
    }
}