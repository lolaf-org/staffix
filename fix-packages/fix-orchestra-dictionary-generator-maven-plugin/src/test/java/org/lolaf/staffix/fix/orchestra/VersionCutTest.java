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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What the two ceilings mean on their own and together, which is the whole of the cut.
 */
public class VersionCutTest {

    private static final OrchestraVersion FIX_4_4 = OrchestraVersion.of("FIX.4.4");
    private static final OrchestraVersion FIX_5_0SP2 = OrchestraVersion.of("FIX.5.0SP2");
    private static final OrchestraVersion LATEST = OrchestraVersion.of("FIX.Latest");

    @Test
    public void testNoCeilingKeepsEverything() {
        assertTrue(VersionCut.ALL.keeps(LATEST, 300));
        assertTrue(VersionCut.ALL.keeps(FIX_4_4, null));
    }

    /**
     * A version ceiling on its own is "that version with every extension pack that targeted it": the extension packs
     * belong to the version they amend, so keeping the version keeps them.
     */
    @Test
    public void testAVersionCeilingKeepsThatVersionsExtensionPacks() {
        VersionCut cut = VersionCut.of(FIX_5_0SP2, null);
        assertTrue("the base release", cut.keeps(FIX_5_0SP2, null));
        assertTrue("EP161, which opened the 40000+ block on 5.0SP2", cut.keeps(FIX_5_0SP2, 161));
        assertTrue("an earlier version", cut.keeps(FIX_4_4, 29));
        assertFalse("FIX.Latest is past the ceiling", cut.keeps(LATEST, 300));
    }

    /**
     * The one cut that reproduces a release as it was published, and the reason the ceiling has to be explicit: had
     * a missing extension pack meant "base release only", an unqualified FIX.Latest would generate nothing at all,
     * that version having no base elements whatsoever.
     */
    @Test
    public void testExtensionPackZeroIsTheReleaseAsPublished() {
        VersionCut cut = VersionCut.of(FIX_4_4, 0);
        assertTrue(cut.keeps(FIX_4_4, null));
        assertFalse(cut.keeps(FIX_4_4, 1));
        assertFalse(cut.keeps(FIX_4_4, 38));
    }

    /**
     * Naming both only says something new inside a version, and this is that case: 5.0SP2 with the tag block EP161
     * opened, and nothing published after it.
     */
    @Test
    public void testAnExtensionPackCeilingCutsInsideAVersion() {
        VersionCut cut = VersionCut.of(FIX_5_0SP2, 161);
        assertTrue(cut.keeps(FIX_5_0SP2, 161));
        assertTrue(cut.keeps(FIX_5_0SP2, 98));
        assertFalse(cut.keeps(FIX_5_0SP2, 162));
        assertFalse(cut.keeps(LATEST, 300));
    }

    /**
     * An element of a base release carries no extension pack, and precedes every extension pack of its version, so
     * an extension pack ceiling never touches it. The old versions have nothing else - FIX.2.7 to FIX.4.3 were
     * published before extension packs existed.
     */
    @Test
    public void testAnExtensionPackCeilingLeavesBaseElementsAlone() {
        VersionCut cut = VersionCut.of(null, 100);
        assertTrue(cut.keeps(OrchestraVersion.of("FIX.2.7"), null));
        assertTrue(cut.keeps(FIX_4_4, null));
        assertTrue(cut.keeps(FIX_5_0SP2, 98));
        assertFalse(cut.keeps(FIX_5_0SP2, 101));
    }

    /**
     * An element the repository forgot to stamp. FIX Latest EP300 has exactly one, the MsgType code BQ
     * SettlementObligationReport, and a cut that threw on it would be unusable against the file it exists to read.
     */
    @Test
    public void testSomethingWithNoVersionIsKept() {
        VersionCut cut = VersionCut.of(FIX_4_4, null);
        assertTrue(cut.keeps(null, null));
        assertFalse("an extension pack stamp still places it", VersionCut.of(FIX_4_4, 0).keeps(null, 161));
    }

    @Test
    public void testDeprecationIsTheSameQuestionAskedOfTheDeprecation() {
        VersionCut cut = VersionCut.of(FIX_5_0SP2, 161);
        assertFalse("never deprecated", cut.isAlreadyDeprecated(null, null));
        assertTrue("deprecated before the cut", cut.isAlreadyDeprecated(FIX_4_4, null));
        assertTrue("deprecated at the cut", cut.isAlreadyDeprecated(FIX_5_0SP2, 161));
        assertFalse("deprecated after the cut, so still current at it", cut.isAlreadyDeprecated(LATEST, 300));
    }
}
