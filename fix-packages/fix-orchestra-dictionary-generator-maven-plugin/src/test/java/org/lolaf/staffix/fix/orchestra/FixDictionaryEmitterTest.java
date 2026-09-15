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
import java.io.StringWriter;
import java.net.URL;

import static org.junit.Assert.*;

/**
 * The translation from an Orchestra repository to the dictionary the encoders generator of this project reads: by
 * name rather than by id, groups inlined and named after their counter, code sets flattened into values, and the
 * session layer where the version puts it.
 */
public class FixDictionaryEmitterTest {

    private static String emit(String upToVersion, Integer upToExtensionPack) throws Exception {
        return emit("orchestration-fixture.xml", upToVersion, upToExtensionPack);
    }

    private static String emit(String fixture, String upToVersion, Integer upToExtensionPack) throws Exception {
        return emit(fixture, upToVersion, upToExtensionPack, false);
    }

    private static String emitMarked(String upToVersion, Integer upToExtensionPack) throws Exception {
        return emit("orchestration-fixture.xml", upToVersion, upToExtensionPack, true);
    }

    private static String emit(String fixture, String upToVersion, Integer upToExtensionPack,
                               boolean markDeprecated) throws Exception {
        URL resource = FixDictionaryEmitterTest.class.getClassLoader().getResource(fixture);
        OrchestraRepository repository = OrchestraRepository.load(new File(resource.toURI()));
        OrchestraVersion version = OrchestraVersion.of(upToVersion);
        VersionCut cut = VersionCut.of(version, upToExtensionPack);
        new OrchestraPruner(repository, cut, true).prune();
        StringWriter out = new StringWriter();
        new FixDictionaryEmitter(repository, version, null, markDeprecated ? cut : null).emit(out);
        return out.toString();
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    public void testTheRootCarriesTheVersionTheGeneratorReadsBack() throws Exception {
        assertTrue(emit("FIX.4.4", null).contains("<fix major=\"4\" minor=\"4\">"));
        assertTrue(emit("FIX.5.0SP2", null).contains("<fix major=\"5\" minor=\"0\" servicepack=\"SP2\">"));
        // not major="Latest": that spelling takes a path through the encoders generator which hands the version enum
        // a lower case service pack it does not know
        assertTrue(emit("FIX.Latest", null).contains("<fix major=\"5\" minor=\"0\" servicepack=\"Latest\">"));
    }

    /**
     * A field is written by number, name and dictionary type, and a code set becomes the values of the field it
     * types - with the code names as the upper case descriptions the generator turns into enum constants.
     */
    @Test
    public void testAFieldCarriesItsTypeAndItsValues() throws Exception {
        String dictionary = emit("FIX.4.4", null);

        assertTrue(dictionary.contains("<field number=\"1\" name=\"Account\" type=\"STRING\"/>"));
        assertTrue(dictionary.contains("<field number=\"54\" name=\"Side\" type=\"CHAR\">"));
        assertTrue(dictionary.contains("<value enum=\"1\" description=\"BUY\"/>"));
        assertTrue(dictionary.contains("<value enum=\"2\" description=\"SELL\"/>"));
        assertFalse("the code added at EP161 is past a 4.4 cut", dictionary.contains("UNDISCLOSED"));
    }

    /**
     * A group is inlined where it is used and named after the NumInGroup field that counts it - InstrmtLegGrp counts
     * with NoLegs, and it is NoLegs the dictionary names - because that is what the encoders generator looks up.
     */
    @Test
    public void testAGroupIsInlinedUnderTheNameOfItsCounter() throws Exception {
        String dictionary = emit("FIX.4.4", null);

        assertTrue(dictionary.contains("<group name=\"NoLegs\" required=\"N\">"));
        assertFalse("the group's own name means nothing to a dictionary", dictionary.contains("InstrmtLegGrp"));
        assertTrue("and it holds what the group holds", dictionary.contains("<field name=\"LegSymbol\" required=\"N\"/>"));
    }

    /**
     * A component keeps its name and is written once, referenced from wherever it is used.
     */
    @Test
    public void testAComponentIsWrittenOnceAndReferencedByName() throws Exception {
        String dictionary = emit("FIX.4.4", null);

        assertEquals("defined once", 1, countOf(dictionary, "<component name=\"Instrument\">"));
        assertEquals("referenced from the message", 1, countOf(dictionary, "<component name=\"Instrument\" required=\"N\"/>"));
    }

    /**
     * Section 4.4.1 of FIXT.1.1: up to FIX.4.4 the standard header, the trailer and the session messages are part of
     * the application dictionary, and from FIX.5.0 they are a dictionary of their own. Getting this wrong leaves the
     * encoders generator classifying every header field as a body field, since it reads the classification off the
     * header element alone.
     */
    @Test
    public void testTheSessionLayerGoesWhereTheVersionPutsIt() throws Exception {
        String upTo44 = emit("FIX.4.4", null);
        assertTrue(upTo44.contains("<header>"));
        assertTrue("the header holds what StandardHeader holds", upTo44.contains("<field name=\"MsgSeqNum\" required=\"Y\"/>"));
        assertTrue(upTo44.contains("msgcat=\"admin\""));
        assertFalse("a message carries no reference to the header", upTo44.contains("<component name=\"StandardHeader\""));

        String upTo50 = emit("FIX.5.0", null);
        assertTrue("both elements are still written, the generator asks for them by name", upTo50.contains("<header/>"));
        assertTrue(upTo50.contains("<trailer/>"));
        assertFalse("the session messages moved to FIXT.1.1", upTo50.contains("msgcat=\"admin\""));
        assertFalse(upTo50.contains("name=\"Logon\""));
    }

    /**
     * The session layer an orchestration describes is FIXT.1.1's, and the five application version fields are the
     * whole of what makes it FIXT.1.1's rather than FIX.4.4's. They cannot be told apart by their stamps - EP16 is
     * where FIXT.1.1 was published and the ladder puts EP16 inside FIX.4.4 - so a dictionary that carries its own
     * session layer, and therefore predates FIXT.1.1, has to leave them out by name. FIX44.xml has none of them.
     * <p>
     * The definitions stay from FIX.5.0 on, where the session layer is left to FIXT.1.1 and the fields are the
     * standard's ordinary ones: FIX50SP2.xml defines ApplVerID(1128).
     */
    @Test
    public void testTheFixtApplicationVersionFieldsStayOutOfAPreFixtDictionary() throws Exception {
        String fixture = "orchestration-fixt-appl-version-fixture.xml";
        String upTo44 = emit(fixture, "FIX.4.4", 38);
        assertFalse("the header is FIX.4.4's, not FIXT.1.1's", upTo44.contains("ApplVerID"));
        assertFalse("and its Logon negotiates no application version", upTo44.contains("DefaultApplVerID"));
        assertTrue("the rest of the header is untouched", upTo44.contains("<field name=\"MsgSeqNum\" required=\"Y\"/>"));
        assertTrue("and so is the rest of the Logon", upTo44.contains("<field name=\"EncryptMethod\" required=\"Y\"/>"));

        String upTo50 = emit(fixture, "FIX.5.0", null);
        assertTrue("from FIX.5.0 they are ordinary fields of the standard, as FIX50SP2.xml has them",
                upTo50.contains("<field number=\"1128\" name=\"ApplVerID\" type=\"STRING\"/>"));
    }

    /**
     * Where the published FIX50SP2.xml draws the line, which is not "session or not". The standard header and
     * trailer fields stay - they are on the wire of every message the dictionary describes - while what only a
     * session message carries goes to FIXT.1.1 with the message. ApplVerID(1128) is on the header and FIX50SP2.xml
     * defines it; DefaultApplVerID(1137) is on the Logon and only FIXT11.xml does, and the same line puts
     * EncryptMethod(98) on the FIXT side.
     * <p>
     * Up to FIX.4.4 the question does not arise: the session layer is this dictionary's own, and all of it stays.
     */
    @Test
    public void testFieldsOnlyASessionMessageCarriesAreLeftToFixt() throws Exception {
        String fixture = "orchestration-fixt-appl-version-fixture.xml";

        String upTo50 = emit(fixture, "FIX.5.0", null);
        assertFalse("nothing but the Logon carries it", upTo50.contains("name=\"EncryptMethod\""));
        assertFalse(upTo50.contains("name=\"DefaultApplVerID\""));
        assertTrue("while the header's own fields stay, empty <header/> or not",
                upTo50.contains("name=\"MsgSeqNum\""));
        assertTrue(upTo50.contains("name=\"CheckSum\""));
        assertTrue(upTo50.contains("name=\"ApplVerID\""));

        String upTo44 = emit(fixture, "FIX.4.4", 38);
        assertTrue("a dictionary carrying its own session layer keeps every field of it",
                upTo44.contains("name=\"EncryptMethod\""));
    }

    /**
     * A field nothing references at all is a different question, and not this one's: it stays, and the dictionary
     * sanitizer is what takes it out. AdvId(2) and Side(54) of the fixture are referenced by no message.
     */
    @Test
    public void testAFieldNothingReferencesIsNotSessionOnly() throws Exception {
        String upTo50 = emit("FIX.Latest", null);

        assertTrue(upTo50.contains("name=\"AdvId\""));
        assertTrue(upTo50.contains("name=\"Side\""));
        assertFalse("while LateField is reached through LateOnly, which the Logon alone references",
                upTo50.contains("number=\"40001\""));
        assertFalse("and LateOnly goes with it, or it would reference a definition the file no longer carries",
                upTo50.contains("name=\"LateOnly\""));
        assertTrue("and Retired, which an application message also carries, stays",
                upTo50.contains("name=\"Retired\""));
    }

    /**
     * The extension pack the cut landed on, on the root element, so that the file says which of the cuts it is:
     * major, minor and servicepack name the version, and two dictionaries of one version hold different content
     * depending on where in that version's extension packs they were cut.
     * <p>
     * Left out when nothing was recorded, because a dictionary that names an extension pack it was not cut at is
     * worse than one that names none. The encoders generator reads {@code major}, {@code minor} and
     * {@code servicepack} by name and ignores the rest, so adding it costs nothing downstream.
     */
    @Test
    public void testTheRootCarriesTheExtensionPackTheCutLandedOn() throws Exception {
        URL resource = FixDictionaryEmitterTest.class.getClassLoader().getResource("orchestration-fixture.xml");
        OrchestraRepository repository = OrchestraRepository.load(new File(resource.toURI()));
        OrchestraVersion version = OrchestraVersion.of("FIX.4.4");
        new OrchestraPruner(repository, VersionCut.of(version, 0), true).prune();
        StringWriter out = new StringWriter();
        new FixDictionaryEmitter(repository, version, 0).emit(out);

        assertTrue(out.toString(), out.toString().contains("<fix major=\"4\" minor=\"4\" extensionpack=\"0\">"));
        assertFalse("nothing recorded, nothing claimed", emit("FIX.4.4", 0).contains("extensionpack"));
    }

    /**
     * A service pack keeps its own attribute, and the extension pack goes after it rather than in place of it - the
     * two say different things, and the encoders generator reads the service pack back to build its version enum.
     */
    @Test
    public void testTheExtensionPackDoesNotDisplaceTheServicePack() throws Exception {
        URL resource = FixDictionaryEmitterTest.class.getClassLoader().getResource("orchestration-fixture.xml");
        OrchestraRepository repository = OrchestraRepository.load(new File(resource.toURI()));
        OrchestraVersion version = OrchestraVersion.of("FIX.5.0SP2");
        StringWriter out = new StringWriter();
        new FixDictionaryEmitter(repository, version, 161).emit(out);

        assertTrue(out.toString(), out.toString()
                .contains("<fix major=\"5\" minor=\"0\" servicepack=\"SP2\" extensionpack=\"161\">"));
    }

    /**
     * A field whose code set the cut removed comes out as the plain datatype the code set was built on. This is the
     * end of the chain the pruner starts: the type it rewrote has to be one {@code FieldTypes} accepts, or the
     * emitter throws here instead.
     */
    @Test
    public void testAFieldWhoseCodeSetTheCutRemovedIsWrittenAsAPlainType() throws Exception {
        String dictionary = emit("orchestration-orphaned-codeset-fixture.xml", "FIX.4.4", null);

        assertTrue(dictionary.contains("<field number=\"1039\" name=\"UnderlyingSettlMethod\" type=\"STRING\"/>"));
        assertFalse("the codes arrived with the code set, at FIX.5.0", dictionary.contains("CASH_SETTLEMENT"));
    }

    /**
     * Nothing at all unless it was asked for. A dictionary is read by more than this project's generator, and the
     * attributes below are this project's own addition, so the file stays what a QuickFIX toolchain would have
     * written until someone says otherwise - which also means every dictionary shipped before this existed is
     * unchanged by it.
     */
    @Test
    public void testDeprecationIsNotWrittenUnlessItIsAskedFor() throws Exception {
        String dictionary = emit("FIX.4.4", null);

        assertFalse(dictionary, dictionary.contains("deprecated"));
        assertTrue("and the deprecated elements are still all there", dictionary.contains("name=\"Retired\""));
    }

    /**
     * The mark is {@code deprecated="true"} and nothing else. Which version of the standard did the deprecating is
     * in the orchestration for anyone who wants it, and a dictionary states one version - putting a second one
     * inside it, on an attribute nothing reads back, would be mixing two versions in a file that claims to be one.
     */
    @Test
    public void testTheMarkSaysDeprecatedAndNothingMore() throws Exception {
        String dictionary = emitMarked("FIX.5.0SP2", null);

        assertTrue(dictionary, dictionary.contains(
                "<field number=\"1234\" name=\"Retired\" type=\"STRING\" deprecated=\"true\"/>"));
        assertFalse("no version of the standard leaks into a dictionary of another one",
                dictionary.contains("deprecated=\"FIX"));
        assertFalse(dictionary.contains("deprecatedep"));
        assertTrue("values are marked the same way",
                emitMarked("FIX.4.4", null).contains("<value enum=\"8\" description=\"OLDWAY\" deprecated=\"true\"/>"));
        assertTrue("and what was never deprecated says nothing",
                dictionary.contains("<field number=\"1\" name=\"Account\" type=\"STRING\"/>"));
    }

    /**
     * A repository always describes the standard as it stands now, so it carries deprecations that had not happened
     * yet at the point being cut to - and those are not facts about the dictionary being written. Retired is a
     * FIX.4.4 field the standard deprecated in FIX.5.0: current in a FIX.4.4 dictionary, deprecated in a 5.0SP2 one,
     * from the same stamp in the same file.
     * <p>
     * This is the same question {@link OrchestraPruner} asks before dropping a deprecated element, asked again
     * before writing one down, and getting it wrong is not visible in the output - it is a dictionary that reads
     * perfectly well and dates a field to a version the file does not claim to be.
     */
    @Test
    public void testADeprecationLaterThanTheCutHasNotHappenedYet() throws Exception {
        assertTrue("current at 4.4, which is before the FIX.5.0 that deprecated it",
                emitMarked("FIX.4.4", null).contains("<field number=\"1234\" name=\"Retired\" type=\"STRING\"/>"));
        assertTrue("and deprecated once the cut is past it", emitMarked("FIX.5.0SP2", null).contains(
                "<field number=\"1234\" name=\"Retired\" type=\"STRING\" deprecated=\"true\"/>"));
    }

    /**
     * The distinction the whole thing rests on, and the one a dictionary has never been able to make: Account is a
     * current field that one message is deprecated in using. The field says nothing and the reference says it, so
     * both facts survive - a generator can deprecate that one setter without deprecating the field everywhere.
     */
    @Test
    public void testAUseCanBeDeprecatedWhileTheFieldIsNot() throws Exception {
        String dictionary = emitMarked("FIX.4.4", null);

        assertTrue("the use, in Logon", dictionary.contains(
                "<field name=\"Account\" required=\"N\" deprecated=\"true\"/>"));
        assertTrue("the field, which is current", dictionary.contains(
                "<field number=\"1\" name=\"Account\" type=\"STRING\"/>"));
        assertTrue("and its other uses, which are current too", dictionary.contains(
                "<field name=\"Account\" required=\"N\"/>"));
    }

    /**
     * A group is three elements in an orchestration - the group, the reference to it and the NumInGroup that counts
     * it - and one in a dictionary, so a stamp on any of the three has one place to land.
     */
    @Test
    public void testADeprecatedGroupIsMarkedOnTheInlinedGroup() throws Exception {
        String dictionary = emitMarked("FIX.4.4", null);

        assertTrue(dictionary, dictionary.contains(
                "<group name=\"NoLegs\" required=\"N\" deprecated=\"true\">"));
    }

    /**
     * A component is written once and referenced, so the deprecation of the component itself goes on the definition.
     * The references stay clean, which is the same rule as for fields: the reference says only what is true of that
     * one use.
     */
    @Test
    public void testADeprecatedComponentIsMarkedOnItsDefinition() throws Exception {
        String dictionary = emitMarked("FIX.4.4", null);

        assertTrue(dictionary, dictionary.contains("<component name=\"Instrument\" deprecated=\"true\">"));
        assertTrue("the reference is a use, and this use is not deprecated",
                dictionary.contains("<component name=\"Instrument\" required=\"N\"/>"));
    }
}
