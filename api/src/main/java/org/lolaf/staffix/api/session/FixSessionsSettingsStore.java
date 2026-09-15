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
package org.lolaf.staffix.api.session;

import org.lolaf.staffix.api.InstanceIdSupplier;
import org.lolaf.staffix.api.Startable;

import java.util.*;

/**
 * Holds the {@link FixSessionSettings} the engine manages and acts as the bridge between an external configuration
 * source (a directory of files, a database, plain in-memory configuration, ...) and the running engine.
 * <p>
 * A store keeps an in-memory <em>managed</em> collection (exposed through {@link #getSettings()} and {@link #find}) that
 * mirrors the sessions the engine currently knows about. Mutating that collection through {@link #add},
 * {@link #remove} and {@link #update} both updates the backing source (where applicable) and notifies registered
 * {@link Listener}s so that initiators and acceptors can create, tear down or refresh the corresponding sessions.
 * <p>
 * {@link #load()} is deliberately kept separate from those mutators: it only <em>reads</em> the backing source and
 * returns what it finds, leaving reconciliation against the managed collection to the caller (the engine), which
 * computes the difference keyed by {@link FixSessionId} and applies it through {@link #add}/{@link #remove}/
 * {@link #update}.
 * <p>
 * Each store is identified by its {@link #getInstanceId() instance id} and follows the {@link Startable} lifecycle;
 * implementations are typically started by the engine before their settings are consumed.
 */
public interface FixSessionsSettingsStore extends InstanceIdSupplier, Startable<FixSessionsSettingsStore> {

    /**
     * Returns the settings the store currently manages in memory.
     *
     * @return the managed settings; never {@code null}, possibly empty
     */
    Collection<FixSessionSettings> getSettings();

