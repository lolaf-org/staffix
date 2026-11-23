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
package org.lolaf.staffix.ha.replication;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Free TCP ports for the tests, as staffix's {@code TestPorts} hands them out and with the same lock files, so test
 * JVMs of both never take the same port. Below the ephemeral range, which outgoing connections draw from; each port is
 * locked until the JVM exits, as a free port is bound seconds after it is handed out.
 */
@UtilityClass
public class TestingPorts {

    private static final int FIRST_PORT = 20_000;
    private static final int PORTS_COUNT = 12_768;
    private static final int REFUSED_CANDIDATE_JUMP = 61;
    private static final AtomicInteger NEXT_PORT = new AtomicInteger(new SecureRandom().nextInt(PORTS_COUNT));
    private static final Path RESERVATIONS = Paths.get(System.getProperty("java.io.tmpdir"), "staffix-test-ports");
    /**
     * Kept reachable: a channel collected would close, releasing its lock.
     */
    private static final List<FileChannel> HELD = new CopyOnWriteArrayList<>();

    public static int findFree() {
        for (int attempt = 0; attempt < 200; attempt++) {
            int port = nextCandidate(1);
            if (!reserve(port)) {
                nextCandidate(REFUSED_CANDIDATE_JUMP);
                continue;
            }
            try (ServerSocket probe = new ServerSocket(port)) {
                return probe.getLocalPort();
            } catch (IOException alreadyBound) {
                // held by a process that is not a test, try the next one
            }
        }
        throw new IllegalStateException("no free port found below the ephemeral range");
    }

    private static int nextCandidate(int step) {
        return FIRST_PORT + Math.floorMod(NEXT_PORT.getAndAdd(step), PORTS_COUNT);
    }

    /**
     * The OS drops the lock when this JVM exits.
     *
     * @return false if another JVM holds the port
     */
    private static boolean reserve(int port) {
        FileChannel channel = null;
        try {
            Files.createDirectories(RESERVATIONS);
            channel = FileChannel.open(RESERVATIONS.resolve(port + ".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                return false;
            }
            HELD.add(channel);
            return true;
        } catch (OverlappingFileLockException alreadyHeldByThisJvm) {
            closeQuietly(channel);
            return false;
        } catch (IOException noReservationPossible) {
            // nowhere to write them: the probe alone, rather than failing over a port very probably free
            closeQuietly(channel);
            return true;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing to do about it while handing out a port
            }
        }
    }
}
