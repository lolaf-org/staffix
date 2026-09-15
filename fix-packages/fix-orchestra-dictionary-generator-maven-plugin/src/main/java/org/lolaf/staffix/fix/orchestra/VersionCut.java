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

/**
 * How far down the standard's history a dictionary is being cut: a version ceiling, an extension pack ceiling, or
 * both.
 * <p>
 * The two are independent filters, combined with and, and neither is inferred from the other. That works because of
 * how an orchestration is stamped: every element carries {@code added}, most also carry {@code addedEP}, and an
 * element without an {@code addedEP} came with the base release of its version. So an extension pack ceiling leaves
 * base elements alone - they precede every extension pack of their version - and a version ceiling on its own means
 * "that version with every extension pack that targeted it".
 * <p>
 * Extension pack numbers run in one increasing sequence across versions, and each version's range is disjoint from
 * the next (FIX.4.4 holds EP 1-38, FIX.5.0 EP 41-72, FIX.5.0SP2 EP 98-259, FIX.Latest EP 260-300 in the FIX Latest
 * EP300 repository), so a version cut and an extension pack cut are two ways of naming the same point. Naming both
 * only says something new when the point is inside a version: FIX.5.0SP2 up to EP161 is the release with the 40000+
 * tag block the Global Technical Committee opened there, and nothing published after it.
 * <p>
 * A ceiling of no extension pack at all - {@code upToExtensionPack} left out - has to mean "every extension pack",
 * not "the base release only": FIX.Latest has no base elements whatsoever and FIX.5.0SP2 has one, so the other
 * reading would answer an unqualified {@code upToVersion=FIX.Latest} with an empty dictionary. Asking for a release
 * as published is {@code upToExtensionPack=0}, which is the cut that reproduces the classic dictionaries.
 */
@Getter
public final class VersionCut {

    /**
     * The cut that keeps everything, i.e. the repository as it stands.
     */
    public static final VersionCut ALL = new VersionCut(null, null);

    private final OrchestraVersion maxVersion;
    private final Integer maxExtensionPack;

    private VersionCut(OrchestraVersion maxVersion, Integer maxExtensionPack) {
        this.maxVersion = maxVersion;
        this.maxExtensionPack = maxExtensionPack;
    }

    /**
     * @param maxVersion       the last version to keep, or null for no version ceiling
     * @param maxExtensionPack the last extension pack to keep, or null for no extension pack ceiling
     */
    public static VersionCut of(OrchestraVersion maxVersion, Integer maxExtensionPack) {
        return maxVersion == null && maxExtensionPack == null ? ALL : new VersionCut(maxVersion, maxExtensionPack);
    }

    /**
     * Whether something introduced at {@code version} by {@code extensionPack} existed at this cut.
     * <p>
     * An element carrying no version at all is kept. It ought not to happen and very nearly does not - FIX Latest
     * EP300 stamps all 24 thousand of its elements bar one, the MsgType code {@code BQ}
     * SettlementObligationReport - but this is a subtractive tool, and something that cannot be dated is not
     * thereby known to be too recent. Keeping it leaves a dictionary with one element more than asked for; dropping
     * it would quietly lose content for no stated reason.
     *
     * @param version       the {@code added} attribute value, or null when the element carries none
     * @param extensionPack the {@code addedEP} attribute value, or null for an element of a base release
     */
    public boolean keeps(OrchestraVersion version, Integer extensionPack) {
        if (maxVersion != null && version != null && version.compareTo(maxVersion) > 0) {
            return false;
        }
        return maxExtensionPack == null || extensionPack == null || extensionPack <= maxExtensionPack;
    }

    /**
     * Whether something deprecated at {@code version} by {@code extensionPack} was already deprecated at this cut,
     * which is the same question as {@link #keeps} asked of the deprecation rather than of the element. Something
     * deprecated after the cut was still current then, and stays in.
     *
     * @param version       the {@code deprecated} attribute value, or null for an element never deprecated
     * @param extensionPack the {@code deprecatedEP} attribute value, or null when the deprecation carries no
     *                      extension pack
     */
    public boolean isAlreadyDeprecated(OrchestraVersion version, Integer extensionPack) {
        return version != null && keeps(version, extensionPack);
    }

    @Override
    public String toString() {
        if (this == ALL || (maxVersion == null && maxExtensionPack == null)) {
            return "the whole repository";
        }
        StringBuilder description = new StringBuilder();
        description.append(maxVersion == null ? "every version" : "up to " + maxVersion);
        description.append(maxExtensionPack == null ? ", every extension pack" : ", up to EP" + maxExtensionPack);
        return description.toString();
    }
}