    /**
     * Looks up a managed {@link FixSessionSettings} by its {@link FixSessionId} and {@link FixSession.FixSessionType}.
     * Both are required to match because the same {@link FixSessionId} may be configured for both an initiator and an
     * acceptor session.
     *
     * @param fixSessionId   the id to look up
     * @param fixSessionType whether to look up the initiator or the acceptor session for that id
     * @return the matching settings, or {@link Optional#empty()} if none is managed for that id and type
     */
    Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType);

    /**
     * Adds new settings to the managed collection, persisting them to the backing source where applicable, and
     * notifies registered {@link Listener}s via {@link Listener#onAddedSession(FixSessionSettings)}.
     *
     * @param settings the settings to add
     */
    void add(FixSessionSettings settings);

    /**
     * Removes settings from the managed collection, deleting them from the backing source where applicable, and
     * notifies registered {@link Listener}s via {@link Listener#onRemovedSession(FixSessionSettings)}.
     *
     * @param settings the settings to remove
     */
    void remove(FixSessionSettings settings);

    /**
     * Replaces the managed settings sharing the same {@link FixSessionId} as the given value, persisting the change to
     * the backing source where applicable, and notifies registered {@link Listener}s via
     * {@link Listener#onUpdatedSession(FixSessionSettings, FixSessionSettings)}.
     *
     * @param settings the new value, whose {@link FixSessionId} identifies the managed entry to replace
     */
    void update(FixSessionSettings settings);

    /**
     * Reads and parses the backing source (e.g. disk, database, or in-memory configuration) and returns the
     * {@link FixSessionSettings} discovered there.
     * <p>
     * Implementations <strong>must not</strong> mutate the in-memory managed collection returned by
     * {@link #getSettings()}, and <strong>must not</strong> fire any {@link Listener} callbacks. Reconciling the
     * returned set against the currently managed settings (adding, removing or updating entries) is the
     * responsibility of the caller, which applies the differences through {@link #add(FixSessionSettings)},
     * {@link #remove(FixSessionSettings)} and {@link #update(FixSessionSettings)} so that listeners are notified.
     *
     * @return the settings read from the backing source; never {@code null}
     */
    Set<FixSessionSettings> load();

    /**
     * Registers a listener to be notified of changes to the managed settings.
     *
     * @param listener the listener to register
     */
    void register(Listener listener);

    /**
     * Unregisters a previously {@link #register(Listener) registered} listener.
     *
     * @param listener the listener to remove
     */
    void unregister(Listener listener);

    /**
     * Callback contract notified whenever the managed settings of a {@link FixSessionsSettingsStore} change, so that
     * the engine can keep its initiators and acceptors in sync with the configured sessions.
     */
    interface Listener {
        /**
         * Invoked when settings for a new {@link FixSessionId} are added to the store.
         *
         * @param settings the newly added settings
         */
        void onAddedSession(FixSessionSettings settings);

        /**
         * Invoked when settings are removed from the store.
         *
         * @param settings the removed settings
         */
        void onRemovedSession(FixSessionSettings settings);

        /**
         * Invoked when a reload yields a {@link FixSessionSettings} whose {@link FixSessionId} matches an already
         * managed entry but whose value differs from it.
         *
         * @param oldSettings the previously managed settings
         * @param newSettings the reloaded settings replacing it (same {@link FixSessionId}, different value)
         */
        void onUpdatedSession(FixSessionSettings oldSettings, FixSessionSettings newSettings);
    }

    /**
     * Base class for {@link FixSessionsSettingsStore} implementations that centralizes listener bookkeeping and the
     * notification ordering.
     * <p>
     * It implements {@link #add}, {@link #remove} and {@link #update} as a backing-source mutation (delegated to the
     * {@link #onAdd}, {@link #onRemove} and {@link #onUpdate} hooks subclasses provide) followed by the matching
     * {@link Listener} notification. Subclasses therefore only have to manage their storage and need not deal with
     * listeners.
     */
    abstract class AbstractFixSessionSettingsStore extends Startable.SimpleStartable<FixSessionsSettingsStore> implements FixSessionsSettingsStore {

        private final Set<Listener> listeners = new HashSet<>();

        /**
         * @return the live set of registered listeners, for subclasses that need to notify them directly
         */
        protected Set<Listener> getListeners() {
            return listeners;
        }

        /**
         * Adds the given settings to the subclass storage. Invoked by {@link #add(FixSessionSettings)} before
         * listeners are notified.
         *
         * @param settings the settings to add
         */
        public abstract void onAdd(FixSessionSettings settings);

        @Override
        public void add(FixSessionSettings settings) {
            onAdd(settings);
            for (Listener l : List.copyOf(listeners)) {
                l.onAddedSession(settings);
            }
        }

        /**
         * Removes the given settings from the subclass storage. Invoked by {@link #remove(FixSessionSettings)} before
         * listeners are notified.
         *
         * @param settings the settings to remove
         */
        public abstract void onRemove(FixSessionSettings settings);

        @Override
        public void remove(FixSessionSettings settings) {
            onRemove(settings);
            for (Listener l : List.copyOf(listeners)) {
                l.onRemovedSession(settings);
            }
        }

        /**
         * Replaces, in the subclass storage, the entry sharing the given settings' {@link FixSessionId}. Invoked by
         * {@link #update(FixSessionSettings)} before listeners are notified.
         *
         * @param settings the new value, whose {@link FixSessionId} identifies the entry to replace
         */
        public abstract void onUpdate(FixSessionSettings settings);

        @Override
        public void update(FixSessionSettings settings) {
            FixSessionSettings oldSettings = find(settings.getFixSessionId(), settings.getFixSessionType())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cannot update unknown FIX session settings " + settings.getFixSessionId()));
            onUpdate(settings);
            for (Listener l : List.copyOf(listeners)) {
                l.onUpdatedSession(oldSettings, settings);
            }
        }

        @Override
        public void register(Listener listener) {
            listeners.add(listener);
        }

        @Override
        public void unregister(Listener listener) {
            listeners.remove(listener);
        }
    }
}