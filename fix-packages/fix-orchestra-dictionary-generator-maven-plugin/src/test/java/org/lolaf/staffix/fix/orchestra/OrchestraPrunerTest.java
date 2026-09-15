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
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * The cut and the closure that follows it, against {@code orchestration-fixture.xml} - small enough that every
 * expectation below can be read off the file by hand.
 */
public class OrchestraPrunerTest {

    private static OrchestraRepository fixture() throws Exception {
        return fixture("orchestration-fixture.xml");
    }

    private static OrchestraRepository fixture(String name) throws Exception {
        URL resource = OrchestraPrunerTest.class.getClassLoader().getResource(name);
        return OrchestraRepository.load(new File(resource.toURI()));
    }

    private static List<String> namesOf(OrchestraRepository repository, String elementName) {
        return repository.elementsByTagName(elementName).stream()
                .map(element -> element.getAttribute("name"))
                .collect(Collectors.toList());
    }

    private static List<String> idsOf(OrchestraRepository repository, String elementName) {
        return repository.elementsByTagName(elementName).stream()
                .map(element -> element.getAttribute("id"))
                .collect(Collectors.toList());
    }

    @Test
    public void testNoCutChangesNothing() throws Exception {
        OrchestraRepository repository = fixture();
        PruneReport report = new OrchestraPruner(repository, VersionCut.ALL, true).prune();

        assertTrue(report.describe(), report.isEmpty());
        assertEquals(11, repository.elementsByTagName("fixr:field").size());
        assertEquals(4, repository.elementsByTagName("fixr:message").size());
    }

