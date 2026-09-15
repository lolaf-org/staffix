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

import lombok.Getter;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * Writes a pruned orchestration out as a FIX dictionary, in the shape the encoders generator of this project reads.
 * <p>
 * The two models say the same things differently, and the translation is where the work is:
 * <ul>
 *     <li>Orchestra refers to everything <b>by id</b>, a dictionary <b>by name</b>;</li>
 *     <li>Orchestra keeps groups in a section of their own and points at them, a dictionary <b>inlines</b> each one
 *     where it is used, named after the NumInGroup field that counts it - {@code <group name="NoOrders">} rather
 *     than the group's own name;</li>
 *     <li>Orchestra types a field with a datatype or a code set, a dictionary with a type name and a list of
 *     {@code <value>} elements;</li>
 *     <li>presence is {@code presence="required"} against {@code required="Y"}, and everything else is optional.</li>
 * </ul>
 * <p>
 * Where the session layer goes depends on the version, which is why the cut decides it. Up to FIX.4.4 the standard
 * header, the trailer and the session messages belong to the application dictionary - FIX44.xml carries 29 header
 * fields, 3 trailer fields and 7 admin messages. From FIX.5.0 they moved to the FIXT.1.1 transport dictionary, and
 * FIX50SP2.xml has no {@code <header>}, no {@code <trailer>} and no Logon at all. Emitting them anyway would leave
 * every field of the header classified as a body field by the encoders generator, which derives that classification
 * from the {@code <header>} element alone.
 * <p>
 * <b>Deprecation</b> is carried over when asked for, as {@code deprecated="true"} on whatever the standard had
 * already deprecated at the cut.
 */
public final class FixDictionaryEmitter {

    private static final String STANDARD_HEADER = "StandardHeader";
    private static final String STANDARD_TRAILER = "StandardTrailer";
    private static final String SESSION_CATEGORY = "Session";
    private static final String DEPRECATED = "deprecated";
    private static final String DEPRECATED_EP = "deprecatedEP";
    /**
     * The last version whose application dictionary carries the session layer.
     */
    private static final OrchestraVersion LAST_VERSION_WITH_SESSION_LAYER = OrchestraVersion.of("FIX.4.4");
    /**
     * The fields FIXT.1.1 introduced to negotiate which application version a session speaks: ApplVerID(1128) and
     * CstmApplVerID(1129) on the header, RefApplVerID(1130) and RefCstmApplVerID(1131) in MsgTypeGrp, and
     * DefaultApplVerID(1137) on the Logon.
     * <p>
     * They need naming because the {@code added} stamps cannot be read at face value here. EP16 is where the FIX
     * Trading Community published FIXT.1.1, and the ladder puts EP16 inside FIX.4.4's range, so all five carry
     * {@code added="FIX.4.4" addedEP="16"} and a cut to FIX.4.4 as amended keeps them - EP16 is under the EP38 that
     * version ends at. The session layer the repository describes is FIXT.1.1's, and those references are the whole
     * of what makes it FIXT.1.1's rather than FIX.4.4's: FIX44.xml has none of the five, its Logon has no
     * DefaultApplVerID and its MsgTypeGrp is RefMsgType and MsgDirection alone.
     * <p>
     * A dictionary carrying its own session layer is by definition a pre-FIXT one, so this is exactly the case
     * {@link #includesSessionLayer()} already decides, and the exclusion is scoped to it. From FIX.5.0 the session
     * layer is left to FIXT.1.1 and the field definitions stay where the standard puts them - FIX50SP2.xml defines
     * 1128 through 1131, and only FIXT11.xml defines 1137.
     * <p>
     * Every reference to the five sits in the session layer or is dated FIX.5.0 (Reject and BusinessMessageReject
     * carry 1130 and 1131 from then on, which any pre-5.0 cut removes on the version alone), so dropping the
     * definitions cannot strand a body field.
     */
    private static final Set<String> FIXT_APPLICATION_VERSION_FIELDS = Set.of("1128", "1129", "1130", "1131", "1137");

