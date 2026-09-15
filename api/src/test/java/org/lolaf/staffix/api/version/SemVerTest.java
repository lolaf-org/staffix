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
package org.lolaf.staffix.api.version;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemVerTest {

    @Test
    void testBasicVersion() {
        SemVer sv = SemVer.from("1.2.3");
        assertEquals(1, sv.getMajor());
        assertEquals(2, sv.getMinor());
        assertEquals(3, sv.getPatch());
        assertNull(sv.getMetadata());
    }

    @Test
    void testVersionWithMetadata() {
        SemVer sv = SemVer.from("2.0.0+build.123");
        assertEquals(2, sv.getMajor());
        assertEquals(0, sv.getMinor());
        assertEquals(0, sv.getPatch());
        assertEquals("build.123", sv.getMetadata());

        sv = SemVer.from("2.0.0-build.123");
        assertEquals(2, sv.getMajor());
        assertEquals(0, sv.getMinor());
        assertEquals(0, sv.getPatch());
        assertEquals("build.123", sv.getMetadata());
    }

    @Test
    void testZeroVersion() {
        SemVer sv = SemVer.from("0.0.0");
        assertEquals(0, sv.getMajor());
        assertEquals(0, sv.getMinor());
        assertEquals(0, sv.getPatch());
        assertNull(sv.getMetadata());
    }

    @Test
    void testLargeVersionNumbers() {
        SemVer sv = SemVer.from("99.88.77");
        assertEquals(99, sv.getMajor());
        assertEquals(88, sv.getMinor());
        assertEquals(77, sv.getPatch());
    }

    @Test
    void testToStringBasic() {
        SemVer sv = SemVer.from("1.2.3");
        assertEquals("1.2.3", sv.toString());
    }

    @Test
    void testToStringWithMetadata() {
        SemVer sv = SemVer.from("1.0.0+build.456");
        assertEquals("1.0.0-build.456", sv.toString());
    }

    @Test
    void testInvalidVersionNoNumbers() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.from("abc.def.ghi"));
    }

    @Test
    void testInvalidVersionTooFewParts() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.from("1.2"));
    }

    @Test
    void testInvalidVersionTooManyParts() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.from("1.2.3.4"));
    }

    @Test
    void testInvalidVersionWithPrerelease() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.from("1.0.0|alpha"));
    }

    @Test
    void testVersionWithWhitespace() {
        SemVer sv = SemVer.from("  1.2.3  ");
        assertEquals(1, sv.getMajor());
        assertEquals(2, sv.getMinor());
        assertEquals(3, sv.getPatch());
    }

    @Test
    void testComplexMetadata() {
        SemVer sv = SemVer.from("1.2.3+build.20230101.abc");
        assertEquals("build.20230101.abc", sv.getMetadata());
    }

}