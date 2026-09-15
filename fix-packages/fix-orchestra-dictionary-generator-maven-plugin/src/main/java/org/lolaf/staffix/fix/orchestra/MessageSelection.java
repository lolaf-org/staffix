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

import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * The messages a dictionary is asked to keep, read from a file of one entry per line.
 * <p>
 * FIX Latest describes 173 messages and the encoders generated from them are the bulk of what such a package costs -
 * one message is hundreds of classes once its groups and components are expanded - so a package that means to ship
 * rather than to prove the toolchain works has to say which of them it is about. The file is the place to say it:
 * a list this long does not belong in a POM, and keeping it beside the dictionary it produces makes the two reviewable
 * against each other.
 * <p>
 * An entry is a message name ({@code NewOrderSingle}) or a msgType ({@code D}), whichever reads better where it is
 * written; names are what the shipped lists use, since a msgType tells a reader nothing. {@code #} starts a comment,
 * to the end of the line, and blank lines are ignored.
 * <p>
 * Nothing here decides what to do about an entry that matches no message - that is
 * {@link OrchestraPruner#getUnmatchedMessages()} and the mojo that fails the build over it. The distinction matters:
 * an entry is matched against the messages that survived the cut, not against the repository as published, so a list
 * naming a message that FIX Latest has and FIX.4.4 has not is wrong for a 4.4 cut and right for a Latest one.
 */
public final class MessageSelection {

    private static final char COMMENT = '#';

    /**
     * In the order they were written, which is the order the failure message lists them in.
     */
    private final Set<String> entries;

    private MessageSelection(Set<String> entries) {
        this.entries = entries;
    }

    /**
     * Reads a selection from a file, dropping comments, blank lines and duplicates.
     */
    public static MessageSelection read(File file) throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            int comment = line.indexOf(COMMENT);
            String entry = (comment < 0 ? line : line.substring(0, comment)).trim();
            if (!entry.isEmpty()) {
                entries.add(entry);
            }
        }
        return new MessageSelection(entries);
    }

    static MessageSelection of(String... entries) {
        return new MessageSelection(new LinkedHashSet<>(Arrays.asList(entries)));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /**
     * Whether the message is one of those asked for, by name or by msgType.
     */
    public boolean keeps(Element message) {
        return entries.contains(message.getAttribute("name")) || entries.contains(message.getAttribute("msgType"));
    }

    /**
     * The entries that match none of the given messages, in the order they were written.
     * <p>
     * A message can be matched twice, by its name and by its msgType, and then both entries are accounted for: saying
     * the same thing twice is redundant rather than wrong, and reporting one of the two as unknown would send whoever
     * reads the failure looking for a typo that is not there.
     */
    public List<String> unmatchedIn(Collection<Element> messages) {
        Set<String> matched = new HashSet<>();
        for (Element message : messages) {
            String name = message.getAttribute("name");
            String msgType = message.getAttribute("msgType");
            if (entries.contains(name)) {
                matched.add(name);
            }
            if (entries.contains(msgType)) {
                matched.add(msgType);
            }
        }
        List<String> unmatched = new ArrayList<>();
        for (String entry : entries) {
            if (!matched.contains(entry)) {
                unmatched.add(entry);
            }
        }
        return unmatched;
    }
}
