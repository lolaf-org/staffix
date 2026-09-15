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

import java.io.File;
import java.net.URL;

import static org.junit.Assert.*;

/**
 * The two ways a {@code (upToVersion, upToExtensionPack)} pair can name no point of the standard's history, against
 * the fixture ladder: FIX.4.4 ends at EP38 in it, FIX.5.0SP2 runs EP161 and FIX.Latest EP300.
 * <p>
 * Both matter because both produce a dictionary rather than a failure: too low silently loses content the version
 * had, too high silently produces the cut the ceiling was meant to narrow. Neither is visible in the result.
 */
public class ExtensionPackLadderTest {

    private static ExtensionPackLadder ladder() throws Exception {
        URL resource = ExtensionPackLadderTest.class.getClassLoader().getResource("orchestration-fixture.xml");
        return ExtensionPackLadder.of(OrchestraRepository.load(new File(resource.toURI())));
    }

    private static OrchestraVersion version(String label) {
        return OrchestraVersion.of(label);
    }

    /**
     * The fixture stamps nothing of FIX.4.4 with an extension pack, its 40000 block with EP161 at FIX.5.0SP2 and its
     * newest elements with EP300 at FIX.Latest - so those are the ends the ladder has to find.
     */
    @Test
    public void testTheLadderIsReadOffTheRepository() throws Exception {
        ExtensionPackLadder ladder = ladder();

        assertNull("no extension pack targeted FIX.4.4 in the fixture", ladder.lastExtensionPackOf(version("FIX.4.4")));
        assertEquals(Integer.valueOf(161), ladder.lastExtensionPackOf(version("FIX.5.0SP2")));
        assertEquals(Integer.valueOf(300), ladder.lastExtensionPackOf(version("FIX.Latest")));
    }

    /**
     * "As published" is the last extension pack of everything before the version, because all of it was already in
     * force when the version shipped. FIX.Latest comes after EP161, so a FIX.Latest release cut starts there and not
     * at zero.
     */
    @Test
    public void testAsPublishedIsWhereTheVersionBeforeItEnded() throws Exception {
        ExtensionPackLadder ladder = ladder();

        assertEquals("nothing precedes FIX.4.4 with an extension pack", 0, ladder.asPublishedCeiling(version("FIX.4.4")));
        assertEquals(0, ladder.asPublishedCeiling(version("FIX.5.0SP2")));
        assertEquals(161, ladder.asPublishedCeiling(version("FIX.Latest")));
    }

    /**
     * Below the floor the cut reaches back past the version being asked for, and the dictionary carries that
     * version's number anyway - which is the failure that cost a FIX.5.0SP2 cut 517 of its fields before anyone
     * noticed. The message has to carry the number to use instead, since it is not guessable from the ladder.
     */
    @Test
    public void testACeilingBelowTheVersionsOwnStartIsRejected() throws Exception {
        String rejection = ladder().rejectionOf(version("FIX.Latest"), 0);

        assertNotNull(rejection);
        assertTrue(rejection, rejection.contains("below EP161"));
        assertTrue("it names the ceiling that was meant: " + rejection, rejection.contains("upToExtensionPack=161"));
    }

    /**
     * Past the version's own end the ceiling excludes nothing, because the version ceiling already removed
     * everything above it. Harmless in what it produces and wrong in what it says: EP39 of FIX.4.4 does not exist.
     */
    @Test
    public void testACeilingPastTheVersionsOwnEndIsRejected() throws Exception {
        String rejection = ladder().rejectionOf(version("FIX.5.0SP2"), 200);

        assertNotNull(rejection);
        assertTrue(rejection, rejection.contains("past EP161"));
    }

    /**
     * A version no extension pack ever targeted takes exactly one ceiling, the one that means "as published".
     */
    @Test
    public void testAVersionWithoutExtensionPacksTakesOnlyItsPublishedCeiling() throws Exception {
        assertNull(ladder().rejectionOf(version("FIX.4.4"), 0));
        assertNotNull(ladder().rejectionOf(version("FIX.4.4"), 39));
        assertTrue(ladder().rejectionOf(version("FIX.4.4"), 39).contains("No extension pack ever targeted"));
    }

    @Test
    public void testTheCutsThisProjectActuallyMakesAreAccepted() throws Exception {
        ExtensionPackLadder ladder = ladder();

        assertNull("FIX.5.0SP2 as published", ladder.rejectionOf(version("FIX.5.0SP2"), 0));
        assertNull("FIX.5.0SP2 as amended", ladder.rejectionOf(version("FIX.5.0SP2"), 161));
        assertNull("FIX.Latest as published", ladder.rejectionOf(version("FIX.Latest"), 161));
        assertNull("FIX.Latest as amended", ladder.rejectionOf(version("FIX.Latest"), 300));
    }
}