    private final OrchestraRepository repository;
    private final OrchestraVersion targetVersion;
    private final Integer extensionPack;
    /**
     * Null when deprecation is not being written. Otherwise the cut, because a stamp is only a fact about the
     * dictionary being written if the deprecation had already happened at the point it was cut at - see
     * {@link #deprecationOf}.
     */
    private final VersionCut cut;
    /**
     * -- GETTER --
     * Whatever could not be expressed, for the caller to log. Empty on the repositories this was built against; a
     * line here means the dictionary is missing something rather than being wrong about it.
     */
    @Getter
    private final List<String> warnings = new ArrayList<>();

    private final Map<String, Element> fieldsById = new HashMap<>();
    private final Map<String, Element> componentsById = new HashMap<>();
    private final Map<String, Element> groupsById = new HashMap<>();
    private final Map<String, Element> codeSetsByName = new HashMap<>();
    /**
     * Fields, and components or groups, that belong to FIXT.1.1 rather than to this dictionary - empty unless the
     * cut leaves the session layer to FIXT.1.1. See {@link #indexSessionOnly()}. Containers are keyed the way the
     * walk keys them, {@code c} or {@code g} and the id.
     */
    private final Set<String> sessionOnlyFields = new HashSet<>();
    private final Set<String> sessionOnlyContainers = new HashSet<>();

    /**
     * @param repository    the orchestration, cut already
     * @param targetVersion the version the dictionary claims to be, which also decides whether the session layer is
     *                      part of it
     */
    public FixDictionaryEmitter(OrchestraRepository repository, OrchestraVersion targetVersion) {
        this(repository, targetVersion, null);
    }

    /**
     * @param repository    the orchestration, cut already
     * @param targetVersion the version the dictionary claims to be, which also decides whether the session layer is
     *                      part of it
     * @param extensionPack the extension pack the cut landed on, written out as an attribute of the root element so
     *                      that the file says which point of the standard it is; null leaves it unsaid
     */
    public FixDictionaryEmitter(OrchestraRepository repository, OrchestraVersion targetVersion,
                                Integer extensionPack) {
        this(repository, targetVersion, extensionPack, null);
    }

    public FixDictionaryEmitter(OrchestraRepository repository, OrchestraVersion targetVersion,
                                Integer extensionPack, VersionCut cut) {
        this.repository = repository;
        this.targetVersion = targetVersion;
        this.extensionPack = extensionPack;
        this.cut = cut;
        index();
        indexSessionOnly();
    }

    private static String required(Element reference) {
        return "required".equals(reference.getAttribute("presence")) ? "Y" : "N";
    }

