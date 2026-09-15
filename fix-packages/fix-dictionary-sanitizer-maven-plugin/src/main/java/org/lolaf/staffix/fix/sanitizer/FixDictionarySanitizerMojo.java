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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maven plugin that sanitizes FIX XML files by removing field definitions
 * that are not referenced in any message, component, or group.
 */
@Mojo(name = "sanitize", defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class FixDictionarySanitizerMojo extends AbstractMojo {

    /**
     * The input FIX XML file to sanitize.
     */
    @Parameter(property = "inputFile", required = true)
    private File inputFile;

    /**
     * The output sanitized FIX XML file.
     */
    @Parameter(property = "outputFile", required = true)
    private File outputFile;

    /**
     * Sanitize MsgType value enums to only keep values matching defined messages, should be disabled if using FIXT (FIX v 5)
     */
    @Parameter(property = "sanitizeMsgTypeField", defaultValue = "true")
    private boolean sanitizeMsgTypeField = true;

    /**
     * Field names to keep whatever happens, even when nothing in the dictionary references them.
     * <p>
     * Needed because "referenced" is read from the header, the trailer, the messages and the components, and from
     * FIX.5.0 the header and the trailer are empty - the session layer moved to FIXT.1.1, and a FIX.5.0+ dictionary
     * carries {@code <header/>} and {@code <trailer/>} with the session fields still defined below. Nothing points at
     * BeginString, BodyLength, MsgType, MsgSeqNum, SenderCompID, TargetCompID, SendingTime, CheckSum or ApplVerID
     * there, so without this they are all removed, and the generated package silently loses field classes its
     * FIX.4.4 sibling has.
     */
    @Parameter(property = "keepFields")
    private List<String> keepFields;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            getLog().info("Sanitizing FIX XML file: " + inputFile.getAbsolutePath());

            // Parse the XML file
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringComments(false);
            factory.setIgnoringElementContentWhitespace(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputFile);

            // Collect all referenced component names and remove unreferenced components first
            Set<String> referencedComponents = collectReferencedComponents(doc);
            int removedComponents = removeUnreferencedComponents(doc, referencedComponents);
            getLog().info("Removed " + removedComponents + " unreferenced component definitions");

            // Collect all referenced field names (after component cleanup)
            Set<String> referencedFields = collectReferencedFields(doc);
            getLog().info("Found " + referencedFields.size() + " referenced fields");
            if (keepFields != null && !keepFields.isEmpty()) {
                referencedFields.addAll(keepFields);
                getLog().info("Keeping " + keepFields.size() + " further fields asked for by keepFields");
            }

            // Remove unreferenced fields from the <fields> section
            int removedCount = removeUnreferencedFields(doc, referencedFields);
            getLog().info("Removed " + removedCount + " unreferenced field definitions");

            if (sanitizeMsgTypeField) {
                // Sanitize MsgType value enums to only keep values matching defined messages
                int removedValues = sanitizeMsgTypeValues(doc);
                getLog().info("Removed " + removedValues + " unreferenced MsgType values");
            }

            // Write the sanitized XML to output file
            writeDocument(doc, outputFile);

            getLog().info("Sanitized FIX XML written to: " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            throw new MojoExecutionException("Error sanitizing FIX XML file", e);
        }
    }

    /**
     * Collects all component names referenced in header, trailer, messages,
     * and transitively through other referenced components.
     */
    private Set<String> collectReferencedComponents(Document doc) {
        Set<String> directRefs = new HashSet<>();

        // Collect component references from header, trailer, messages
        collectComponentRefsFromSections(doc.getElementsByTagName("header"), directRefs);
        collectComponentRefsFromSections(doc.getElementsByTagName("trailer"), directRefs);
        collectComponentRefsFromSections(doc.getElementsByTagName("messages"), directRefs);

        // Transitively resolve: referenced components may reference other components
        Set<String> allRefs = new HashSet<>(directRefs);
        NodeList componentsNodeList = doc.getElementsByTagName("components");
        if (componentsNodeList.getLength() > 0) {
            Element componentsElement = (Element) componentsNodeList.item(0);
            boolean changed = true;
            while (changed) {
                changed = false;
                NodeList componentDefs = componentsElement.getElementsByTagName("component");
                for (int i = 0; i < componentDefs.getLength(); i++) {
                    Element comp = (Element) componentDefs.item(i);
                    if (comp.getParentNode() == componentsElement && allRefs.contains(comp.getAttribute("name"))) {
                        Set<String> nested = new HashSet<>();
                        collectComponentRefsRecursive(comp, nested);
                        for (String name : nested) {
                            if (allRefs.add(name)) {
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        return allRefs;
    }

    private void collectComponentRefsFromSections(NodeList nodeList, Set<String> refs) {
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                collectComponentRefsRecursive((Element) node, refs);
            }
        }
    }

    private void collectComponentRefsRecursive(Element element, Set<String> refs) {
        if ("component".equals(element.getTagName())) {
            refs.add(element.getAttribute("name"));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectComponentRefsRecursive((Element) child, refs);
            }
        }
    }

    /**
     * Removes component definitions from the <components> section that are not in the referenced set.
     */
    private int removeUnreferencedComponents(Document doc, Set<String> referencedComponents) {
        int removedCount = 0;
        NodeList componentsNodeList = doc.getElementsByTagName("components");

        if (componentsNodeList.getLength() == 0) {
            return 0;
        }

        Element componentsElement = (Element) componentsNodeList.item(0);
        NodeList componentDefs = componentsElement.getChildNodes();

        Set<Node> toRemove = new HashSet<>();
        for (int i = 0; i < componentDefs.getLength(); i++) {
            Node node = componentDefs.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "component".equals(node.getNodeName())) {
                String name = ((Element) node).getAttribute("name");
                if (!referencedComponents.contains(name)) {
                    toRemove.add(node);
                }
            }
        }

        for (Node comp : toRemove) {
            Node prevSibling = comp.getPreviousSibling();
            if (prevSibling != null && prevSibling.getNodeType() == Node.TEXT_NODE) {
                String text = prevSibling.getTextContent();
                if (text != null && text.trim().isEmpty()) {
                    componentsElement.removeChild(prevSibling);
                }
            }
            componentsElement.removeChild(comp);
            removedCount++;
        }

        return removedCount;
    }

    /**
     * Collects all field names referenced in header, trailer, messages, and components.
     */
    private Set<String> collectReferencedFields(Document doc) {
        Set<String> referencedFields = new HashSet<>();

        // Collect from header
        collectFieldsFromElement(doc.getElementsByTagName("header"), referencedFields);

        // Collect from trailer
        collectFieldsFromElement(doc.getElementsByTagName("trailer"), referencedFields);

        // Collect from messages
        collectFieldsFromElement(doc.getElementsByTagName("messages"), referencedFields);

        // Collect from components
        collectFieldsFromElement(doc.getElementsByTagName("components"), referencedFields);

        return referencedFields;
    }

    /**
     * Recursively collects field names from elements (including nested groups and components).
     */
    private void collectFieldsFromElement(NodeList nodeList, Set<String> referencedFields) {
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                collectFieldsRecursive((Element) node, referencedFields);
            }
        }
    }

    /**
     * Recursively traverses the DOM tree to find all field references.
     */
    private void collectFieldsRecursive(Element element, Set<String> referencedFields) {
        // If this is a field or group element, add its name
        if ("field".equals(element.getTagName()) || "group".equals(element.getTagName())) {
            String fieldName = element.getAttribute("name");
            referencedFields.add(fieldName);
        }

        // Process child elements (for groups and components)
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                collectFieldsRecursive((Element) child, referencedFields);
            }
        }
    }

    /**
     * Removes field definitions from the <fields> section that are not in the referenced set.
     */
    private int removeUnreferencedFields(Document doc, Set<String> referencedFields) {
        int removedCount = 0;
        NodeList fieldsNodeList = doc.getElementsByTagName("fields");

        if (fieldsNodeList.getLength() == 0) {
            return 0;
        }

        Element fieldsElement = (Element) fieldsNodeList.item(0);
        NodeList fieldDefinitions = fieldsElement.getElementsByTagName("field");

        // Collect fields to remove (can't remove while iterating)
        Set<Node> toRemove = new HashSet<>();
        for (int i = 0; i < fieldDefinitions.getLength(); i++) {
            Element field = (Element) fieldDefinitions.item(i);
            String fieldName = field.getAttribute("name");

            if (!referencedFields.contains(fieldName)) {
                toRemove.add(field);
            }
        }

        // Remove unreferenced fields
        for (Node field : toRemove) {
            // Also remove the text node (whitespace/newline) before the field if it exists
            Node prevSibling = field.getPreviousSibling();
            if (prevSibling != null && prevSibling.getNodeType() == Node.TEXT_NODE) {
                String text = prevSibling.getTextContent();
                if (text != null && text.trim().isEmpty()) {
                    fieldsElement.removeChild(prevSibling);
                }
            }
            fieldsElement.removeChild(field);
            removedCount++;
        }

        return removedCount;
    }

    /**
     * Sanitizes the MsgType field's value enums, removing values whose description
     * does not match any defined message name.
     */
    private int sanitizeMsgTypeValues(Document doc) {
        // Collect defined message names converted to uppercase
        Set<String> messageDescriptions = new HashSet<>();
        NodeList messageNodes = doc.getElementsByTagName("message");
        for (int i = 0; i < messageNodes.getLength(); i++) {
            Element msg = (Element) messageNodes.item(i);
            messageDescriptions.add(msg.getAttribute("name").toUpperCase());
        }

        // Find the MsgType field definition in <fields>
        NodeList fieldsNodeList = doc.getElementsByTagName("fields");
        if (fieldsNodeList.getLength() == 0) {
            return 0;
        }

        Element fieldsElement = (Element) fieldsNodeList.item(0);
        NodeList fieldDefs = fieldsElement.getChildNodes();
        Element msgTypeField = null;
        for (int i = 0; i < fieldDefs.getLength(); i++) {
            Node node = fieldDefs.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "field".equals(node.getNodeName())) {
                if ("MsgType".equals(((Element) node).getAttribute("name"))) {
                    msgTypeField = (Element) node;
                    break;
                }
            }
        }

        if (msgTypeField == null) {
            return 0;
        }

        // Remove value children whose description doesn't match any defined message
        int removedCount = 0;
        Set<Node> toRemove = new HashSet<>();
        NodeList valueNodes = msgTypeField.getChildNodes();
        for (int i = 0; i < valueNodes.getLength(); i++) {
            Node node = valueNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && "value".equals(node.getNodeName())) {
                String description = ((Element) node).getAttribute("description")
                        .toUpperCase().replace("_", "");
                if (!messageDescriptions.contains(description)) {
                    toRemove.add(node);
                }
            }
        }

        for (Node value : toRemove) {
            Node prevSibling = value.getPreviousSibling();
            if (prevSibling != null && prevSibling.getNodeType() == Node.TEXT_NODE) {
                String text = prevSibling.getTextContent();
                if (text != null && text.trim().isEmpty()) {
                    msgTypeField.removeChild(prevSibling);
                }
            }
            msgTypeField.removeChild(value);
            removedCount++;
        }

        return removedCount;
    }

    /**
     * Writes the sanitized document to the output file.
     * <p>
     * The declaration is written by hand rather than left to the transformer, which spells it
     * {@code standalone="no"} and puts the root element on the same line. The output can be a dictionary kept in a
     * source tree beside the hand written ones, and those all open on {@code <?xml version="1.0" encoding="UTF-8"?>}
     * and a newline; a file that only nearly matches its neighbours is a diff waiting to be read as a change.
     */
    private void writeDocument(Document doc, File outputFile) throws Exception {
        outputFile.getParentFile().mkdirs();

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        try (Writer out = new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8)) {
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            out.write(System.lineSeparator());
            out.flush();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            out.write(System.lineSeparator());
        }
    }
}
