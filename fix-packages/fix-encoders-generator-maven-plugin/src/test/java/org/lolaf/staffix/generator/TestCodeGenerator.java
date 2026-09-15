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
package org.lolaf.staffix.generator;

import org.apache.maven.plugin.logging.Log;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.mockito.Mockito.mock;

class TestCodeGenerator {

    private static File generate(String dictionary, String packageSuffix) throws Exception {
        File target = new File("./target");
        File dict = new File(target.getParentFile(), "src/test/resources/" + dictionary);
        if (!dict.exists()) {
            throw new IllegalStateException("Unknown dict file " + dict);
        }
        File sources = new File(target, "gen-" + packageSuffix + "-sources");
        CodeGenerator.process("test." + packageSuffix, dict, sources,
                new File(target, "gen-" + packageSuffix + "-resources"), "test-dict", mock(Log.class), false);
        return new File(sources, "test/" + packageSuffix);
    }

    private static String read(File packageDir, String path) throws Exception {
        return new String(Files.readAllBytes(new File(packageDir, path).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    void testCodeGenerator() throws Exception {
        Log log = mock(Log.class);
        File target = new File("./target");
        if (!target.exists()) {
            throw new IllegalStateException("Unknown target dir");
        }
        File dict = new File(target.getParentFile(), "src/test/resources/FIX44-test.xml");
        if (!dict.exists()) {
            throw new IllegalStateException("Unknown dict file");
        }
        File sourceOuput = new File(target, "gen-test-sources");
        File resourcesOuput = new File(target, "gen-test-resources");

        CodeGenerator.process("test.package", dict, sourceOuput, resourcesOuput, "test-dict", log, true);

        Assertions.assertThat(new File(target.getParentFile(), "src/test/resources/expected.fields"))
                .hasSameTextualContentAs(new File(resourcesOuput, "VERSION_44.test-dict-FIX.4.4.fields"));

        Assertions.assertThat(new File(target.getParentFile(), "src/test/resources/expected.MarketDataSnapshotFullRefresh.fieldsInfo"))
                .hasSameTextualContentAs(new File(resourcesOuput, "VERSION_44.test-dict-FIX.4.4.MarketDataSnapshotFullRefresh.fieldsInfo"));

    }

    /**
     * A dictionary that says nothing about deprecation produces code that says nothing about it either.
     * <p>
     * This is what lets the hand written QuickFIX dictionaries go on being read by a generator that now knows about
     * an attribute they do not carry: {@code getAttribute} answers the empty string for what is not there, and the
     * empty string means current.
     */
    @Test
    void testADictionaryWithoutDeprecationGeneratesNoAnnotation() throws Exception {
        File generated = generate("FIX44-test.xml", "plain");

        Assertions.assertThat(read(generated, "encoders/MarketDataSnapshotFullRefreshEncoder.java"))
                .doesNotContain("@Deprecated");
        Assertions.assertThat(read(generated, "fields/CFICode.java")).doesNotContain("@Deprecated");
        Assertions.assertThat(read(generated, "msg/MessageTypes.java")).doesNotContain("@Deprecated");
    }

    /**
     * The two facts a dictionary could never tell apart before, now told apart: Account is a current field that one
     * message is deprecated in using, so the setter carries the annotation and the class does not. Retired is
     * deprecated outright, so the class carries it and every use follows.
     */
    @Test
    void testADeprecatedUseAndADeprecatedFieldAreBothMarked() throws Exception {
        File generated = generate("FIX44-deprecated-test.xml", "deprecated");

        String account = read(generated, "fields/Account.java");
        Assertions.assertThat(account).doesNotContain("@Deprecated");

        String retired = read(generated, "fields/Retired.java");
        Assertions.assertThat(retired).contains("@Deprecated", "deprecated by the FIX standard");

        String encoder = read(generated, "encoders/DeprecatedThingsEncoder.java");
        Assertions.assertThat(encoder).contains("@Deprecated\n        public DeprecatedThingsEncoder setAccount(");
        Assertions.assertThat(encoder).contains("@Deprecated\n        public DeprecatedThingsEncoder setRetired(");
        Assertions.assertThat(encoder).as("a current field used in a message that is not deprecated in using it")
                .contains("\n        public DeprecatedThingsEncoder setCurrentText(")
                .doesNotContain("@Deprecated\n        public DeprecatedThingsEncoder setCurrentText(");
    }

    /**
     * A group becomes a class of its own, so it is that class that is marked and not each setter inside it. A
     * component does not - it is flattened into whatever uses it - so there the members are the only place the fact
     * can go.
     */
    @Test
    void testAGroupIsMarkedOnItsClassAndAComponentOnWhatItContributes() throws Exception {
        File generated = generate("FIX44-deprecated-test.xml", "shapes");
        String encoder = read(generated, "encoders/DeprecatedThingsEncoder.java");
        String group = read(generated, "encoders/group/NoLegsEncoder.java");

        Assertions.assertThat(group).as("the group's own class carries the deprecation")
                .contains("@Deprecated")
                .contains("public final class NoLegsEncoder");
        Assertions.assertThat(encoder).contains("@Deprecated\n    public NoLegsEncoder addNoLegs(");
        Assertions.assertThat(group).as("the group class already covers its members")
                .contains("\n        public NoLegsEncoder setLegSymbol(")
                .doesNotContain("@Deprecated\n        public NoLegsEncoder setLegSymbol(");
        Assertions.assertThat(encoder).as("the deprecated component has no class, so its field carries it")
                .contains("@Deprecated\n        public DeprecatedThingsEncoder setLegacyText(");
    }

    /**
     * The point of generating groups as top-level classes: a group encoder is built from its field list and from
     * nothing else, so every message carrying that group shares one class rather than getting a nested copy of it.
     * Before this, {@code staffix-fix-latest} generated 20,985 group encoder classes for 515 distinct field lists.
     */
    @Test
    void testMessagesCarryingTheSameGroupShareOneEncoderClass() throws Exception {
        File generated = generate("FIX44-test.xml", "shared");

        File encoders = new File(generated, "encoders/group");
        Assertions.assertThat(new File(encoders, "NoMDEntriesEncoder.java"))
                .as("group encoders live in their own package, told apart from message encoders at a glance")
                .exists();

        String snapshot = read(generated, "encoders/MarketDataSnapshotFullRefreshEncoder.java");
        Assertions.assertThat(snapshot)
                .as("the message hands out the shared class and no longer declares one of its own")
                .contains("public NoMDEntriesEncoder addNoMDEntries(")
                .doesNotContain("public class NoMDEntriesEncoder");
        Assertions.assertThat(snapshot)
                .as("and the per-message forwarding base it needed for nesting is gone with it")
                .doesNotContain("InnerEncoder");
    }

    /**
     * FIX reuses a counter field across genuinely different groups - NoLegs stands for nine different field lists in
     * FIX Latest - so the name alone cannot decide which groups share a class. Two groups with the same counter
     * field and different contents must stay two classes, and neither may claim the bare name.
     */
    @Test
    void testTwoGroupsSharingACounterFieldStayApart() throws Exception {
        File generated = generate("FIX44-test.xml", "ambiguous");
        File encoders = new File(generated, "encoders/group");

        File[] noMdEntries = encoders.listFiles((dir, name) -> name.startsWith("NoMDEntries"));
        Assertions.assertThat(noMdEntries).isNotNull();
        for (File groupEncoder : noMdEntries) {
            String name = groupEncoder.getName().replace(".java", "");
            Assertions.assertThat(read(generated, "encoders/group/" + groupEncoder.getName()))
                    .as("%s must be self-contained", name)
                    .contains("public final class " + name + " extends AbstractGroupEncoder<" + name + ">");
        }
    }

    /**
     * A deprecated value of a live field, and a deprecated message: the enum constant and the message type carry it,
     * and their neighbours do not.
     */
    @Test
    void testValuesAndMessageTypesAreMarkedOneByOne() throws Exception {
        File generated = generate("FIX44-deprecated-test.xml", "values");

        String legacyBits = read(generated, "fields/LegacyBits.java");
        Assertions.assertThat(legacyBits).contains("@Deprecated\n        GONE_BAD('B');");
        Assertions.assertThat(legacyBits).contains("\n        STILL_GOOD('A'),");

        String messageTypes = read(generated, "msg/MessageTypes.java");
        Assertions.assertThat(messageTypes).contains("deprecated by the FIX standard");
        Assertions.assertThat(messageTypes).contains("@Deprecated\n    OldMessage(\"ZO\")");
    }

    /**
     * An encoder has to go on being able to build a deprecated field, so it silences the warning it would otherwise
     * raise against itself - and a deprecated class is named in full rather than imported, since an import sits
     * outside the class and no annotation there can reach it.
     */
    @Test
    void testAnEncoderDoesNotWarnAboutTheDeprecatedFieldsItHasToCarry() throws Exception {
        File generated = generate("FIX44-deprecated-test.xml", "warnings");

        String deprecated = read(generated, "encoders/DeprecatedThingsEncoder.java");
        Assertions.assertThat(deprecated).contains("@SuppressWarnings(\"deprecation\")");
        Assertions.assertThat(deprecated).doesNotContain("import test.warnings.fields.NoLegs;");

        Assertions.assertThat(read(generate("FIX44-test.xml", "quiet"),
                        "encoders/MarketDataSnapshotFullRefreshEncoder.java"))
                .as("nothing to suppress, so nothing is said")
                .doesNotContain("@SuppressWarnings(\"deprecation\")");
    }
}
