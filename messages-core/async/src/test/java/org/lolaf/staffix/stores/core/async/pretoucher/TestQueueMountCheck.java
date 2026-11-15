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

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLogger;
import com.github.valfirst.slf4jtest.TestLoggerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestQueueMountCheck {

    private static final String ROOT = "1 0 259:2 / / rw,relatime shared:1 - ext4 /dev/nvme0n1p2 rw";
    private static final String SHM = "30 23 0:27 / /dev/shm rw,nosuid,nodev shared:5 - tmpfs tmpfs rw,inode64";
    private static final String HUGE = "40 1 0:40 / /mnt/huge rw,relatime shared:9 - tmpfs tmpfs rw,size=4194304k,huge=within_size";
    private static final String SHMEM_NEVER = "always within_size advise [never] deny force";

    final TestLogger log = TestLoggerFactory.getTestLogger(QueueMountCheck.class);
    final Map<String, String> files = new HashMap<>();
    final QueueMountCheck check = new QueueMountCheck(path -> Optional.ofNullable(files.get(path)));

    @AfterEach
    void clearLogs() {
        TestLoggerFactory.clear();
    }

    private void given(String shmemEnabled, String... mounts) {
        files.put(QueueMountCheck.MOUNT_INFO, String.join("\n", mounts) + "\n");
        files.put(QueueMountCheck.SHMEM_ENABLED, shmemEnabled + "\n");
    }

    private List<String> warnings() {
        return log.getLoggingEvents().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(LoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    @Test
    void warnsOfATmpfsWithoutHugePagesWhilePretouched() {
        given(SHMEM_NEVER, ROOT, SHM);

        check.check(Paths.get("/dev/shm/queues"), true);

        assertThat(warnings()).singleElement().asString()
                .contains("/dev/shm/queues", "tmpfs /dev/shm without huge pages", "huge=never", "mount it with huge=within_size")
                .doesNotContain("set /sys");
    }

    @Test
    void acceptsATmpfsWithHugePagesWhilePretouched() {
        given(SHMEM_NEVER, ROOT, SHM, HUGE);

        check.check(Paths.get("/mnt/huge/queues"), true);

        assertThat(warnings()).isEmpty();
    }

    @Test
    void warnsOfHugePagesWithoutPretouching() {
        given(SHMEM_NEVER, ROOT, HUGE);

        check.check(Paths.get("/mnt/huge/queues"), false);

        assertThat(warnings()).singleElement().asString()
                .contains("/mnt/huge/queues", "with huge pages (huge=within_size", "set pretouchInterval above zero");
    }

    @Test
    void acceptsATmpfsWithoutHugePagesWithoutPretouching() {
        given(SHMEM_NEVER, ROOT, SHM);

        check.check(Paths.get("/dev/shm/queues"), false);

        assertThat(warnings()).isEmpty();
    }

    @Test
    void checksNothingOnDisk() {
        given(SHMEM_NEVER, ROOT, SHM);

        check.check(Paths.get("/var/lib/queues"), true);
        check.check(Paths.get("/var/lib/other-queues"), false);

        assertThat(warnings()).isEmpty();
    }

    @Test
    void forceGivesEveryTmpfsHugePages() {
        given("always within_size advise never deny [force]", ROOT, SHM);

        check.check(Paths.get("/dev/shm/queues"), false);

        assertThat(warnings()).singleElement().asString().contains("with huge pages (huge=never", "is force");
    }

    @Test
    void denyTakesHugePagesFromEveryTmpfs() {
        given("always within_size advise never [deny] force", ROOT, HUGE);

        check.check(Paths.get("/mnt/huge/queues"), true);

        assertThat(warnings()).singleElement().asString()
                .contains("without huge pages (huge=within_size", "and set " + QueueMountCheck.SHMEM_ENABLED + " to never");
    }

    @Test
    void theLongestMountPointWins() {
        given(SHMEM_NEVER, HUGE, ROOT, "50 40 0:50 / /mnt/huge/plain rw - tmpfs tmpfs rw");

        check.check(Paths.get("/mnt/huge/plain/queues"), true);
        check.check(Paths.get("/mnt/huge/queues"), true);

        assertThat(warnings()).singleElement().asString().contains("/mnt/huge/plain/queues");
    }

    @Test
    void theLastMountStackedOnAPointWins() {
        given(SHMEM_NEVER, ROOT, HUGE, "51 40 0:51 / /mnt/huge rw - tmpfs tmpfs rw");

        check.check(Paths.get("/mnt/huge/queues"), true);

        assertThat(warnings()).singleElement().asString().contains("huge=never");
    }

    @Test
    void matchesMountPointsByWholeNames() {
        given(SHMEM_NEVER, ROOT, "52 1 0:52 / /mnt/hug rw - tmpfs tmpfs rw");

        check.check(Paths.get("/mnt/huge/queues"), true);

        assertThat(warnings()).isEmpty();
    }

    @Test
    void readsAnEscapedMountPoint() {
        given(SHMEM_NEVER, ROOT, "53 1 0:53 / /mnt/my\\040queues rw - tmpfs tmpfs rw");

        check.check(Paths.get("/mnt/my queues/session"), true);

        assertThat(warnings()).singleElement().asString().contains("tmpfs /mnt/my queues without huge pages");
    }

    @Test
    void warnsOncePerDirectory() {
        given(SHMEM_NEVER, ROOT, SHM);

        check.check(Paths.get("/dev/shm/queues"), true);
        check.check(Paths.get("/dev/shm/queues"), true);
        check.check(Paths.get("/dev/shm/other-queues"), true);

        assertThat(warnings()).hasSize(2);
    }

    @Test
    void checksNothingWithoutMountInfo() {
        check.check(Paths.get("/dev/shm/queues"), true);

        assertThat(warnings()).isEmpty();
    }
}
