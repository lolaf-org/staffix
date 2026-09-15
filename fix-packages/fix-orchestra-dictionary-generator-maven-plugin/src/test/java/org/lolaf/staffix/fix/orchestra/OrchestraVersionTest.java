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
package org.lolaf.staffix.fix.orchestra;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * The ladder every cut is made against. The order is derived from the labels rather than enumerated, so these are
 * the cases that say the derivation holds - service packs after their release, FIX.Latest after everything numbered,
 * and an unknown label placed rather than crashing.
 */
public class OrchestraVersionTest {

    /**
     * The ladder the FIX Latest EP300 repository actually uses, in the order the FIX Trading Community published it.
     */
    private static final List<String> LADDER = Arrays.asList(
            "FIX.2.7", "FIX.3.0", "FIX.4.0", "FIX.4.1", "FIX.4.2", "FIX.4.3", "FIX.4.4",
            "FIX.5.0", "FIX.5.0SP1", "FIX.5.0SP2", "FIX.Latest");

    @Test
    public void testTheLadderSortsInPublicationOrder() {
        List<OrchestraVersion> shuffled = LADDER.stream().map(OrchestraVersion::of).collect(Collectors.toList());
        Collections.shuffle(shuffled);
        List<String> sorted = shuffled.stream().sorted().map(OrchestraVersion::getLabel).collect(Collectors.toList());
        assertEquals(LADDER, sorted);
    }

    @Test
    public void testAServicePackComesAfterItsRelease() {
        assertTrue(OrchestraVersion.of("FIX.5.0").compareTo(OrchestraVersion.of("FIX.5.0SP1")) < 0);
        assertTrue(OrchestraVersion.of("FIX.5.0SP1").compareTo(OrchestraVersion.of("FIX.5.0SP2")) < 0);
        assertTrue(OrchestraVersion.of("FIX.4.4").compareTo(OrchestraVersion.of("FIX.5.0")) < 0);
    }

    @Test
    public void testLatestComesAfterEveryNumberedVersion() {
        OrchestraVersion latest = OrchestraVersion.of("FIX.Latest");
        assertTrue(latest.isLatest());
        assertTrue(latest.isKnown());
        for (String label : LADDER.subList(0, LADDER.size() - 1)) {
            assertTrue(label + " must precede FIX.Latest", OrchestraVersion.of(label).compareTo(latest) < 0);
        }
    }

    /**
     * The FIX Trading Community names versions on its own schedule, and an orchestration is a file this plugin is
     * handed rather than one it owns. An unrecognised label therefore has to sort somewhere - last, so a cut cannot
     * quietly include what it could not place - rather than end the build.
     */
    @Test
    public void testAnUnknownLabelSortsLastAndSaysSo() {
        OrchestraVersion unknown = OrchestraVersion.of("FIX.SomethingNew");
        assertFalse(unknown.isKnown());
        assertTrue(OrchestraVersion.of("FIX.Latest").compareTo(unknown) < 0);
        assertTrue(OrchestraVersion.of("FIX.2.7").compareTo(unknown) < 0);

        List<OrchestraVersion> versions = new ArrayList<>(
                Arrays.asList(unknown, OrchestraVersion.of("FIX.Latest"), OrchestraVersion.of("FIX.4.4")));
        Collections.sort(versions);
        assertEquals(Arrays.asList("FIX.4.4", "FIX.Latest", "FIX.SomethingNew"),
                versions.stream().map(OrchestraVersion::getLabel).collect(Collectors.toList()));
    }

    @Test
    public void testTwoLabelsOfTheSameVersionAreTheSameVersion() {
        assertEquals(OrchestraVersion.of("FIX.4.4"), OrchestraVersion.of("FIX.4.4"));
        assertEquals(OrchestraVersion.of("FIX.4.4").hashCode(), OrchestraVersion.of("FIX.4.4").hashCode());
        assertEquals(0, OrchestraVersion.of("FIX.5.0SP2").compareTo(OrchestraVersion.of("FIX.5.0SP2")));
    }
}
