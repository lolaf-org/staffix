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
import org.lolaf.staffix.fix.orchestra.PruneReport.Reason;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.*;

/**
 * Cuts an orchestration back to a point in the standard's history, in place.
 * <p>
 * Two things happen, in this order and for good reason. First everything introduced after the cut goes, along with
 * anything already deprecated at it when deprecated elements were not asked for - a decision taken one element at a
 * time, from the {@code added} and {@code deprecated} stamps each of them carries. What that leaves is not yet a
 * coherent repository: a message may still reference a field that no longer exists, a group may have lost the
 * NumInGroup field that made it a group, a component may have been emptied. So the second part runs to a fixed
 * point, dropping whatever no longer has anything to refer to, until a pass changes nothing.
 * <p>
 * A {@link MessageSelection} may narrow it further, between the two: the messages not asked for go, and the same
 * fixed point then empties whatever was made only of them. What it deliberately does not do is drop a field or a
 * component that no surviving message references any more - that one is left whole, and the dictionary sanitizer
 * removes it from the dictionary afterwards. Reachability is already written there, and having it in one place is
 * worth more than saving the sanitizer a pass.
 * <p>
 * The result is <b>everything that existed at the cut, described as it is now</b>, which is not the same as the
 * repository as it stood then. An orchestration keeps one definition per element - the current one - so a field
 * added in FIX.4.2 and reshaped by a later extension pack comes out in its reshaped form. Reconstructing the older
 * shape would need the history the file does not carry.
 */
public final class OrchestraPruner {

    private static final String ADDED = "added";
    private static final String ADDED_EP = "addedEP";
    private static final String DEPRECATED = "deprecated";
    private static final String DEPRECATED_EP = "deprecatedEP";
    private static final String ID = "id";
    /**
     * As {@code FixDictionaryEmitter} spells it, which is what decides where the session layer is written.
     */
    private static final String SESSION_CATEGORY = "Session";

    private final OrchestraRepository repository;
    private final VersionCut cut;
    private final boolean includeDeprecated;
    private final MessageSelection selection;
    private final PruneReport report = new PruneReport();
    @Getter
    private final List<String> warnings = new ArrayList<>();
    /**
     * Entries of the selection that named no message of this cut, for the mojo to fail the build over.
     */
    @Getter
    private final List<String> unmatchedMessages = new ArrayList<>();

    public OrchestraPruner(OrchestraRepository repository, VersionCut cut, boolean includeDeprecated) {
        this(repository, cut, includeDeprecated, null);
    }

    public OrchestraPruner(OrchestraRepository repository, VersionCut cut, boolean includeDeprecated,
                           MessageSelection selection) {
        this.repository = repository;
        this.cut = cut;
        this.includeDeprecated = includeDeprecated;
        this.selection = selection;
    }

