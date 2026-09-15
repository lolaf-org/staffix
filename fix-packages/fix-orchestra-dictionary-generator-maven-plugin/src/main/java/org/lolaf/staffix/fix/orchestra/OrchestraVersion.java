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

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A value of the {@code added}, {@code updated} or {@code deprecated} attribute of an Orchestra element, and the order
 * those values are in - which is the ladder a cut is made against.
 * <p>
 * The ladder is <b>derived rather than enumerated</b>: {@code FIX.<major>.<minor>[SP<n>]} is parsed and ordered
 * numerically, {@code FIX.Latest} sorts after every numbered version, and anything else sorts after that while
 * comparing by label so the order stays total and stable. A repository naming a version this does not recognise is
 * therefore reported rather than fatal - the FIX Trading Community adds labels on its own schedule, and an
 * orchestration is a file this plugin is handed rather than one it owns.
 * <p>
 * Deliberately not built on {@code org.lolaf.staffix.api.version.FixRegularVersion}: that enum is the set of versions
 * this engine speaks, which starts at FIX.4.2, while an orchestration legitimately describes elements added in
 * FIX.2.7 through FIX.4.1 and the cut has to place those too.
 */
public final class OrchestraVersion implements Comparable<OrchestraVersion> {

    /**
     * The rolling head of the standard, which every numbered version precedes.
     */
    public static final String LATEST_LABEL = "FIX.Latest";

    private static final Pattern NUMBERED = Pattern.compile("FIX\\.(\\d+)\\.(\\d+)(?:SP(\\d+))?");
    private static final int LATEST_RANK = Integer.MAX_VALUE - 1;
    private static final int UNKNOWN_RANK = Integer.MAX_VALUE;

    @Getter
    private final String label;
    private final int rank;
    private final int major;
    private final int minor;
    private final int servicePack;

    private OrchestraVersion(String label, int rank, int major, int minor, int servicePack) {
        this.label = label;
        this.rank = rank;
        this.major = major;
        this.minor = minor;
        this.servicePack = servicePack;
    }

    /**
     * @param label the attribute value, e.g. {@code FIX.4.4}, {@code FIX.5.0SP2} or {@code FIX.Latest}
     */
    public static OrchestraVersion of(String label) {
        Objects.requireNonNull(label, "label");
        if (LATEST_LABEL.equals(label)) {
            return new OrchestraVersion(label, LATEST_RANK, 0, 0, 0);
        }
        Matcher matcher = NUMBERED.matcher(label);
        if (!matcher.matches()) {
            return new OrchestraVersion(label, UNKNOWN_RANK, 0, 0, 0);
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int servicePack = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new OrchestraVersion(label, 0, major, minor, servicePack);
    }

    /**
     * Whether the label was one this understands, which is what tells a caller it can trust the ordering. An
     * unrecognised version sorts last rather than throwing, so a cut never silently keeps elements it cannot place.
     */
    public boolean isKnown() {
        return rank != UNKNOWN_RANK;
    }

    public boolean isLatest() {
        return rank == LATEST_RANK;
    }

    @Override
    public int compareTo(OrchestraVersion other) {
        if (rank != other.rank) {
            return Integer.compare(rank, other.rank);
        }
        if (rank != 0) {
            // both Latest, or both unrecognised: the label is all there is to go on
            return label.compareTo(other.label);
        }
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(servicePack, other.servicePack);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OrchestraVersion && label.equals(((OrchestraVersion) other).label);
    }

    @Override
    public int hashCode() {
        return label.hashCode();
    }

    @Override
    public String toString() {
        return label;
    }
}
