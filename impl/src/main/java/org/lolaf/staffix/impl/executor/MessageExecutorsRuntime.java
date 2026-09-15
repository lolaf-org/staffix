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
package org.lolaf.staffix.impl.executor;

import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.Startable;
import org.lolaf.staffix.api.executor.MessageExecutorSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The executor threads of one connector, and the sessions' claims on them.
 * <p>
 * What is shared is only the pool of {@link ExecutorThread}s and the round-robin that spreads routing keys over it.
 * The executors themselves belong to a session, through the {@link SessionMessageExecutors} it is handed at
 * construction: a routing key is looked up in the session's own executors, never in a map shared by every session
 * here.
 */
public class MessageExecutorsRuntime extends Startable.SimpleStartable<MessageExecutorsRuntime> {

    private final List<ExecutorThread> executorThreads;
    private final List<SessionMessageExecutors> sessionsExecutors;
    private int nextExecutorThread;

    public MessageExecutorsRuntime(MessageExecutorSettings messageExecutorSettings) {
        executorThreads = new ArrayList<>();
        sessionsExecutors = new ArrayList<>();
        for (int i = 0; i < messageExecutorSettings.getExecutorsThreadsCount(); i++) {
            executorThreads.add(new ExecutorThread(messageExecutorSettings, messageExecutorSettings.getInstanceId(), i));
        }
    }

    /**
     * The executors of one session, to be held by it and released through
     * {@link SessionMessageExecutors#releaseAll(Deadline)} when it stops. Tracked here as well, so that
     * {@link #stopMe(Deadline)} can release what a session did not.
     */
    public SessionMessageExecutors newSessionExecutors() {
        SessionMessageExecutors sessionExecutors = new SessionMessageExecutors(this);
        synchronized (sessionsExecutors) {
            sessionsExecutors.add(sessionExecutors);
        }
        return sessionExecutors;
    }

    /**
     * The next executor thread a routing key should be assigned to, round-robin over the pool and shared by every
     * session, so that keys spread over the threads however they are distributed across sessions and namespaces.
     */
    synchronized ExecutorThread nextExecutorThread() {
        ExecutorThread executorThread = executorThreads.get(nextExecutorThread);
        nextExecutorThread = (nextExecutorThread + 1) % executorThreads.size();
        return executorThread;
    }

    void onSessionExecutorsReleased(SessionMessageExecutors sessionExecutors) {
        synchronized (sessionsExecutors) {
            sessionsExecutors.remove(sessionExecutors);
        }
    }

    /**
     * Tracks a session's executors again when it creates one after having released them all - an application callback
     * firing late on a session being torn down, say. Cheap by construction: only the creation path calls this, and
     * only a session that came back for more is not already here.
     */
    void onSessionExecutorCreated(SessionMessageExecutors sessionExecutors) {
        synchronized (sessionsExecutors) {
            if (!sessionsExecutors.contains(sessionExecutors)) {
                sessionsExecutors.add(sessionExecutors);
            }
        }
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        List<SessionMessageExecutors> pending;
        synchronized (sessionsExecutors) {
            pending = new ArrayList<>(sessionsExecutors);
        }
        // released rather than merely forgotten: an executor left assigned keeps its thread's assignment set non-empty,
        // and a thread that still believes it has work does not start again if this runtime does
        pending.forEach(sessionExecutors -> sessionExecutors.releaseAll(stopDeadline));
        executorThreads.forEach(t -> t.stop(stopDeadline));
    }

    @Override
    protected void startMe() throws StartStopException {
        executorThreads.forEach(SimpleStartable::start);
    }
}