    private static Element firstChild(Element parent, String tagName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && ((Element) child).getTagName().equals(tagName)) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Whether the element is still part of the document. {@code getElementsByTagName} is asked for a fresh list on
     * every sweep, but an element removed earlier in the same sweep - or removed along with its parent - is still
     * held by the list being walked, and removing it twice would count it twice.
     */
    private static boolean isStillAttached(Element element) {
        for (Node node = element; node != null; node = node.getParentNode()) {
            if (node.getNodeType() == Node.DOCUMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the cut and returns what it removed.
     */
    public PruneReport prune() {
        // read before anything goes: a code set removed by the cut takes its datatype with it, and a field still
        // typed by it needs that datatype
        Map<String, String> codeSetDatatypes = codeSetDatatypes();
        removeEverythingAfterTheCut();
        retypeFieldsWhoseCodeSetIsGone(codeSetDatatypes);
        removeMessagesNotSelected();
        removeWhatIsLeftDangling();
        return report;
    }

    /**
     * Keeps the messages the selection asked for and drops the rest.
     * <p>
     * After the cut rather than before it, so that the selection is read against the messages this dictionary could
     * have held: an entry naming a message that exists in FIX Latest and not at a FIX.4.4 cut is a mistake worth a
     * failed build, and matching before the cut would let it pass as a message silently removed a moment later.
     * <p>
     * Session messages are kept whatever the list says. Which application messages a package is about is a choice;
     * whether it can log on is not, and up to FIX.4.4 the session layer belongs to this dictionary rather than to
     * FIXT.1.1. A list is written to name the business vocabulary, so a FIX.4.2 one would leave out Logon and
     * Heartbeat without ever meaning to, and produce a dictionary that cannot open a session.
     */
    private void removeMessagesNotSelected() {
        if (selection == null) {
            return;
        }
        List<Element> messages = new ArrayList<>();
        for (Element message : repository.elementsByTagName("fixr:message")) {
            if (isStillAttached(message)) {
                messages.add(message);
            }
        }
        unmatchedMessages.addAll(selection.unmatchedIn(messages));
        for (Element message : messages) {
            if (!selection.keeps(message) && !SESSION_CATEGORY.equals(message.getAttribute("category"))) {
                remove(message, Reason.NOT_SELECTED, "fixr:message");
            }
        }
    }

    private Map<String, String> codeSetDatatypes() {
        List<Element> codeSets = repository.elementsByTagName("fixr:codeSet");
        Map<String, String> datatypes = new HashMap<>(codeSets.size());
        for (Element codeSet : codeSets) {
            datatypes.put(codeSet.getAttribute("name"), codeSet.getAttribute("type"));
        }
        return datatypes;
    }

    /**
     * A field can be older than the code set that types it, and then a cut between the two leaves the field behind
     * pointing at a name that no longer resolves to anything - field 1039 UnderlyingSettlMethod is dated FIX.4.4 and
     * its SettlMethodCodeSet FIX.5.0, so any cut at FIX.4.4 through FIX.5.0-as-published produces exactly that.
     * <p>
     * The field is not wrong, only less precise than it will become: it holds a String either way, and the
     * enumeration of what that String may be had not been written down yet. So it is retyped to whatever the code
     * set was built on and keeps its place, which is what the standard itself describes. Dropping the field instead
     * would silently lose it from the dictionary, and leaving it alone hands the code set's *name* to
     * {@link FieldTypes#toDictionaryType} and fails the build with nothing pointing at the cause.
     */
    private void retypeFieldsWhoseCodeSetIsGone(Map<String, String> codeSetDatatypes) {
        Set<String> surviving = new HashSet<>();
        for (Element codeSet : repository.elementsByTagName("fixr:codeSet")) {
            if (isStillAttached(codeSet)) {
                surviving.add(codeSet.getAttribute("name"));
            }
        }
        for (Element field : repository.elementsByTagName("fixr:field")) {
            String type = field.getAttribute("type");
            if (!isStillAttached(field) || surviving.contains(type)) {
                continue;
            }
            String datatype = codeSetDatatypes.get(type);
            if (datatype == null) {
                // a plain datatype rather than a code set, which is the ordinary case
                continue;
            }
            field.setAttribute("type", datatype);
            warnings.add("Field " + field.getAttribute("name") + " is older than the code set " + type
                    + " that types it, which this cut removed, so it is written as a plain " + datatype
                    + " with no enumeration");
        }
    }

    private void removeEverythingAfterTheCut() {
        for (String elementName : OrchestraRepository.VERSIONED_ELEMENTS) {
            for (Element element : repository.elementsByTagName(elementName)) {
                if (!isStillAttached(element)) {
                    // a parent went in this same pass and took it along
                    continue;
                }
                if (!cut.keeps(OrchestraRepository.version(element, ADDED),
                        OrchestraRepository.extensionPack(element, ADDED_EP))) {
                    remove(element, Reason.AFTER_CUT, elementName);
                } else if (!includeDeprecated && cut.isAlreadyDeprecated(
                        OrchestraRepository.version(element, DEPRECATED),
                        OrchestraRepository.extensionPack(element, DEPRECATED_EP))) {
                    remove(element, Reason.DEPRECATED, elementName);
                }
            }
        }
    }

    /**
     * Drops what the first pass left without a target or without content, over and over until a pass changes
     * nothing: removing a component can empty the group that held it, which can strand the groupRefs pointing at
     * that group, so one sweep is never enough.
     */
    private void removeWhatIsLeftDangling() {
        boolean changed = true;
        while (changed) {
            changed = removeReferencesToWhatIsGone();
            changed |= removeGroupsWithoutTheirCounter();
            changed |= removeEmptied("fixr:component");
            changed |= removeEmptied("fixr:group");
            changed |= removeEmptiedMessages();
        }
    }

    private boolean removeReferencesToWhatIsGone() {
        boolean changed = false;
        changed |= removeReferencesToWhatIsGone("fixr:fieldRef", "fixr:field");
        changed |= removeReferencesToWhatIsGone("fixr:componentRef", "fixr:component");
        changed |= removeReferencesToWhatIsGone("fixr:groupRef", "fixr:group");
        return changed;
    }

    private boolean removeReferencesToWhatIsGone(String referenceName, String definitionName) {
        Set<String> defined = idsOf(definitionName);
        boolean changed = false;
        for (Element reference : repository.elementsByTagName(referenceName)) {
            if (isStillAttached(reference) && !defined.contains(reference.getAttribute(ID))) {
                remove(reference, Reason.DANGLING, referenceName);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * A repeating group is a NumInGroup field and the fields it counts. Lose the counter and what is left cannot be
     * expressed as a group at all, so the group goes with it.
     */
    private boolean removeGroupsWithoutTheirCounter() {
        Set<String> fields = idsOf("fixr:field");
        boolean changed = false;
        for (Element group : repository.elementsByTagName("fixr:group")) {
            if (!isStillAttached(group)) {
                continue;
            }
            Element counter = firstChild(group, "fixr:numInGroup");
            if (counter == null || !fields.contains(counter.getAttribute(ID))) {
                remove(group, Reason.DANGLING, "fixr:group");
                changed = true;
            }
        }
        return changed;
    }

    /**
     * A component or group that has lost every reference it was made of. Its own references elsewhere go on the next
     * sweep, which is what makes the loop necessary.
     */
    private boolean removeEmptied(String elementName) {
        boolean changed = false;
        for (Element element : repository.elementsByTagName(elementName)) {
            if (isStillAttached(element) && !hasAnyReference(element)) {
                remove(element, Reason.DANGLING, elementName);
                changed = true;
            }
        }
        return changed;
    }

    private boolean removeEmptiedMessages() {
        boolean changed = false;
        for (Element message : repository.elementsByTagName("fixr:message")) {
            if (!isStillAttached(message)) {
                continue;
            }
            Element structure = firstChild(message, "fixr:structure");
            if (structure == null || !hasAnyReference(structure)) {
                remove(message, Reason.DANGLING, "fixr:message");
                changed = true;
            }
        }
        return changed;
    }

    private boolean hasAnyReference(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element)) {
                continue;
            }
            String name = ((Element) child).getTagName();
            if (name.equals("fixr:fieldRef") || name.equals("fixr:componentRef") || name.equals("fixr:groupRef")) {
                return true;
            }
            if (name.equals("fixr:structure") && hasAnyReference((Element) child)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> idsOf(String elementName) {
        List<Element> elements = repository.elementsByTagName(elementName);
        Set<String> ids = new HashSet<>(elements.size());
        for (Element element : elements) {
            if (isStillAttached(element)) {
                ids.add(element.getAttribute(ID));
            }
        }
        return ids;
    }

    private void remove(Element element, Reason reason, String elementName) {
        Node parent = element.getParentNode();
        if (parent != null) {
            parent.removeChild(element);
        }
        report.record(reason, elementName);
    }
}
