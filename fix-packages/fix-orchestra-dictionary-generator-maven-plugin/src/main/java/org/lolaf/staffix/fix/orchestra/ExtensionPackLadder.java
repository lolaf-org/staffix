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

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Which extension pack numbers belong to which version, read off the orchestration in hand, and what that says about
 * whether a {@code (upToVersion, upToExtensionPack)} pair names a real point in the standard's history.
 * <p>
 * The two ceilings look independent and are not, because <b>extension pack numbers run in one increasing sequence
 * across versions</b> rather than restarting at each one - FIX Latest EP307 holds FIX.4.4 at EP1-38, FIX.5.0 at
 * EP41-72, FIX.5.0SP1 at EP76-97, FIX.5.0SP2 at EP98-259 and FIX.Latest at EP260-307. So an extension pack ceiling
 * is an absolute point on that sequence, and only some of those points mean anything for a given version:
 * <ul>
 *     <li>below the last extension pack of the <b>previous</b> version, the cut drops content that was published
 *     before the version being asked for even existed. The dictionary still carries that version's number, so what
 *     comes out claims to be something it is not - a FIX.5.0SP2 cut at EP0 loses 517 of its 1432 fields and says
 *     nothing about it;</li>
 *     <li>above the last extension pack of the version itself, the ceiling has nothing left to exclude - everything
 *     past it belongs to a later version, which the version ceiling already removed. FIX.4.4 up to EP39 is FIX.4.4
 *     up to EP38 is FIX.4.4 with no extension pack ceiling at all, three spellings of one cut, two of which invite
 *     the reader to believe in an EP39 of FIX.4.4.</li>
 * </ul>
 * Derived rather than tabulated, because the ladder grows with every extension pack the FIX Trading Community
 * publishes and this plugin is handed the file rather than owning it. The {@code versions} goal prints the same
 * ladder, which is where to look when one of these is rejected.
 */
final class ExtensionPackLadder {

    /**
     * Last extension pack per version, for the versions that have any. A version's first is not kept: what a cut has
     * to be checked against is where each version <b>ends</b>, both for its own ceiling and for the floor it sets
     * for the version after it.
     */
    private final NavigableMap<OrchestraVersion, Integer> lastExtensionPacks = new TreeMap<>();

    private ExtensionPackLadder(OrchestraRepository repository) {
        for (String elementName : OrchestraRepository.VERSIONED_ELEMENTS) {
            for (Element element : repository.elementsByTagName(elementName)) {
                OrchestraVersion version = OrchestraRepository.version(element, "added");
                Integer extensionPack = OrchestraRepository.extensionPack(element, "addedEP");
                if (version == null || extensionPack == null) {
                    continue;
                }
                lastExtensionPacks.merge(version, extensionPack, Math::max);
            }
        }
    }

    static ExtensionPackLadder of(OrchestraRepository repository) {
        return new ExtensionPackLadder(repository);
    }

    /**
     * The ceiling that means "this version as published": the last extension pack of everything that came before it,
     * since all of that was already in force when the version shipped. Zero for the first version to have extension
     * packs at all, and for anything older.
     */
    int asPublishedCeiling(OrchestraVersion version) {
        Map.Entry<OrchestraVersion, Integer> previous = lastExtensionPacks.lowerEntry(version);
        int ceiling = 0;
        while (previous != null) {
            ceiling = Math.max(ceiling, previous.getValue());
            previous = lastExtensionPacks.lowerEntry(previous.getKey());
        }
        return ceiling;
    }

    /**
     * The last extension pack that targeted this version, or null when none did.
     */
    Integer lastExtensionPackOf(OrchestraVersion version) {
        return lastExtensionPacks.get(version);
    }

    /**
     * The extension pack a cut to this version lands on when no ceiling was named at all, which is the version as
     * amended by everything that targeted it - and, for a version no extension pack ever targeted, the same point
     * as its release.
     */
    int effectiveCeilingOf(OrchestraVersion version) {
        Integer last = lastExtensionPackOf(version);
        return last != null ? last : asPublishedCeiling(version);
    }

    /**
     * Why the pair does not name a point of this repository's history, or null when it does. Phrased as the whole
     * explanation rather than as a code, because the number to use instead is the only thing the caller wants and it
     * is not guessable from the ladder without the reasoning above.
     *
     * @param version       the version ceiling, which must be one this ladder can place
     * @param extensionPack the extension pack ceiling being checked against it
     */
    String rejectionOf(OrchestraVersion version, int extensionPack) {
        int floor = asPublishedCeiling(version);
        if (extensionPack < floor) {
            return "upToExtensionPack=" + extensionPack + " is below EP" + floor + ", the last extension pack "
                    + "published before " + version + ", so the cut would also drop what the extension packs of "
                    + "earlier versions added - all of which was already in force when " + version + " shipped. "
                    + "Extension pack numbers run in one sequence across versions rather than restarting at each "
                    + "one, so " + version + " as published is upToExtensionPack=" + floor + ". To cut at a point "
                    + "before " + version + " existed, name the version that did: run the versions goal on this "
                    + "orchestration to see the ladder.";
        }
        Integer last = lastExtensionPackOf(version);
        if (last == null) {
            return extensionPack > floor
                    ? "No extension pack ever targeted " + version + ", so upToExtensionPack=" + extensionPack
                    + " excludes nothing the upToVersion ceiling has not excluded already. Leave it out, or use "
                    + floor + "."
                    : null;
        }
        if (extensionPack > last) {
            return "upToExtensionPack=" + extensionPack + " is past EP" + last + ", the last extension pack of "
                    + version + ", so it excludes nothing: everything above EP" + last + " belongs to a later "
                    + "version, which upToVersion has removed already. Use " + last + " for " + version + " as "
                    + "amended - or leave upToExtensionPack out, which says the same thing - and "
                    + asPublishedCeiling(version) + " for " + version + " as published.";
        }
        return null;
    }
}
