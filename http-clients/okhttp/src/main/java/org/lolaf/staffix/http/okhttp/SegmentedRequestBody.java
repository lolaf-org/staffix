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
package org.lolaf.staffix.http.okhttp;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;

import java.io.IOException;

/**
 * A payload slice, written into okio one segment at a time, with an optional prefix in front of it.
 *
 * <p><strong>Why this exists rather than {@code RequestBody.create(byte[], MediaType, int, int)}.</strong>
 * That factory issues a single {@code BufferedSink.write(array, offset, length)}, and okio copies the
 * <em>whole</em> body into its segment chain before the one {@code emitCompleteSegments()} that drains
 * it. A 128 KB export therefore needs sixteen 8 KiB segments alive at the same moment, while
 * {@code SegmentPool} pools at most 64 KiB per thread-affine bucket - so every segment past the eighth
 * is a fresh allocation, and the cost grows with the payload rather than staying flat.
 *
 * <p>Writing a segment at a time keeps one complete segment alive, and okio's {@code OutputStreamSink}
 * recycles it before the next is filled. The socket sees the same number of writes either way, because
 * it already drains the chain segment by segment, so this trades no throughput for the allocations it
 * saves. It is also the shape okio's own {@code writeAll} uses.
 *
 * <p>The body stays replayable - {@code isOneShot()} is left at its default {@code false} - so OkHttp
 * can still repeat it when it retries a stale pooled connection. That holds because the arrays are the
 * caller's and stay stable for the duration of a publish.
 */
final class SegmentedRequestBody extends RequestBody {

    /**
     * okio's {@code Segment.SIZE}. Writing in exactly this unit is what keeps the chain from growing.
     */
    private static final int SEGMENT_SIZE = 8192;

    private final MediaType contentType;

    /**
     * Written before the slice, or {@code null}; gRPC's five-byte frame header is the only use.
     */
    private final byte[] prefix;

    private final byte[] data;

    private final int offset;

    private final int length;

    SegmentedRequestBody(MediaType contentType, byte[] data, int offset, int length) {
        this(contentType, null, data, offset, length);
    }

    SegmentedRequestBody(MediaType contentType, byte[] prefix, byte[] data, int offset, int length) {
        this.contentType = contentType;
        this.prefix = prefix;
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() {
        return (prefix == null ? 0L : prefix.length) + length;
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        if (prefix != null) {
            sink.write(prefix);
        }
        int written = 0;
        while (written < length) {
            int chunk = Math.min(SEGMENT_SIZE, length - written);
            // BufferedSink.write emits the segments it completes, so the chain never outgrows two.
            sink.write(data, offset + written, chunk);
            written += chunk;
        }
    }
}
