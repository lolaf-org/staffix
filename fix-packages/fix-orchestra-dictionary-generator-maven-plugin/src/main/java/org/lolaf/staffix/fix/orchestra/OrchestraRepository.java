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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * A FIX Orchestra repository loaded as a DOM, and the handful of things the cut needs to ask of it.
 * <p>
 * A DOM rather than the JAXB model published with Orchestra: what happens here is subtraction - whole elements are
 * dropped and what is left is written out - so the schema buys nothing that a document does not already carry, and
 * the plugin stays free of the {@code io.fixprotocol} model and its transitive dependencies.
 * <p>
 * The file may be the XML itself or a zip holding it, because the repositories are published zipped and there is no
 * reason to keep an unpacked copy of 8 MB in a source tree next to the 800 KB it came in.
 */
public final class OrchestraRepository {

    /**
     * The elements a cut applies to, i.e. everything an orchestration stamps with {@code added}. Refs are in the list
     * because a reference carries its own history: a field can be older than the message that came to use it.
     */
    static final List<String> VERSIONED_ELEMENTS = List.of(
            "fixr:field", "fixr:message", "fixr:component", "fixr:group", "fixr:codeSet", "fixr:code",
            "fixr:fieldRef", "fixr:componentRef", "fixr:groupRef");

    private final Document document;

    private OrchestraRepository(Document document) {
        this.document = document;
    }

    /**
     * @param file the orchestration, either the XML or a zip holding exactly one XML entry
     */
    public static OrchestraRepository load(File file) throws IOException, ParserConfigurationException, org.xml.sax.SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // the repositories carry no doctype and are not fetched over a network, and a build must not be made to
        // depend on either being true of a file it is handed
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        if (isZip(file)) {
            try (ZipFile zip = new ZipFile(file); InputStream entry = openSingleXmlEntry(zip, file)) {
                return new OrchestraRepository(builder.parse(entry));
            }
        }
        return new OrchestraRepository(builder.parse(file));
    }

    private static boolean isZip(File file) {
        return file.getName().toLowerCase().endsWith(".zip");
    }

    private static InputStream openSingleXmlEntry(ZipFile zip, File file) throws IOException {
        ZipEntry found = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (!candidate.isDirectory() && candidate.getName().toLowerCase().endsWith(".xml")) {
                if (found != null) {
                    throw new IOException("Orchestration " + file + " holds more than one XML entry, "
                            + found.getName() + " and " + candidate.getName() + ", so which one to read is ambiguous");
                }
                found = candidate;
            }
        }
        if (found == null) {
            throw new IOException("Orchestration " + file + " holds no XML entry");
        }
        return zip.getInputStream(found);
    }

    /**
     * The {@code added}, {@code updated} or {@code deprecated} version of an element, or null when it carries none.
     */
    public static OrchestraVersion version(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        return value.isEmpty() ? null : OrchestraVersion.of(value);
    }

    /**
     * The {@code addedEP}, {@code updatedEP} or {@code deprecatedEP} of an element, or null when it belongs to the
     * base release of its version rather than to an extension pack.
     * <p>
     * A base element is spelled two ways in the published repositories: the attribute is left out, or it is stamped
     * {@code -1}. FIX Latest EP300 does both - 315 elements carry {@code addedEP="-1"}, among them 198 codes and 16
     * groups of FIX.4.4 - and they mean the same thing, so both answer null here. Extension pack numbering starts at
     * 1, so nothing meaningful is being swallowed.
     */
    public static Integer extensionPack(Element element, String attribute) {
        String value = element.getAttribute(attribute);
        if (value.isEmpty()) {
            return null;
        }
        try {
            int extensionPack = Integer.parseInt(value);
            return extensionPack > 0 ? extensionPack : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    public Document getDocument() {
        return document;
    }

    /**
     * The repository's own name and version, as {@code <fixr:repository name= version=>} carries them.
     */
    public String getRepositoryVersion() {
        Element root = document.getDocumentElement();
        return root == null ? null : root.getAttribute("version");
    }

    public String getRepositoryName() {
        Element root = document.getDocumentElement();
        return root == null ? null : root.getAttribute("name");
    }

    /**
     * Every element of the given tag name, as a list rather than the live {@link NodeList} the DOM hands out: the
     * callers here remove elements while iterating, which a live view does not survive.
     */
    public List<Element> elementsByTagName(String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        List<Element> elements = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element) {
                elements.add((Element) node);
            }
        }
        return elements;
    }
}
