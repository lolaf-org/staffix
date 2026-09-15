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

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Generates a FIX dictionary from a FIX Orchestra repository, at a chosen point of the standard's history.
 * <p>
 * What the two ceilings mean is in {@link VersionCut}, what is removed and why in {@link OrchestraPruner}, and how
 * the result is written in {@link FixDictionaryEmitter}. Run {@code versions} on the same file first: the cut
 * is only meaningful against the ladder that file happens to carry, and that ladder grows with every extension pack
 * the FIX Trading Community publishes.
 */
/*
 * Bound to initialize rather than to generate-resources, which is what a generated resource would suggest: Maven's
 * default lifecycle runs generate-sources before generate-resources, and this dictionary is the input of a code
 * generator that runs at generate-sources. Binding it to the phase its output belongs to would produce it after the
 * plugin that reads it, and the only sign would be a file-not-found from that plugin. Initialize is early enough for
 * anything, and does not depend on the order plugins happen to be declared in.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.INITIALIZE)
public class DictionaryGeneratorMojo extends AbstractMojo {

    /**
     * The orchestration to read, either the XML or a zip holding it - the repositories are published zipped, and
     * there is no reason to keep an unpacked copy of them in a source tree.
     */
    @Parameter(property = "orchestration", required = true)
    private File orchestration;

    /**
     * Where to write the dictionary. Named rather than derived from the repository so that it can be handed straight
     * to the encoders generator as its {@code dictionaryFile}.
     */
    @Parameter(property = "outputFile", defaultValue = "${project.build.directory}/generated-resources/fix-dictionary.xml")
    private File outputFile;

    /**
     * The last FIX version to keep, e.g. {@code FIX.4.4} or {@code FIX.5.0SP2}. Left out, every version in the
     * repository is kept. It also decides where the session layer goes: up to FIX.4.4 the standard header, trailer
     * and session messages belong to this dictionary, from FIX.5.0 they belong to FIXT.1.1 and are left out.
     */
    @Parameter(property = "upToVersion")
    private String upToVersion;

    /**
     * The last extension pack to keep. Left out, every extension pack of the kept versions is kept - which is what
     * "FIX.5.0SP2" means on its own, that release as amended. Set to 0 for a release as published, the cut that
     * reproduces the classic dictionaries.
     */
    @Parameter(property = "upToExtensionPack")
    private Integer upToExtensionPack;

    /**
     * Whether to keep what had already been deprecated at the cut.
     * <p>
     * On by default, because a published dictionary keeps its deprecated elements: deprecated is not withdrawn, it is
     * "do not use this in new work", and the field stays in the dictionary so that a peer still sending it can be
     * decoded. Measured against FIX44.xml - a FIX.4.4 cut at EP0 comes to 914 fields of its 916 with this on, and to
     * 901 with it off, the 13 in between being the repurchase and redemption fields the standard deprecated in 4.4
     * and kept.
     * <p>
     * Turning it off is the deliberate act of not generating encoders for what the FIX Trading Community has told
     * you not to use.
     */
    @Parameter(property = "includeDeprecated", defaultValue = "true")
    private boolean includeDeprecated;

    /**
     * Whether the dictionary should say which of the elements it keeps are deprecated, as {@code deprecated="true"}
     * on each of them - which of the standard's versions did the deprecating is the orchestration's business, not
     * something a dictionary stating one version should be mixing in.
     * <p>
     * The point is what reads the dictionary next: the encoders generator turns these into {@code @Deprecated} on the
     * generated field classes, setters, groups, enum constants and message types, so that a compiler tells whoever
     * uses one. Nothing else in a dictionary can express that, which is why an orchestration is worth cutting from at
     * all - the hand written dictionaries carry the deprecated elements without a word about their being deprecated.
     * <p>
     * Off by default. On, the file stops being byte for byte what a QuickFIX toolchain would have written, and that
     * is a decision to take rather than to inherit; a reader that does not know the attributes ignores them, so the
     * cost is only that the file is no longer the same file.
     * <p>
     * Meaningless with {@link #includeDeprecated} off, which removes the very elements this would mark, and saying
     * both is reported rather than silently doing nothing.
     */
    @Parameter(property = "markDeprecated", defaultValue = "false")
    private boolean markDeprecated;

    /**
     * A file listing the messages to keep, one per line, by name ({@code NewOrderSingle}) or by msgType ({@code D});
     * {@code #} starts a comment and blank lines are ignored. Left out, every message of the cut is kept, which is
     * what a version of the standard means on its own.
     * <p>
     * Worth setting only where the cut is wide: the encoders generated from a message are hundreds of classes once
     * its groups and components are expanded, so on FIX Latest the message list decides what the package costs to
     * build - and nothing else does, since the fields those messages reach are very nearly all of them either way.
     * <p>
     * Every entry has to name a message this cut holds, or the build fails saying which ones do not. A list is only
     * as good as the guarantee that it says what its author thought it said.
     * <p>
     * What the kept messages no longer reference is still written out: run the dictionary sanitizer over the result
     * to take those out, as the fix-latest module does.
     */
    @Parameter(property = "includeMessagesFile")
    private File includeMessagesFile;

    /**
     * Reports what the cut would remove and stops there, without writing anything.
     */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    /**
     * The version a dictionary claims to be when no ceiling was named: the newest one the repository still holds
     * after the cut, since an extension pack ceiling on its own can leave the newest version with nothing in it.
     */
    private static OrchestraVersion highestVersionOf(OrchestraRepository repository) {
        OrchestraVersion highest = null;
        for (String elementName : OrchestraRepository.VERSIONED_ELEMENTS) {
            for (org.w3c.dom.Element element : repository.elementsByTagName(elementName)) {
                OrchestraVersion version = OrchestraRepository.version(element, "added");
                if (version != null && version.isKnown() && (highest == null || version.compareTo(highest) > 0)) {
                    highest = version;
                }
            }
        }
        return highest == null ? OrchestraVersion.of(OrchestraVersion.LATEST_LABEL) : highest;
    }

    private MessageSelection readMessageSelection() throws MojoExecutionException, IOException {
        if (includeMessagesFile == null) {
            return null;
        }
        if (!includeMessagesFile.isFile()) {
            throw new MojoExecutionException("includeMessagesFile " + includeMessagesFile + " does not exist");
        }
        MessageSelection selection = MessageSelection.read(includeMessagesFile);
        if (selection.isEmpty()) {
            // a dictionary of no messages at all is never what was meant, and it would be written without complaint
            throw new MojoExecutionException("includeMessagesFile " + includeMessagesFile + " names no message. "
                    + "Leave the parameter out to keep every message of the cut.");
        }
        return selection;
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            // before the orchestration is parsed, which is a minute of work to reach a complaint about a file that
            // was never opened
            MessageSelection selection = readMessageSelection();

            OrchestraRepository repository = OrchestraRepository.load(orchestration);
            OrchestraVersion maxVersion = upToVersion == null ? null : OrchestraVersion.of(upToVersion);
            if (maxVersion != null && !maxVersion.isKnown()) {
                throw new MojoExecutionException("upToVersion " + upToVersion + " is not a version this plugin can "
                        + "place on the ladder, run the versions goal on " + orchestration + " to see the ladder it holds");
            }
            // read before the cut, which is what makes it a description of the repository rather than of the result
            ExtensionPackLadder ladder = ExtensionPackLadder.of(repository);
            if (maxVersion != null && upToExtensionPack != null) {
                // a ceiling that names no point of this repository's history is always a misreading of how the two
                // ceilings combine, and both misreadings produce a dictionary rather than an error: one silently
                // short of content, the other silently identical to a cut nobody asked for
                String rejection = ladder.rejectionOf(maxVersion, upToExtensionPack);
                if (rejection != null) {
                    throw new MojoExecutionException("Cannot cut " + orchestration + " to " + upToVersion
                            + " at extension pack " + upToExtensionPack + ". " + rejection);
                }
            }
            VersionCut cut = VersionCut.of(maxVersion, upToExtensionPack);
            if (markDeprecated && !includeDeprecated) {
                throw new MojoExecutionException("markDeprecated asks the dictionary to say which elements are "
                        + "deprecated, and includeDeprecated=false removes those elements, so there would be nothing "
                        + "left to mark. Turn one of the two off.");
            }

            getLog().info("Cutting " + repository.getRepositoryName() + " " + repository.getRepositoryVersion()
                    + " to " + cut + (includeDeprecated
                    ? ", deprecated elements kept" + (markDeprecated ? " and marked" : "") : ""));
            OrchestraPruner pruner = new OrchestraPruner(repository, cut, includeDeprecated, selection);
            PruneReport report = pruner.prune();
            if (!pruner.getUnmatchedMessages().isEmpty()) {
                throw new MojoExecutionException(includeMessagesFile + " names "
                        + pruner.getUnmatchedMessages().size() + " message(s) this cut does not hold: "
                        + String.join(", ", pruner.getUnmatchedMessages())
                        + ". An entry is a message name or a msgType, matched against the messages of " + cut + ".");
            }
            if (selection != null) {
                int kept = repository.elementsByTagName("fixr:message").size();
                int dropped = report.count(PruneReport.Reason.NOT_SELECTED, "fixr:message");
                getLog().info("Kept " + kept + " of the " + (kept + dropped) + " messages of this cut, from "
                        + includeMessagesFile.getName() + " - session messages are kept whatever it says, and "
                        + "written to the dictionary only where the session layer belongs to it");
            }
            getLog().info("Removed " + report.total() + " elements");
            for (String line : report.describe().split(System.lineSeparator())) {
                getLog().info("  " + line);
            }
            pruner.getWarnings().forEach(getLog()::warn);
            if (dryRun) {
                getLog().info("Dry run, " + outputFile + " not written");
                return;
            }

            OrchestraVersion target = maxVersion != null ? maxVersion : highestVersionOf(repository);
            // no ceiling asked for is not "no extension pack": it is that version as amended by every extension pack
            // that targeted it, and the dictionary should say which one that came to be
            int extensionPack = upToExtensionPack != null ? upToExtensionPack : ladder.effectiveCeilingOf(target);
            FixDictionaryEmitter emitter =
                    new FixDictionaryEmitter(repository, target, extensionPack, markDeprecated ? cut : null);
            getLog().info("Writing a " + target + " EP" + extensionPack + " dictionary to " + outputFile
                    + (emitter.includesSessionLayer()
                    ? ", session layer included" : ", session layer left to FIXT.1.1"));
            write(emitter);
            emitter.getWarnings().forEach(getLog()::warn);
        } catch (MojoExecutionException alreadyExplained) {
            throw alreadyExplained;
        } catch (Exception ex) {
            throw new MojoExecutionException("Failed to generate a dictionary from " + orchestration, ex);
        }
    }

    private void write(FixDictionaryEmitter emitter) throws IOException {
        File parent = outputFile.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        try (Writer out = new BufferedWriter(Files.newBufferedWriter(outputFile.toPath(), StandardCharsets.UTF_8))) {
            emitter.emit(out);
        }
    }
}
