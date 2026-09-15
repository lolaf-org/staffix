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
package org.lolaf.staffix.fix.sanitizer;

import org.apache.maven.plugin.testing.MojoRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

public class FixDictionarySanitizerMojoTest {

    @Rule
    public MojoRule rule = new MojoRule();

    File inputFile;
    File outputFile;

    @Before
    public void setup() {
        File tempDir = new File("target/fix-sanitizer-test" + System.currentTimeMillis());
        tempDir.mkdirs();
        inputFile = new File(tempDir, "input.xml");
        outputFile = new File(tempDir, "output.xml");
    }

    @Test
    public void testSanitizeRemovesUnreferencedFields() throws Exception {
        FixDictionarySanitizerMojo mojo = setupFixSanitizerMojo("/test-fix.xml");

        mojo.execute();

        assertThat(outputFile).exists();

        // Parse and verify the output
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(outputFile);

        // Get all field definitions and extract field names
        NodeList fieldNodes = doc.getElementsByTagName("fields").item(0).getChildNodes();
        List<String> fieldNames = new ArrayList<>();

        for (int i = 0; i < fieldNodes.getLength(); i++) {
            if (fieldNodes.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE &&
                    "field".equals(fieldNodes.item(i).getNodeName())) {
                Element field = (Element) fieldNodes.item(i);
                fieldNames.add(field.getAttribute("name"));
            }
        }

        NodeList componentsNodes = doc.getElementsByTagName("components").item(0).getChildNodes();
        List<String> componentsNames = new ArrayList<>();

        for (int i = 0; i < componentsNodes.getLength(); i++) {
            if (componentsNodes.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE &&
                    "component".equals(componentsNodes.item(i).getNodeName())) {
                Element field = (Element) componentsNodes.item(i);
                componentsNames.add(field.getAttribute("name"));
            }
        }
        assertThat(componentsNames)
                .hasSize(1);

        // Verify that unreferenced fields are removed and we have exactly 11 referenced fields
        assertThat(fieldNames)
                .hasSize(11)
                .doesNotContain("UnusedField1", "UnusedField2", "UnusedField3")
                .containsExactlyInAnyOrder(
                        "BeginString", "MsgType", "CheckSum", "TestReqID", "ClOrdID",
                        "Side", "Symbol", "SecurityID", "NoSecurityAltID", "SecurityAltID", "AllocAccount");

        assertThat(fieldNames)
                .as("Fields from header, trailer, messages, components and nested groups should be present")
                .contains(
                        "BeginString",      // from header
                        "Symbol",           // from component
                        "AllocAccount",     // from group in message
                        "NoSecurityAltID",  // group field from component
                        "SecurityAltID"     // from nested component group
                );

        // Verify MsgType value enums are sanitized to only matching messages
        NodeList allFieldDefs = doc.getElementsByTagName("fields").item(0).getChildNodes();
        Element msgTypeField = null;
        for (int i = 0; i < allFieldDefs.getLength(); i++) {
            if (allFieldDefs.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE &&
                    "field".equals(allFieldDefs.item(i).getNodeName())) {
                Element f = (Element) allFieldDefs.item(i);
                if ("MsgType".equals(f.getAttribute("name"))) {
                    msgTypeField = f;
                    break;
                }
            }
        }
        assertThat(msgTypeField).isNotNull();

        List<String> valueDescriptions = new ArrayList<>();
        NodeList valueNodes = msgTypeField.getChildNodes();
        for (int i = 0; i < valueNodes.getLength(); i++) {
            if (valueNodes.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE &&
                    "value".equals(valueNodes.item(i).getNodeName())) {
                valueDescriptions.add(((Element) valueNodes.item(i)).getAttribute("description"));
            }
        }

        assertThat(valueDescriptions)
                .hasSize(2)
                .containsExactlyInAnyOrder("HEARTBEAT", "NEW_ORDER_SINGLE")
                .doesNotContain("EXECUTION_REPORT", "MARKET_DATA_REQUEST");
    }

    @Test
    public void testEmptyFieldsSection() throws Exception {
        FixDictionarySanitizerMojo mojo = setupFixSanitizerMojo("/test-fix-empty-fields.xml");

        rule.setVariableValueToObject(mojo, "inputFile", inputFile);
        rule.setVariableValueToObject(mojo, "outputFile", outputFile);

        // Should not throw exception
        assertThatCode(mojo::execute)
                .as("Processing empty fields section should not throw exception")
                .doesNotThrowAnyException();

        assertThat(outputFile)
                .as("Output file should be created")
                .exists();
    }

    /**
     * From FIX.5.0 the header and the trailer are empty - the session layer moved to FIXT.1.1 - so a dictionary of
     * that generation references none of its own session fields and every one of them is unreferenced. keepFields is
     * what stops them being removed, and without it they go, which is the whole reason it exists.
     */
    @Test
    public void testKeepFieldsHoldsOntoFieldsNothingReferences() throws Exception {
        FixDictionarySanitizerMojo kept = setupFixSanitizerMojo("/test-fix.xml");
        rule.setVariableValueToObject(kept, "keepFields", List.of("UnusedField1", "UnusedField2"));

        kept.execute();

        assertThat(fieldNamesOf(outputFile))
                .as("asked for, so kept even though no message, component or group references them")
                .contains("UnusedField1", "UnusedField2")
                .doesNotContain("UnusedField3");

        File withoutKeepFields = new File(outputFile.getParentFile(), "without-keep-fields.xml");
        FixDictionarySanitizerMojo removed = setupFixSanitizerMojo("/test-fix.xml");
        rule.setVariableValueToObject(removed, "outputFile", withoutKeepFields);

        removed.execute();

        assertThat(fieldNamesOf(withoutKeepFields))
                .as("and without keepFields the very same fields go")
                .doesNotContain("UnusedField1", "UnusedField2", "UnusedField3");
    }

    /**
     * The output can be a dictionary kept in a source tree beside the hand written ones, which all open on the
     * declaration.
     */
    @Test
    public void testTheOutputOpensOnTheXmlDeclaration() throws Exception {
        setupFixSanitizerMojo("/test-fix.xml").execute();

        assertThat(Files.readAllLines(outputFile.toPath()).get(0)).startsWith("<?xml ");
    }

    private List<String> fieldNamesOf(File file) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        NodeList fieldNodes = doc.getElementsByTagName("fields").item(0).getChildNodes();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            if (fieldNodes.item(i).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE
                    && "field".equals(fieldNodes.item(i).getNodeName())) {
                names.add(((Element) fieldNodes.item(i)).getAttribute("name"));
            }
        }
        return names;
    }

    private FixDictionarySanitizerMojo setupFixSanitizerMojo(String inputTestFixFileName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream(inputTestFixFileName)) {
            Files.copy(is, inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        File pom = new File("src/test/resources/test-pom");
        assertThat(pom).isNotNull().exists();

        FixDictionarySanitizerMojo mojo = rule.lookupConfiguredMojo(pom, "sanitize");
        assertThat(mojo).isNotNull();

        rule.setVariableValueToObject(mojo, "inputFile", inputFile);
        rule.setVariableValueToObject(mojo, "outputFile", outputFile);
        return mojo;
    }
}