    /**
     * The published repositories spell a base element two ways - no {@code addedEP} at all, or {@code addedEP="-1"}
     * - and FIX Latest EP300 uses both, 315 elements carrying the second. They have to mean the same thing, or a
     * release-as-published cut drops a third of FIX.4.4's codes.
     */
    @Test
    public void testAnExtensionPackOfMinusOneIsABaseElement() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), 0), true).prune();

        assertTrue("addedEP=-1 is the base release of FIX.4.4, so a base cut keeps it",
                idsOf(repository, "fixr:field").contains("2"));
    }

    /**
     * The plain case: everything stamped after the cut goes, and nothing that was there before it does.
     */
    @Test
    public void testACutDropsWhatCameLater() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null), true).prune();

        assertEquals(List.of("1", "2", "54", "555", "600", "1234", "34", "10"), idsOf(repository, "fixr:field"));
        assertFalse("the 40000+ block arrived at EP161, past a 4.4 cut",
                idsOf(repository, "fixr:field").contains("40000"));
        assertEquals("codes added later go with their version, and Oldway is 4.2 - deprecated in 4.4, which is not "
                        + "the same as absent",
                3, repository.elementsByTagName("fixr:code").size());
    }

    /**
     * A field going takes with it every reference to it, wherever they are - and a component made only of such
     * references goes too, along with the reference that pointed at that component. Three levels of cascade, which
     * is what the fixed point loop is for.
     */
    @Test
    public void testWhatIsLeftDanglingGoesToo() throws Exception {
        OrchestraRepository repository = fixture();
        PruneReport report = new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null), true).prune();

        // LateOnly held nothing but a reference to a field added at EP161
        assertEquals(List.of("StandardHeader", "StandardTrailer", "Instrument"), namesOf(repository, "fixr:component"));
        // and the message that referenced LateOnly no longer does
        List<String> messageRefs = repository.elementsByTagName("fixr:componentRef").stream()
                .map(reference -> reference.getAttribute("id"))
                .collect(Collectors.toList());
        assertEquals(List.of("1024", "1003", "1025"), messageRefs);
        assertTrue(report.describe(), report.count(PruneReport.Reason.DANGLING) > 0);
    }

    /**
     * A repeating group is its NumInGroup field and what it counts. LateGrp counts with a field added at EP161, so
     * a 4.4 cut leaves it countless and it cannot be expressed as a group at all.
     */
    @Test
    public void testAGroupWithoutItsCounterGoes() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null), true).prune();

        assertEquals(List.of("InstrmtLegGrp"), namesOf(repository, "fixr:group"));
        assertEquals("the reference to it goes with it", List.of("2019"), idsOf(repository, "fixr:groupRef"));
    }

    /**
     * A message whose whole structure went is not a message any more. The one that survives keeps the references
     * that were valid at the cut.
     */
    @Test
    public void testAMessageLeftEmptyGoes() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null), true).prune();

        assertEquals(List.of("Logon", "AppMessage"), namesOf(repository, "fixr:message"));
    }

    /**
     * Deprecated is not the same question as added: what matters is whether the deprecation had happened at the cut.
     * Retired was deprecated by EP41, so a 4.4 cut still has it current while a 5.0 cut does not.
     */
    @Test
    public void testDeprecatedElementsGoOnlyOnceTheyHaveBeenDeprecated() throws Exception {
        OrchestraRepository beforeTheDeprecation = fixture();
        new OrchestraPruner(beforeTheDeprecation, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null), false).prune();
        assertTrue("deprecated at 5.0, so still current at a 4.4 cut",
                idsOf(beforeTheDeprecation, "fixr:field").contains("1234"));

        OrchestraRepository afterTheDeprecation = fixture();
        PruneReport report = new OrchestraPruner(afterTheDeprecation,
                VersionCut.of(OrchestraVersion.of("FIX.5.0"), null), false).prune();
        assertFalse(idsOf(afterTheDeprecation, "fixr:field").contains("1234"));
        assertEquals(1, report.count(PruneReport.Reason.DEPRECATED, "fixr:field"));
        assertFalse("and the reference to it goes with it",
                idsOf(afterTheDeprecation, "fixr:fieldRef").contains("1234"));
    }

    @Test
    public void testDeprecatedElementsAreKeptWhenAskedFor() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.5.0"), null), true).prune();

        assertTrue(idsOf(repository, "fixr:field").contains("1234"));
    }

    /**
     * The release as published, which is the cut that reproduces the classic dictionaries: base elements only, none
     * of the extension packs that amended the version afterwards.
     */
    @Test
    public void testExtensionPackZeroKeepsTheBaseReleaseOnly() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.Latest"), 0), true).prune();

        List<String> codes = repository.elementsByTagName("fixr:code").stream()
                .map(element -> element.getAttribute("name"))
                .collect(Collectors.toList());
        // Oldway is deprecated rather than late, and this cut was asked to keep deprecated elements
        assertEquals("Undisclosed came with EP161 and Brandnew with EP300",
                List.of("Buy", "Sell", "Oldway"), codes);
        assertFalse(idsOf(repository, "fixr:field").contains("40000"));
    }

    /**
     * A cut inside a version: 5.0SP2 up to EP161 keeps the tag block the Global Technical Committee opened there and
     * nothing published after it.
     */
    @Test
    public void testACutInsideAVersionKeepsThatVersionsEarlierExtensionPacks() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.5.0SP2"), 161), true).prune();

        List<String> fields = idsOf(repository, "fixr:field");
        assertTrue("EP161 is at the cut", fields.contains("40000"));
        assertTrue(fields.contains("40001"));
        assertFalse("EP300 is past it", fields.contains("42087"));
        assertEquals("the message made only of a reference to it goes", List.of("Logon", "AppMessage"),
                namesOf(repository, "fixr:message"));
    }

    @Test
    public void testAFieldOlderThanItsCodeSetIsRetypedRatherThanLeftDangling() throws Exception {
        OrchestraRepository repository = fixture("orchestration-orphaned-codeset-fixture.xml");
        OrchestraPruner pruner = new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null), true);
        pruner.prune();

        assertEquals("the code set arrived at FIX.5.0 EP52", 0, repository.elementsByTagName("fixr:codeSet").size());
        List<String> fields = idsOf(repository, "fixr:field");
        assertTrue("the field itself is dated FIX.4.4 and stays", fields.contains("1039"));
        assertEquals("typed by what the code set was built on", "String",
                repository.elementsByTagName("fixr:field").stream()
                        .filter(field -> "1039".equals(field.getAttribute("id")))
                        .findFirst().orElseThrow(AssertionError::new)
                        .getAttribute("type"));
        assertEquals("and it says so, since the dictionary is now less precise than the repository",
                1, pruner.getWarnings().size());
        assertTrue(pruner.getWarnings().get(0), pruner.getWarnings().get(0).contains("UnderlyingSettlMethod"));
    }

    /**
     * The other side of it: a cut that keeps both leaves the type alone, so nothing is being retyped on the way past.
     */
    @Test
    public void testAFieldKeepsItsCodeSetWhenTheCutKeepsBoth() throws Exception {
        OrchestraRepository repository = fixture("orchestration-orphaned-codeset-fixture.xml");
        OrchestraPruner pruner = new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.5.0"), null), true);
        pruner.prune();

        assertEquals(1, repository.elementsByTagName("fixr:codeSet").size());
        assertEquals("SettlMethodCodeSet", repository.elementsByTagName("fixr:field").stream()
                .filter(field -> "1039".equals(field.getAttribute("id")))
                .findFirst().orElseThrow(AssertionError::new)
                .getAttribute("type"));
        assertTrue(pruner.getWarnings().toString(), pruner.getWarnings().isEmpty());
    }

    /**
     * A selection keeps what it names and drops the rest, whether it names it by message name or by msgType.
     */
    @Test
    public void testASelectionKeepsOnlyTheMessagesItNames() throws Exception {
        OrchestraRepository byName = fixture();
        PruneReport report = new OrchestraPruner(byName, VersionCut.ALL, true,
                MessageSelection.of("LateMessage")).prune();
        assertEquals("Logon is session, and kept whatever the list says",
                List.of("Logon", "LateMessage"), namesOf(byName, "fixr:message"));
        assertEquals("LatestMessage and AppMessage", 2, report.count(PruneReport.Reason.NOT_SELECTED, "fixr:message"));

        OrchestraRepository byMsgType = fixture();
        new OrchestraPruner(byMsgType, VersionCut.ALL, true, MessageSelection.of("ZZ")).prune();
        assertEquals("ZZ is LateMessage's msgType",
                List.of("Logon", "LateMessage"), namesOf(byMsgType, "fixr:message"));
    }

    /**
     * Which application messages a package is about is a choice; whether it can log on is not. Up to FIX.4.4 the
     * session layer belongs to this dictionary, and a list naming the business vocabulary would leave it out without
     * ever meaning to.
     */
    @Test
    public void testSessionMessagesSurviveASelectionThatDoesNotNameThem() throws Exception {
        OrchestraRepository repository = fixture();
        PruneReport report = new OrchestraPruner(repository, VersionCut.ALL, true,
                MessageSelection.of("LatestMessage")).prune();

        assertTrue(namesOf(repository, "fixr:message").contains("Logon"));
        assertEquals("LateMessage and AppMessage were dropped", 2,
                report.count(PruneReport.Reason.NOT_SELECTED, "fixr:message"));
    }

    /**
     * The selection is read against the messages the cut left, not against the repository as published: LatestMessage
     * came at EP300, so asking a 4.4 cut for it is a mistake rather than a message that happens not to be there.
     */
    @Test
    public void testASelectionNamingSomethingThisCutDoesNotHoldIsReported() throws Exception {
        OrchestraRepository repository = fixture();
        OrchestraPruner pruner = new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null),
                true, MessageSelection.of("Logon", "LatestMessage", "NoSuchMessage"));
        pruner.prune();

        assertEquals(List.of("LatestMessage", "NoSuchMessage"), pruner.getUnmatchedMessages());
        assertEquals("and what it did name is still kept", List.of("Logon"), namesOf(repository, "fixr:message"));
    }

    /**
     * Naming the same message twice, once each way, is redundant rather than wrong - and neither entry may be
     * reported as unknown, or the failure sends its reader hunting for a typo that is not there.
     */
    @Test
    public void testNamingAMessageBothWaysReportsNeitherEntryAsUnknown() throws Exception {
        OrchestraRepository repository = fixture();
        OrchestraPruner pruner = new OrchestraPruner(repository, VersionCut.ALL, true,
                MessageSelection.of("LateMessage", "ZZ"));
        pruner.prune();

        assertTrue(pruner.getUnmatchedMessages().toString(), pruner.getUnmatchedMessages().isEmpty());
        assertEquals(List.of("Logon", "LateMessage"), namesOf(repository, "fixr:message"));
    }

    /**
     * What a dropped message leaves behind is two different things. A component made only of it goes, because the
     * fixed point drops what has been emptied - but a field definition nothing references any more is untouched
     * here, and it is the dictionary sanitizer that takes it out of the dictionary.
     */
    @Test
    public void testWhatADroppedMessageLeavesUnreferencedIsLeftToTheSanitizer() throws Exception {
        OrchestraRepository repository = fixture();
        new OrchestraPruner(repository, VersionCut.ALL, true, MessageSelection.of("Logon")).prune();

        assertEquals(List.of("Logon"), namesOf(repository, "fixr:message"));
        assertEquals("every field of the repository is still defined", 11,
                repository.elementsByTagName("fixr:field").size());
    }

    /**
     * The report is what a dry run prints, so it has to tell the two kinds of removal apart: what the cut took, and
     * what fell over afterwards because of it.
     */
    @Test
    public void testTheReportSeparatesTheCutFromItsConsequences() throws Exception {
        OrchestraRepository repository = fixture();
        PruneReport report = new OrchestraPruner(repository, VersionCut.of(OrchestraVersion.of("FIX.4.4"), null), true).prune();

        assertEquals(3, report.count(PruneReport.Reason.AFTER_CUT, "fixr:field"));
        assertEquals(1, report.count(PruneReport.Reason.AFTER_CUT, "fixr:message"));
        assertTrue(report.describe(), report.count(PruneReport.Reason.DANGLING, "fixr:component") >= 1);
        assertTrue(report.describe(), report.count(PruneReport.Reason.DANGLING, "fixr:group") >= 1);
        assertTrue(report.describe().contains("AFTER_CUT"));
    }
}
