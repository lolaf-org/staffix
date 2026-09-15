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

import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.lolaf.staffix.generator.CodeGenerator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class GoldenDictionaryTest {

    private static final File ORCHESTRATION =
            new File("../fix-orchestra/src/main/resources/fix-orchestra-latest.zip");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static Set<Integer> fieldNumbersOf(String dictionary) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher matcher = Pattern.compile("<field number=\"(\\d+)\"").matcher(dictionary);
        while (matcher.find()) {
            numbers.add(Integer.valueOf(matcher.group(1)));
        }
        return numbers;
    }

    /**
     * The {@code FieldLocation} a generated field class was given, read off the static import the template writes.
     */
    private static String locationOf(File sources, String fieldName) throws Exception {
        File field = Files.walk(sources.toPath())
                .filter(path -> path.getFileName().toString().equals(fieldName + ".java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no class was generated for field " + fieldName))
                .toFile();
        Matcher matcher = Pattern.compile("import static org\\.lolaf\\.staffix\\.api\\.fields\\.FieldLocation\\.(\\w+);")
                .matcher(new String(Files.readAllBytes(field.toPath()), StandardCharsets.UTF_8));
        assertTrue("the generated field carries no location", matcher.find());
        return matcher.group(1);
    }

    private static int countMatching(String dictionary, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(dictionary);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private File generateFix44AsPublished() throws Exception {
        return generateFix44AsPublished(false, "FIX44.xml");
    }

    private File generateFix44AsPublished(boolean markDeprecated, String fileName) throws Exception {
        OrchestraRepository repository = OrchestraRepository.load(ORCHESTRATION);
        OrchestraVersion fix44 = OrchestraVersion.of("FIX.4.4");
        // as published: the base release, with the deprecated elements a published dictionary keeps
        VersionCut cut = VersionCut.of(fix44, 0);
        new OrchestraPruner(repository, cut, true).prune();
        File dictionary = new File(temporaryFolder.getRoot(), fileName);
        try (Writer out = new BufferedWriter(Files.newBufferedWriter(dictionary.toPath(), StandardCharsets.UTF_8))) {
            new FixDictionaryEmitter(repository, fix44, null, markDeprecated ? cut : null).emit(out);
        }
        return dictionary;
    }

    /**
     * Where the published FIX50SP2.xml puts the line between this dictionary and FIXT.1.1's, checked against the
     * real repository because it is the only place the answer is a fact rather than a fixture.
     * <p>
     * The line is not "session or not". These nine are on the wire of every message a FIX.5.0 dictionary describes,
     * and the published one defines all nine even though its {@code <header>} is empty. The twenty below are what
     * only a session message ever carries, and it defines none of them - QuickFIX/J's FIX50SP2.xml, which descends
     * from the published dictionary, has 1432 fields and not one of these twenty.
     */
    @Test
    public void testAFix50DictionaryKeepsTheHeaderFieldsAndLeavesTheSessionOnesToFixt() throws Exception {
        Assume.assumeTrue("the FIX Latest orchestration is not in the sibling module", ORCHESTRATION.isFile());

        String dictionary = Files.readString(generateFix50Sp2AsPublished().toPath());

        for (String onTheWireOfEveryMessage : List.of("BeginString", "BodyLength", "MsgType", "MsgSeqNum",
                "SenderCompID", "TargetCompID", "SendingTime", "CheckSum", "ApplVerID")) {
            assertTrue(onTheWireOfEveryMessage + " is a standard header or trailer field and belongs here",
                    dictionary.contains("name=\"" + onTheWireOfEveryMessage + "\""));
        }
        for (String onlyASessionMessageCarriesIt : List.of("EncryptMethod", "HeartBtInt", "TestReqID", "GapFillFlag",
                "ResetSeqNumFlag", "BeginSeqNo", "EndSeqNo", "NewSeqNo", "RefTagID", "SessionRejectReason",
                "MaxMessageSize", "NoMsgTypes", "MsgDirection", "TestMessageIndicator", "NextExpectedMsgSeqNum",
                "DefaultApplVerID", "DefaultApplExtID", "DefaultCstmApplVerID", "SessionStatus",
                "DefaultVerIndicator")) {
            assertFalse(onlyASessionMessageCarriesIt + " belongs to FIXT.1.1, and FIX50SP2.xml does not define it",
                    dictionary.contains("name=\"" + onlyASessionMessageCarriesIt + "\""));
        }
    }

    /**
     * And the other side of it: up to FIX.4.4 the session layer is the dictionary's own, so none of that applies.
     */
    @Test
    public void testAFix44DictionaryKeepsTheWholeSessionLayer() throws Exception {
        Assume.assumeTrue("the FIX Latest orchestration is not in the sibling module", ORCHESTRATION.isFile());

        String dictionary = Files.readString(generateFix44AsPublished().toPath());

        assertTrue(dictionary.contains("name=\"EncryptMethod\""));
        assertTrue(dictionary.contains("name=\"TestReqID\""));
        assertTrue("and its Logon is still a message of this dictionary", dictionary.contains("name=\"Logon\""));
    }

    private File generateFix50Sp2AsPublished() throws Exception {
        OrchestraRepository repository = OrchestraRepository.load(ORCHESTRATION);
        OrchestraVersion fix50sp2 = OrchestraVersion.of("FIX.5.0SP2");
        // EP98 is where FIX.5.0SP2 ends, the cut the fix-50sp2 module is built at
        VersionCut cut = VersionCut.of(fix50sp2, 98);
        new OrchestraPruner(repository, cut, true).prune();
        File dictionary = new File(temporaryFolder.getRoot(), "FIX50SP2-golden.xml");
        try (Writer out = new BufferedWriter(Files.newBufferedWriter(dictionary.toPath(), StandardCharsets.UTF_8))) {
            new FixDictionaryEmitter(repository, fix50sp2, 98, null).emit(out);
        }
        return dictionary;
    }

    /**
     * What marking deprecation costs and buys, against the real repository rather than a fixture.
     * <p>
     * The count is not arbitrary: a FIX.4.4 cut as published comes to 914 fields with deprecated elements kept and
     * 901 without, and those 13 are the repurchase and redemption fields the standard deprecated in 4.4 and kept in
     * the dictionary anyway. So a marked cut has to mark exactly those 13 field definitions - no more, since marking
     * a current field would be a lie, and no fewer, since a fact silently dropped is the failure mode this whole
     * thing exists to avoid.
     * <p>
     * The unmarked cut is the same dictionary without a word of it, which is what FIX44.xml and every hand written
     * dictionary look like, and what the encoders generator has always been handed.
     */
    @Test
    public void testAMarkedCutMarksTheDeprecatedFieldsAndOnlyThose() throws Exception {
        Assume.assumeTrue("the FIX Latest orchestration is not in the sibling module", ORCHESTRATION.isFile());

        String marked = Files.readString(generateFix44AsPublished(true, "FIX44-marked.xml").toPath());
        String plain = Files.readString(generateFix44AsPublished(false, "FIX44-plain.xml").toPath());

        assertEquals("the repurchase and redemption fields 4.4 deprecated and kept",
                13, countMatching(marked, "<field number=\"\\d+\"[^>]* deprecated=\""));
        assertTrue("and they are named as deprecated by the version that did it",
                marked.contains("name=\"RepurchaseRate\" type=\"PERCENTAGE\" deprecated=\"true\"/>"));
        assertTrue("values carry it too, and are counted separately from the fields that hold them",
                countMatching(marked, "<value [^>]* deprecated=\"") > 0);
        assertFalse("nothing is said unless it is asked for", plain.contains("deprecated=\""));
        assertEquals("and the two cuts hold the same fields either way",
                fieldNumbersOf(plain), fieldNumbersOf(marked));
    }

    /**
     * The one that says the two ends agree: the generator that consumes these dictionaries is run over one, and it
     * refuses anything it does not understand - an element it has no case for, a group whose name is not a
     * NumInGroup field, a type outside {@code FieldType}, a component reference it cannot resolve. Getting through
     * it is the assertion; what it leaves behind is the evidence it did the work.
     * <p>
     * The field classes are where the session layer shows: their {@code FieldLocation} comes from the
     * {@code <header>} and {@code <trailer>} elements of the dictionary and from nowhere else, so an empty header -
     * which is what the QuickFIX/J Orchestra generator writes - silently turns MsgSeqNum(34) and CheckSum(10) into
     * body fields. That is the defect this plugin exists to not have, and this is where it would show.
     */
    @Test
    public void testTheEncodersGeneratorReadsWhatThisPluginWrites() throws Exception {
        Assume.assumeTrue("the FIX Latest orchestration is not in the sibling module", ORCHESTRATION.isFile());

        File dictionary = generateFix44AsPublished();
        File sources = temporaryFolder.newFolder("sources");
        File resources = temporaryFolder.newFolder("resources");

        CodeGenerator.process("org.lolaf.staffix.golden", dictionary, sources, resources,
                "golden", new SystemStreamLog(), false);

        List<String> generatedFiles = Files.walk(sources.toPath())
                .filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        // no LogonEncoder: the generator writes no encoder for an admin message, this engine's admin codec builds
        // those by hand
        assertTrue("an encoder per application message", generatedFiles.contains("NewOrderSingleEncoder.java"));
        assertTrue("and a class per field", generatedFiles.contains("MsgSeqNum.java"));

        assertEquals("MsgSeqNum belongs to the header, which is what the <header> element told the generator",
                "HEADER", locationOf(sources, "MsgSeqNum"));
        assertEquals("TRAILER", locationOf(sources, "CheckSum"));
        assertEquals("and a field of neither is a body field", "BODY", locationOf(sources, "Account"));
    }
}
