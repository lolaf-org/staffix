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
package org.lolaf.staffix.api;

import lombok.Getter;
import org.lolaf.ringos.Deadline;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The lifecycle contract of everything the engine owns: message stores, message loggers, session plugins,
 * admin APIs, and the initiators and acceptors themselves.
 *
 * <p>{@link #stop(Deadline)} takes a deadline rather than blocking until it is done, because a shutdown runs
 * across components that may be draining a queue or flushing a file, and none of them may hold the engine open
 * indefinitely.
 *
 * <p>Two bases are provided. {@link SimpleStartable} is the usual one: it moves its {@link State} with a
 * compare-and-set so {@code startMe} and {@code stopMe} run at most once however many threads call them.
 * {@link VoidStartable} is for a component with nothing to start or stop, which records the flag and does nothing else.
 */
public interface Startable<T> {

    /**
     * Stops, finishing by the deadline, and returns this.
     */
    T stop(Deadline stopDeadline) throws StartStopException;

    /**
     * Starts and returns this, so a component can be built and started in one expression.
     */
    T start() throws StartStopException;

    /**
     * Whether {@link #start()} has succeeded and no {@link #stop(Deadline)} has followed.
     */
    boolean isStarted();

    /**
     * Where the component is in its lifecycle. Only {@link SimpleStartable} reports the transitions; the default
     * knows only {@link State#STARTED} and {@link State#STOPPED}.
     */
    default State getState() {
        return isStarted() ? State.STARTED : State.STOPPED;
    }

    enum State {
        STOPPED,
        STARTING,
        STARTED,
        STOPPING
    }

    /**
     * A start or stop that failed. Unchecked, since a caller can rarely do more than report it.
     */
    class StartStopException extends RuntimeException {
        public StartStopException(String message) {
            super(message);
        }

        public StartStopException(Throwable cause) {
            super(cause);
        }

        public StartStopException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * For a component with nothing to start or stop: it only records the flag.
     *
     * @param <T> the component type, returned by start and stop
     */
    @Getter
    abstract class VoidStartable<T> implements Startable<T> {

        private boolean started;

        @Override
        public final T stop(Deadline stopDeadline) throws StartStopException {
            started = false;
            return (T) this;
        }

        @Override
        public final T start() throws StartStopException {
            started = true;
            return (T) this;
        }
    }

    /**
     * Runs {@link #startMe()} and {@link #stopMe(Deadline)} at most once each per start and stop, however many
     * threads call them. {@link #isStarted()} is true only once {@code startMe} returned, so another thread never finds
     * a component half started. Nothing waits: a stop during {@code startMe} runs {@code stopMe} at once, and a start
     * during {@code stopMe} runs {@code startMe}. A {@code startMe} that throws leaves the component stopped, so a
     * start can be retried.
     *
     * @param <T> the component type, returned by start and stop
     */
    abstract class SimpleStartable<T> implements Startable<T> {

        private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

        @Override
        public boolean isStarted() {
            return state.get() == State.STARTED;
        }

        @Override
        public State getState() {
            return state.get();
        }

        @Override
        public final T stop(Deadline stopDeadline) throws StartStopException {
            State current = state.get();
            while (current == State.STARTED || current == State.STARTING) {
                if (state.compareAndSet(current, State.STOPPING)) {
                    try {
                        stopMe(stopDeadline);
                    } finally {
                        state.compareAndSet(State.STOPPING, State.STOPPED);
                    }
                    break;
                }
                current = state.get();
            }
            return (T) this;
        }

        /**
         * The actual stop, called only on a started component.
         */
        protected abstract void stopMe(Deadline stopDeadline) throws StartStopException;

        @Override
        public final T start() throws StartStopException {
            State current = state.get();
            while (current == State.STOPPED || current == State.STOPPING) {
                if (state.compareAndSet(current, State.STARTING)) {
                    try {
                        startMe();
                    } catch (Exception ex) {
                        state.compareAndSet(State.STARTING, State.STOPPED);
                        throw ex;
                    }
                    state.compareAndSet(State.STARTING, State.STARTED);
                    break;
                }
                current = state.get();
            }
            return (T) this;
        }

        /**
         * The actual start, called only on a stopped component.
         */
        protected abstract void startMe() throws StartStopException;
    }


}