    private static Element firstChild(Element parent, String tagName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && ((Element) child).getTagName().equals(tagName)) {
                return (Element) child;
            }
        }
        return null;
    }

    private static String indent(int depth) {
        StringBuilder indent = new StringBuilder(depth * 2);
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }
        return indent.toString();
    }

    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The {@code deprecated} attributes to write on an element, or nothing when it was never deprecated or when the
     * caller did not ask for them.
     * <p>
     * Several candidates because a dictionary sometimes has one element where the orchestration has three, and the
     * first that carries a stamp wins. A group is the case that needs it: Orchestra keeps the group, the reference
     * to it and the NumInGroup that counts it apart, and any of the three can be deprecated on its own, while the
     * dictionary inlines all of it into a single {@code <group>}. Everything else is asked about itself alone, so
     * that the file says what the repository says rather than a conclusion drawn from it - a field deprecated
     * everywhere and a field deprecated in this one message are different facts, and both survive the trip.
     * <p>
     * <b>Only a deprecation that had already happened at the cut is written</b>, which is the same question
     * {@link OrchestraPruner} asks before removing one. An orchestration describes the standard as it stands now, so
     * a FIX.4.4 field the FIX Trading Community deprecated in FIX.5.0 carries that stamp in a repository being cut
     * to FIX.4.4 - and in a FIX.4.4 dictionary that field is current, because 4.4 is before the deprecation. Writing
     * the stamp anyway would put 82 deprecations in a FIX.4.4 cut where 13 belong.
     * <p>
     * The answer is written as {@code deprecated="true"} and nothing more. The version that deprecated the element
     * is the orchestration's business, not the dictionary's: a dictionary states one version of the standard, and a
     * {@code deprecated="FIX.5.0"} inside a file that says {@code major="4" minor="4"} mixes two of them for no
     * reader's benefit. Deprecated at this cut or not is the whole of what a consumer of this file can act on.
     */
    private String deprecationOf(Element... candidates) {
        if (cut == null) {
            return "";
        }
        for (Element candidate : candidates) {
            if (candidate != null && cut.isAlreadyDeprecated(
                    OrchestraRepository.version(candidate, DEPRECATED),
                    OrchestraRepository.extensionPack(candidate, DEPRECATED_EP))) {
                return " deprecated=\"true\"";
            }
        }
        return "";
    }

    private void index() {
        repository.elementsByTagName("fixr:field").forEach(field -> fieldsById.put(field.getAttribute("id"), field));
        repository.elementsByTagName("fixr:component").forEach(component -> componentsById.put(component.getAttribute("id"), component));
        repository.elementsByTagName("fixr:group").forEach(group -> groupsById.put(group.getAttribute("id"), group));
        repository.elementsByTagName("fixr:codeSet").forEach(codeSet -> codeSetsByName.put(codeSet.getAttribute("name"), codeSet));
    }

    /**
     * Whether the session layer belongs in this dictionary, which is true up to FIX.4.4 and false from FIX.5.0 on.
     */
    public boolean includesSessionLayer() {
        return targetVersion.compareTo(LAST_VERSION_WITH_SESSION_LAYER) <= 0;
    }

    /**
     * Whether this field is one FIXT.1.1 brought with it and this dictionary therefore predates, see
     * {@link #FIXT_APPLICATION_VERSION_FIELDS}.
     */
    private boolean isFixtApplicationVersionField(String id) {
        return includesSessionLayer() && FIXT_APPLICATION_VERSION_FIELDS.contains(id);
    }

    /**
     * The fields that belong to FIXT.1.1 rather than to this dictionary, which is the general form of the rule
     * {@link #FIXT_APPLICATION_VERSION_FIELDS} states for five fields by hand.
     * <p>
     * From FIX.5.0 the session layer moved to FIXT.1.1, and the published FIX50SP2.xml draws the line in a place
     * worth being exact about. It is not "session or not": the standard header and trailer fields stay -
     * BeginString(8), BodyLength(9), MsgType(35), MsgSeqNum(34), SenderCompID(49), TargetCompID(56),
     * SendingTime(52), CheckSum(10), ApplVerID(1128) are all defined there, because they are on the wire of every
     * message this dictionary describes even though its {@code <header>} is empty. What is not defined there is what
     * only a session <em>message</em> ever carries: EncryptMethod(98) and HeartBtInt(108) of the Logon,
     * TestReqID(112), the BeginSeqNo/EndSeqNo/NewSeqNo of resending, RefTagID(371) and SessionRejectReason(373) of
     * the Reject, DefaultApplVerID(1137) - twenty fields in all.
     * <p>
     * So the rule is reachability, not a list: a field goes when every reference to it lies inside a session
     * message, directly or through a component or group only session messages use. The header and the trailer are
     * roots of the walk in their own right, next to the application messages - they are on the wire of every message
     * this dictionary describes, and that, rather than the fact that every message in the repository happens to
     * reference them, is why their fields belong here. Written this way the rule keeps being right as extension
     * packs add fields to the session layer, which a hand written list would not.
     * <p>
     * A field nothing references at all is not session-only and is left alone - that is the dictionary sanitizer's
     * business, and reachability from the application side is written once, there.
     * <p>
     * The same walk answers the same question about components and groups, and it has to: a component only a session
     * message reaches is written out like any other, and if its fields have gone while it has not, the dictionary
     * references definitions it does not carry. FIX50SP2.xml has no MsgTypeGrp for exactly this reason.
     */
    private void indexSessionOnly() {
        if (includesSessionLayer()) {
            return;
        }
        Set<String> fromApplication = new HashSet<>();
        Set<String> fromSession = new HashSet<>();
        Set<String> applicationVisited = new HashSet<>();
        Set<String> sessionVisited = new HashSet<>();
        for (Element message : repository.elementsByTagName("fixr:message")) {
            boolean session = SESSION_CATEGORY.equals(message.getAttribute("category"));
            collectFieldReferences(message, session ? fromSession : fromApplication,
                    session ? sessionVisited : applicationVisited);
        }
        for (String componentName : List.of(STANDARD_HEADER, STANDARD_TRAILER)) {
            Element component = findComponentByName(componentName);
            if (component != null) {
                collectFieldReferences(component, fromApplication, applicationVisited);
            }
        }
        fromSession.removeAll(fromApplication);
        sessionVisited.removeAll(applicationVisited);
        sessionOnlyFields.addAll(fromSession);
        sessionOnlyContainers.addAll(sessionVisited);
    }

    /**
     * Every field an element reaches, following component and group references through to their definitions.
     * {@code visited} both stops a group that contains itself and keeps this linear over a repository where the same
     * component is referenced hundreds of times.
     */
    private void collectFieldReferences(Element parent, Set<String> fields, Set<String> visited) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                continue;
            }
            Element reference = (Element) child;
            String id = reference.getAttribute("id");
            switch (reference.getTagName()) {
                // the field that counts a group is a reference to a field like any other, and NoMsgTypes(384) of the
                // Logon's MsgTypeGrp is one of the twenty this has to reach
                case "fixr:numInGroup":
                case "fixr:fieldRef":
                    fields.add(id);
                    break;
                case "fixr:componentRef":
                    if (visited.add("c" + id) && componentsById.containsKey(id)) {
                        collectFieldReferences(componentsById.get(id), fields, visited);
                    }
                    break;
                case "fixr:groupRef":
                    if (visited.add("g" + id) && groupsById.containsKey(id)) {
                        collectFieldReferences(groupsById.get(id), fields, visited);
                    }
                    break;
                case "fixr:structure":
                    collectFieldReferences(reference, fields, visited);
                    break;
                default:
                    // annotations and the like carry no structure
                    break;
            }
        }
    }

    public void emit(Writer out) throws IOException {
        out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.write("<fix" + rootAttributes() + ">\n");
        writeSessionLayer(out);
        writeMessages(out);
        writeComponents(out);
        writeFields(out);
        out.write("</fix>\n");
        out.flush();
    }

    private String rootAttributes() {
        String version = targetVersion.isLatest()
                ? " major=\"5\" minor=\"0\" servicepack=\"Latest\""
                : numberedVersionAttributes();
        return version + (extensionPack == null ? "" : " extensionpack=\"" + extensionPack + "\"");
    }

    private String numberedVersionAttributes() {
        String label = targetVersion.getLabel();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("FIX\\.(\\d+)\\.(\\d+)(?:SP(\\d+))?").matcher(label);
        if (!matcher.matches()) {
            throw new IllegalStateException("Version " + label + " cannot be written as a dictionary version");
        }
        String servicePack = matcher.group(3) == null ? "" : " servicepack=\"SP" + matcher.group(3) + "\"";
        return " major=\"" + matcher.group(1) + "\" minor=\"" + matcher.group(2) + "\"" + servicePack;
    }

    private void writeSessionLayer(Writer out) throws IOException {
        if (!includesSessionLayer()) {
            // section 4.4.1 of FIXT.1.1: from FIX.5.0 the session layer is a dictionary of its own. Both elements are
            // still written, empty, because the encoders generator asks for them by name
            out.write("  <header/>\n  <trailer/>\n");
            return;
        }
        writeSessionComponent(out, "header", STANDARD_HEADER);
        writeSessionComponent(out, "trailer", STANDARD_TRAILER);
    }

    private void writeSessionComponent(Writer out, String elementName, String componentName) throws IOException {
        Element component = findComponentByName(componentName);
        if (component == null) {
            warnings.add("The orchestration holds no " + componentName + " component, so <" + elementName
                    + "> is empty and every field it would have held counts as a body field");
            out.write("  <" + elementName + "/>\n");
            return;
        }
        out.write("  <" + elementName + ">\n");
        writeStructure(out, component, 2, new HashSet<>());
        out.write("  </" + elementName + ">\n");
    }

    private void writeMessages(Writer out) throws IOException {
        out.write("  <messages>\n");
        for (Element message : repository.elementsByTagName("fixr:message")) {
            String category = message.getAttribute("category");
            boolean admin = SESSION_CATEGORY.equals(category);
            if (admin && !includesSessionLayer()) {
                // the session messages moved to FIXT.1.1 with the header, and FIX50SP2.xml has none of them
                continue;
            }
            out.write("    <message name=\"" + attribute(message.getAttribute("name"))
                    + "\" msgtype=\"" + attribute(message.getAttribute("msgType"))
                    + "\" msgcat=\"" + (admin ? "admin" : "app") + "\""
                    + deprecationOf(message) + ">\n");
            Element structure = firstChild(message, "fixr:structure");
            if (structure != null) {
                writeStructure(out, structure, 3, new HashSet<>());
            }
            out.write("    </message>\n");
        }
        out.write("  </messages>\n");
    }

    private void writeComponents(Writer out) throws IOException {
        out.write("  <components>\n");
        for (Element component : repository.elementsByTagName("fixr:component")) {
            String name = component.getAttribute("name");
            if (STANDARD_HEADER.equals(name) || STANDARD_TRAILER.equals(name)) {
                // written as <header>/<trailer>, or left to FIXT.1.1
                continue;
            }
            if (sessionOnlyContainers.contains("c" + component.getAttribute("id"))) {
                // only a session message reaches it, and its fields have gone to FIXT.1.1 with it
                continue;
            }
            out.write("    <component name=\"" + attribute(name) + "\"" + deprecationOf(component) + ">\n");
            writeStructure(out, component, 3, new HashSet<>());
            out.write("    </component>\n");
        }
        out.write("  </components>\n");
    }

    /**
     * The references of a message structure, a component or a group, as the {@code field}, {@code component} and
     * {@code group} elements a dictionary uses. Components stay references - their definition is written once, in
     * the components section - while groups are inlined, which is what {@code visitedGroups} guards: a group that
     * reached itself would inline for ever.
     */
    private void writeStructure(Writer out, Element parent, int depth, Set<String> visitedGroups) throws IOException {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                continue;
            }
            Element reference = (Element) child;
            switch (reference.getTagName()) {
                case "fixr:fieldRef":
                    writeFieldReference(out, reference, depth);
                    break;
                case "fixr:componentRef":
                    writeComponentReference(out, reference, depth);
                    break;
                case "fixr:groupRef":
                    writeGroupReference(out, reference, depth, visitedGroups);
                    break;
                default:
                    // annotations, numInGroup and anything else the schema allows here carry no structure
                    break;
            }
        }
    }

    private void writeFieldReference(Writer out, Element reference, int depth) throws IOException {
        String id = reference.getAttribute("id");
        if (isFixtApplicationVersionField(id)) {
            return;
        }
        Element field = fieldsById.get(id);
        if (field == null) {
            warnings.add("Reference to field " + id + " has no definition and is dropped");
            return;
        }
        out.write(indent(depth) + "<field name=\"" + attribute(field.getAttribute("name")) + "\" required=\""
                + required(reference) + "\"" + deprecationOf(reference) + "/>\n");
    }

    private void writeComponentReference(Writer out, Element reference, int depth) throws IOException {
        Element component = componentsById.get(reference.getAttribute("id"));
        if (component == null) {
            warnings.add("Reference to component " + reference.getAttribute("id") + " has no definition and is dropped");
            return;
        }
        String name = component.getAttribute("name");
        if (STANDARD_HEADER.equals(name) || STANDARD_TRAILER.equals(name)) {
            // a message carries no reference to them in a dictionary: the header is a section of its own, and from
            // FIX.5.0 it is not even in this file
            return;
        }
        out.write(indent(depth) + "<component name=\"" + attribute(name) + "\" required=\"" + required(reference)
                + "\"" + deprecationOf(reference) + "/>\n");
    }

    /**
     * A repeating group, inlined and named after the field that counts it - which is what the encoders generator
     * expects, it looks the group's name up in the fields and refuses anything that is not a NumInGroup.
     */
    private void writeGroupReference(Writer out, Element reference, int depth, Set<String> visitedGroups) throws IOException {
        String groupId = reference.getAttribute("id");
        Element group = groupsById.get(groupId);
        if (group == null) {
            warnings.add("Reference to group " + groupId + " has no definition and is dropped");
            return;
        }
        Element counter = firstChild(group, "fixr:numInGroup");
        Element counterField = counter == null ? null : fieldsById.get(counter.getAttribute("id"));
        if (counterField == null) {
            warnings.add("Group " + group.getAttribute("name") + " has no NumInGroup field and is dropped");
            return;
        }
        if (!visitedGroups.add(groupId)) {
            warnings.add("Group " + group.getAttribute("name") + " contains itself, so the inner one is dropped");
            return;
        }
        out.write(indent(depth) + "<group name=\"" + attribute(counterField.getAttribute("name")) + "\" required=\""
                + required(reference) + "\"" + deprecationOf(reference, group, counter) + ">\n");
        writeStructure(out, group, depth + 1, visitedGroups);
        out.write(indent(depth) + "</group>\n");
        visitedGroups.remove(groupId);
    }

    private void writeFields(Writer out) throws IOException {
        out.write("  <fields>\n");
        for (Element field : repository.elementsByTagName("fixr:field")) {
            if (isFixtApplicationVersionField(field.getAttribute("id"))
                    || sessionOnlyFields.contains(field.getAttribute("id"))) {
                continue;
            }
            String name = field.getAttribute("name");
            String type = field.getAttribute("type");
            Element codeSet = codeSetsByName.get(type);
            String dictionaryType = FieldTypes.toDictionaryType(
                    codeSet == null ? type : codeSet.getAttribute("type"), name);
            Map<String, Code> values = codeSet == null ? Map.of() : valuesOf(codeSet);
            String open = "    <field number=\"" + attribute(field.getAttribute("id")) + "\" name=\"" + attribute(name)
                    + "\" type=\"" + dictionaryType + "\"" + deprecationOf(field);
            if (values.isEmpty()) {
                if (codeSet != null) {
                    // its codes were all cut away, which leaves a field of the underlying type and no enumeration
                    warnings.add("Field " + name + " is typed by code set " + type + ", which has no code left at "
                            + "this cut, so it is written as a plain " + dictionaryType);
                }
                out.write(open + "/>\n");
                continue;
            }
            out.write(open + ">\n");
            for (Map.Entry<String, Code> value : values.entrySet()) {
                out.write("      <value enum=\"" + attribute(value.getKey()) + "\" description=\""
                        + attribute(value.getValue().description) + "\"" + value.getValue().deprecation + "/>\n");
            }
            out.write("    </field>\n");
        }
        out.write("  </fields>\n");
    }

    /**
     * The codes of a code set, by value.
     * <p>
     * Both halves have to come out unique, and neither is unique in the source. A value repeated - a code renamed
     * with the old name kept - would leave a dictionary nothing can switch on, so the first wins and the clash is
     * reported. A <b>description</b> repeated is the more interesting one: it becomes the name of an enum constant,
     * and FIX Latest holds pairs that differ only in case, {@code Euribor} and {@code EURIBOR} of
     * BenchmarkCurveNameCodeSet among them. Both are legal on the wire, so both stay and the later one is numbered -
     * dropping either would leave a value the peer may send and this side could not name.
     * <p>
     * A code deprecated on its own says so, and a code set deprecated as a whole says so on every one of its codes:
     * a dictionary has no element standing for the code set itself, only the values it flattens into, so this is the
     * one place the fact has to go.
     */
    private Map<String, Code> valuesOf(Element codeSet) {
        Map<String, Code> values = new LinkedHashMap<>();
        Set<String> descriptions = new HashSet<>();
        for (Node child = codeSet.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element) || !((Element) child).getTagName().equals("fixr:code")) {
                continue;
            }
            Element code = (Element) child;
            String value = code.getAttribute("value");
            String description = FieldTypes.toValueDescription(code.getAttribute("name"));
            if (!descriptions.add(description)) {
                String numbered = description;
                for (int suffix = 2; !descriptions.add(numbered); suffix++) {
                    numbered = description + "_" + suffix;
                }
                warnings.add("Code set " + codeSet.getAttribute("name") + " names two codes " + description
                        + ", so value " + value + " is written as " + numbered);
                description = numbered;
            }
            Code previous = values.putIfAbsent(value, new Code(description, deprecationOf(code, codeSet)));
            if (previous != null) {
                warnings.add("Code set " + codeSet.getAttribute("name") + " gives value " + value + " twice, as "
                        + previous.description + " and as " + description + ", so " + description + " is dropped");
            }
        }
        return values;
    }

    private Element findComponentByName(String name) {
        for (Element component : repository.elementsByTagName("fixr:component")) {
            if (name.equals(component.getAttribute("name"))) {
                return component;
            }
        }
        return null;
    }

    /**
     * A code as a dictionary writes it: the description that becomes an enum constant, and whatever deprecation
     * belongs on it.
     */
    private static final class Code {

        private final String description;
        private final String deprecation;

        private Code(String description, String deprecation) {
            this.description = description;
            this.deprecation = deprecation;
        }
    }
}
