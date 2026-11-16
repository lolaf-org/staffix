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
package org.lolaf.staffix.stores.core.async.pretoucher;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Warns, once per directory, when a queue on tmpfs is set up against its pretouching: without huge pages while
 * pretouched, appends take TLB misses huge pages would avoid; with huge pages but not pretouched, each new huge page is
 * zeroed on the appending thread. It only warns, since the queue works either way, and checks nothing off Linux.
 */
@Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class QueueMountCheck {

    public static final QueueMountCheck INSTANCE = new QueueMountCheck(QueueMountCheck::readSystemFile);

    static final String MOUNT_INFO = "/proc/self/mountinfo";
    static final String SHMEM_ENABLED = "/sys/kernel/mm/transparent_hugepage/shmem_enabled";

    private static final Pattern ESCAPED_CHARACTER = Pattern.compile("\\\\([0-7]{3})");
    private static final Pattern SELECTED_VALUE = Pattern.compile("\\[(\\w+)]");

    /**
     * Reads a file of {@code /proc} or {@code /sys}, empty when it does not exist.
     */
    @FunctionalInterface
    interface SystemFiles {
        Optional<String> read(String path);
    }

    private final SystemFiles systemFiles;
    private final Set<Path> checkedDirectories = ConcurrentHashMap.newKeySet();

    public void check(Path queueDirectory, boolean pretouching) {
        Path directory = realPath(queueDirectory);
        if (!checkedDirectories.add(directory)) {
            return;
        }
        Optional<Mount> tmpfs = systemFiles.read(MOUNT_INFO)
                .flatMap(mountInfo -> mountOf(directory, mountInfo))
                .filter(mount -> mount.getType().equals("tmpfs"));
        if (tmpfs.isEmpty()) {
            return;
        }
        String hugeOption = tmpfs.get().option("huge").orElse("never");
        String shmemEnabled = systemFiles.read(SHMEM_ENABLED).flatMap(QueueMountCheck::selectedValue).orElse("never");
        boolean hugePages = hasHugePages(hugeOption, shmemEnabled);
        if (pretouching && !hugePages) {
            log.warn("Async queue directory {} is on tmpfs {} without huge pages (huge={}, {} is {}), so appends take TLB "
                            + "misses huge pages would avoid: mount it with huge=within_size{}",
                    directory, tmpfs.get().getMountPoint(), hugeOption, SHMEM_ENABLED, shmemEnabled,
                    shmemEnabled.equals("deny") ? ", and set " + SHMEM_ENABLED + " to never" : "");
        } else if (!pretouching && hugePages) {
            log.warn("Async queue directory {} is on tmpfs {} with huge pages (huge={}, {} is {}) but pretouching is "
                            + "off, so each new huge page is zeroed on the appending thread: set pretouchInterval above "
                            + "zero, or use a tmpfs without huge pages",
                    directory, tmpfs.get().getMountPoint(), hugeOption, SHMEM_ENABLED, shmemEnabled);
        }
    }

    /**
     * {@code shmem_enabled} overrides the mount when it is {@code force} or {@code deny}. {@code huge=advise} counts as
     * none, since Chronicle never calls {@code madvise}.
     */
    private static boolean hasHugePages(String hugeOption, String shmemEnabled) {
        if (shmemEnabled.equals("force")) {
            return true;
        }
        if (shmemEnabled.equals("deny")) {
            return false;
        }
        return hugeOption.equals("within_size") || hugeOption.equals("always");
    }

    private static Path realPath(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException e) {
            return directory.toAbsolutePath().normalize();
        }
    }

    /**
     * The mount whose mount point is the longest prefix of {@code directory}; of mounts stacked on the same point, the
     * last one listed, which hides the others. Fields as in proc(5): the mount point is the fifth, and the type and
     * the super block options are the first and third after the {@code -} separator.
     */
    private static Optional<Mount> mountOf(Path directory, String mountInfo) {
        Mount longest = null;
        for (String line : mountInfo.split("\n")) {
            List<String> fields = Arrays.asList(line.split(" "));
            int separator = fields.indexOf("-");
            if (separator < 6 || separator + 3 >= fields.size()) {
                continue;
            }
            Path mountPoint = Paths.get(unescape(fields.get(4)));
            if (directory.startsWith(mountPoint)
                    && (longest == null || mountPoint.getNameCount() >= longest.getMountPoint().getNameCount())) {
                longest = new Mount(mountPoint, fields.get(separator + 1), fields.get(separator + 3));
            }
        }
        return Optional.ofNullable(longest);
    }

    /**
     * mountinfo writes a space, a tab, a newline and a backslash in a path as an octal escape.
     */
    private static String unescape(String path) {
        Matcher escaped = ESCAPED_CHARACTER.matcher(path);
        StringBuilder unescaped = new StringBuilder();
        while (escaped.find()) {
            escaped.appendReplacement(unescaped, Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(escaped.group(1), 8))));
        }
        return escaped.appendTail(unescaped).toString();
    }

    /**
     * The kernel lists every value and brackets the one set.
     */
    private static Optional<String> selectedValue(String values) {
        Matcher selected = SELECTED_VALUE.matcher(values);
        return selected.find() ? Optional.of(selected.group(1)) : Optional.empty();
    }

    private static Optional<String> readSystemFile(String path) {
        try {
            return Optional.of(Files.readString(Paths.get(path)));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Value
    private static class Mount {
        Path mountPoint;
        String type;
        String superOptions;

        Optional<String> option(String name) {
            return Arrays.stream(superOptions.split(","))
                    .filter(option -> option.startsWith(name + "="))
                    .map(option -> option.substring(name.length() + 1))
                    .findFirst();
        }
    }
}
