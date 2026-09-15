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
package com.google.protobuf;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PooledBytesStringTest {

    private static final byte SEP = (byte) '\001';
    private static final byte PIPE = (byte) '|';

    private static ByteBuffer wrap(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fillsWithoutResizeWhenMessageFits() {
        PooledBytesString bs = new PooledBytesString(32);

        boolean reused = bs.setBytes(wrap("hello world"), SEP, SEP);

        assertThat(reused).isTrue();
        assertThat(bs.size()).isEqualTo(11);
        assertThat(bs.toStringUtf8()).isEqualTo("hello world");
    }

    @Test
    void replacesFieldSeparatorWithReplacement() {
        PooledBytesString bs = new PooledBytesString(32);

        bs.setBytes(wrap("test\001event\001replaced\001"), SEP, PIPE);

        assertThat(bs.toStringUtf8()).isEqualTo("test|event|replaced|");
        assertThat(bs.size()).isEqualTo(20);
    }

    @Test
    void doesNotTouchBytesWhenReplacementEqualsSeparator() {
        PooledBytesString bs = new PooledBytesString(32);

        bs.setBytes(wrap("keep\001the\001separators"), SEP, SEP);

        assertThat(bs.toByteArray()).isEqualTo("keep\001the\001separators".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void growsBackingArrayWhenMessageExceedsCapacity() {
        PooledBytesString bs = new PooledBytesString(4);
        String big = "a message far larger than four bytes";

        boolean reused = bs.setBytes(wrap(big), SEP, SEP);

        assertThat(reused).isFalse();
        assertThat(bs.size()).isEqualTo(big.length());
        assertThat(bs.toStringUtf8()).isEqualTo(big);
    }

    @Test
    void growsThenReplacesFieldSeparator() {
        PooledBytesString bs = new PooledBytesString(4);

        boolean reused = bs.setBytes(wrap("a\001b\001c\001d\001e\001f"), SEP, PIPE);

        assertThat(reused).isFalse();
        assertThat(bs.toStringUtf8()).isEqualTo("a|b|c|d|e|f");
    }

    @Test
    void reusesGrownArrayForSubsequentSmallerMessages() {
        PooledBytesString bs = new PooledBytesString(4);

        // first message grows the array
        assertThat(bs.setBytes(wrap("first long message"), SEP, SEP)).isFalse();

        // a shorter message now fits into the grown array without another resize
        boolean reused = bs.setBytes(wrap("short"), SEP, SEP);

        assertThat(reused).isTrue();
        // size must reflect the new (shorter) length, not stale trailing bytes
        assertThat(bs.size()).isEqualTo(5);
        assertThat(bs.toStringUtf8()).isEqualTo("short");
    }

    @Test
    void copiesOnlyRemainingBytesOfBuffer() {
        PooledBytesString bs = new PooledBytesString(32);
        ByteBuffer buffer = wrap("PREFIXpayload");
        buffer.position(6); // skip "PREFIX"

        boolean reused = bs.setBytes(buffer, SEP, SEP);

        assertThat(reused).isTrue();
        assertThat(bs.size()).isEqualTo(7);
        assertThat(bs.toStringUtf8()).isEqualTo("payload");
    }
}
