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
package org.lolaf.staffix.impl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.*;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.AdminApiExporter;
import org.lolaf.staffix.api.application.FixApplicationFactory;
import org.lolaf.staffix.api.application.FixApplicationFactorySettings;
import org.lolaf.staffix.api.logging.FixMessagesLogger;
import org.lolaf.staffix.api.logging.FixMessagesLoggerSettings;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.api.session.plugins.FixSessionsPlugin;
import org.lolaf.staffix.api.session.plugins.FixSessionsPluginSettings;
import org.lolaf.staffix.api.stores.FixMessagesStore;
import org.lolaf.staffix.api.stores.FixMessagesStoreSettings;
import org.lolaf.staffix.impl.session.FixSessionRegistryImpl;
import org.lolaf.staffix.impl.session.FixSessionSettingsValidator;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The engine: owns the stores, loggers, plugins and application factories a session may name, and the
 * initiators and acceptors built from it.
 *
 * <p>Shutdown order is the contract worth honouring - sessions first, then the resources they were using, so
 * nothing is torn down while a session is still draining into it.
 */
@Slf4j
public class FixEngineImpl extends Startable.SimpleStartable<FixEngine> implements FixEngine, AdminApi, FixSessionsObserver {

    private static final String PROVIDE_AT_LEAST_ONE = "Provide at least one ";

    private final List<FixMessagesStore> fixMessagesStores;
    @Getter
    private final List<FixSessionsSettingsStore> fixSessionsSettingsStores;
    private final List<FixMessagesLogger> fixMessagesLoggers;
    private final List<FixSessionsPlugin<?>> fixSessionsPlugins;
    private final List<FixApplicationFactory> fixApplicationFactories;
    @Getter
    private final FixEngineBuilder fixEngineBuilder;
    private final AdminApiExporter adminApiExporter;

    private final Map<String, FixInitiatorImpl> initiators;
    private final Map<String, FixAcceptorImpl> acceptors;
    private final Set<SessionLifecycleListener> sessionLifecycleListeners = new CopyOnWriteArraySet<>();
    /**
     * Scoped to this engine rather than the JVM, so that another engine configured with the same
     * {@link FixSessionId} - a second deployment in the same process, or a test standing up both ends - keeps its own
     * sessions instead of the two displacing each other. Maintained purely from the observer callbacks below, which
     * the controls already raise at exactly the points a session starts and stops being managed.
     */
    @Getter
    private final FixSessionRegistryImpl fixSessionRegistry = new FixSessionRegistryImpl();
    private ExecutorService selfManagedDisconnectedSessionsExecutor;

    FixEngineImpl(FixEngineBuilder fixEngineBuilder) {
        this.fixEngineBuilder = fixEngineBuilder;
        ensureUniqueInstanceIdsAreProvided(fixEngineBuilder.getFixApplicationFactories(), "FixApplicationFactories");
        this.fixApplicationFactories = fixEngineBuilder.getFixApplicationFactories().stream().map(FixApplicationFactorySettings::instance).collect(Collectors.toList());
        if (fixApplicationFactories.isEmpty()) {
            throw new IllegalStateException(PROVIDE_AT_LEAST_ONE + FixApplicationFactorySettings.class.getSimpleName());
        }
        ensureUniqueInstanceIdsAreProvided(fixEngineBuilder.getFixMessagesStores(), "FixMessagesStores");
        this.fixMessagesStores = fixEngineBuilder.getFixMessagesStores().stream().map(FixMessagesStoreSettings::instance).collect(Collectors.toList());
        if (fixMessagesStores.isEmpty()) {
            throw new IllegalStateException(PROVIDE_AT_LEAST_ONE + FixMessagesStoreSettings.class.getSimpleName());
        }
        ensureUniqueInstanceIdsAreProvided(fixEngineBuilder.getFixSessionsSettingsStores(), "FixSessionsSettingsStores");
        this.fixSessionsSettingsStores = fixEngineBuilder.getFixSessionsSettingsStores().stream().map(FixSessionsSettingsStoreSettings::instance).collect(Collectors.toList());
        if (fixSessionsSettingsStores.isEmpty()) {
            throw new IllegalStateException(PROVIDE_AT_LEAST_ONE + FixSessionsSettingsStoreSettings.class.getSimpleName());
        }
        ensurePluginsUniqueInstanceIdsAreProvided(fixEngineBuilder.getFixSessionsPlugins());
        this.fixSessionsPlugins = fixEngineBuilder.getFixSessionsPlugins().stream().map(FixSessionsPluginSettings::instance).collect(Collectors.toList());
        ensureUniqueInstanceIdsAreProvided(fixEngineBuilder.getFixMessagesLoggers(), "FixMessagesLoggers");
        this.fixMessagesLoggers = fixEngineBuilder.getFixMessagesLoggers().stream().map(FixMessagesLoggerSettings::instance).collect(Collectors.toList());
        this.adminApiExporter = fixEngineBuilder.getAdminApiExporter() != null ? fixEngineBuilder.getAdminApiExporter().instance() : null;
        this.initiators = new ConcurrentHashMap<>();
        this.acceptors = new ConcurrentHashMap<>();
    }

    private <T extends InstanceIdSupplier> void ensurePluginsUniqueInstanceIdsAreProvided(List<T> pluginsSettings) {
        Map<Class<?>, List<T>> instanceIdSuppliersPerPluginsClass = new HashMap<>();
        for (T instanceIdSupplier : pluginsSettings) {
            instanceIdSuppliersPerPluginsClass.computeIfAbsent(instanceIdSupplier.getClass(), k -> new ArrayList<>()).add(instanceIdSupplier);
        }
        instanceIdSuppliersPerPluginsClass.forEach((k, settings) -> {
            if (!listDuplicateIds(settings).isEmpty()) {
                throw new IllegalStateException("Provided plugin " + k.getName() + " have multiple instance with the same id:" + listDuplicateIds(settings));
            }
        });
    }

    private <T extends InstanceIdSupplier> void ensureUniqueInstanceIdsAreProvided(List<T> settings, String settingId) {
        if (!listDuplicateIds(settings).isEmpty()) {
            throw new IllegalStateException("Provided " + settingId + " have multiple instance with the same id:" + listDuplicateIds(settings));
        }
    }

    private <T extends InstanceIdSupplier> List<String> listDuplicateIds(List<T> list) {
        Set<String> elements = new HashSet<>();
        return list.stream()
                .map(InstanceIdSupplier::getInstanceId)
                .filter(id -> !elements.add(id))
                .collect(Collectors.toList());
    }

    @Override
    protected void stopMe(Deadline stopDeadline) throws StartStopException {
        if (adminApiExporter != null) {
            adminApiExporter.shutdown(stopDeadline);
        }
        initiators.values().forEach(initiator -> initiator.stop(stopDeadline));
        initiators.clear();
        acceptors.values().forEach(acceptor -> acceptor.stop(stopDeadline));
        acceptors.clear();
        stopSelfManagedDisconnectedSessionsExecutor(stopDeadline);

        fixSessionsSettingsStores.forEach(s -> s.stop(stopDeadline));
        fixApplicationFactories.forEach(s -> s.stop(stopDeadline));
        fixMessagesStores.forEach(s -> s.stop(stopDeadline));
        fixSessionsPlugins.forEach(s -> {
            if (s instanceof Startable) {
                ((Startable<?>) s).stop(stopDeadline);
            }
        });
        fixMessagesLoggers.forEach(s -> s.stop(stopDeadline));
    }

    /**
     * Before the stores and loggers, which its last tasks still write to. Only what this engine created: a supplied
     * one belongs to the application.
     */
    private void stopSelfManagedDisconnectedSessionsExecutor(Deadline stopDeadline) {
        if (selfManagedDisconnectedSessionsExecutor == null) {
            return;
        }
        selfManagedDisconnectedSessionsExecutor.shutdown();
        try {
            if (!selfManagedDisconnectedSessionsExecutor.awaitTermination(
                    Math.max(1L, stopDeadline.getRemainingTime().toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("Disconnected sessions executor of engine {} still busy at the stop deadline", getInstanceId());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        selfManagedDisconnectedSessionsExecutor = null;
    }

    private ExecutorService disconnectedSessionsExecutor() {
        return fixEngineBuilder.getDisconnectedSessionsExecutor() != null
                ? fixEngineBuilder.getDisconnectedSessionsExecutor() : selfManagedDisconnectedSessionsExecutor;
    }

    @Override
    protected void startMe() throws StartStopException {
        if (fixEngineBuilder.getDisconnectedSessionsExecutor() == null) {
            selfManagedDisconnectedSessionsExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "staffix-offline-sessions-" + getInstanceId());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((t, ex) -> log.error("Uncaught exception occurred in thread {}", t, ex));
                return thread;
            });
        }
        fixSessionsSettingsStores.forEach(s -> {
            s.start();
            s.getSettings().forEach(FixSessionSettingsValidator::validate);
        });
        fixApplicationFactories.forEach(Startable::start);
        fixMessagesStores.forEach(Startable::start);
        fixSessionsPlugins.forEach(s -> {
            if (s instanceof Startable) {
                ((Startable<?>) s).start();
            }
        });
        fixMessagesLoggers.forEach(Startable::start);
        if (adminApiExporter != null) {
            adminApiExporter.export(this);
        }
    }

    private FixSessionRuntimeDependencies getDependencies(FixSessionSettings fixSessionSettings) {
        return new FixSessionRuntimeDependencies(findMatchAmongstMessagesStores(fixSessionSettings),
                findMatchAmongstMessagesLoggers(fixSessionSettings),
                findMatchAmongstFixApplicationFactories(fixSessionSettings),
                findMatchAmongstPluginsComponent(fixSessionSettings),
                fixSessionRegistry, disconnectedSessionsExecutor());
    }

    @Override
    public FixInitiator newInitiator(FixInitiatorBuilder fixInitiatorBuilder) {
        if (!isStarted()) {
            throw new IllegalStateException("Start the FIX engine first");
        }
        return initiators.computeIfAbsent(fixInitiatorBuilder.getInstanceId(),
                instanceId -> new FixInitiatorImpl(fixInitiatorBuilder, this::getDependencies, fixSessionsSettingsStores, this));
    }

    @Override
    public FixAcceptor newAcceptor(FixAcceptorBuilder fixAcceptorBuilder) {
        if (!isStarted()) {
            throw new IllegalStateException("Start the FIX engine first");
        }
        return acceptors.computeIfAbsent(fixAcceptorBuilder.getInstanceId(),
                instanceId -> new FixAcceptorImpl(fixAcceptorBuilder, this::getDependencies, fixSessionsSettingsStores, this));
    }

    // AdminApi implementation - routes each operation to the initiator/acceptor managing the session

    @Override
    public String getInstanceId() {
        return fixEngineBuilder.getInstanceId();
    }

    @Override
    public void logonSession(FixSessionId fixSessionId) {
        findControl(fixSessionId).logonSession(fixSessionId);
    }

    @Override
    public void logoutSession(FixSessionId fixSessionId) {
        findControl(fixSessionId).logoutSession(fixSessionId);
    }

    @Override
    public void resetSession(FixSessionId fixSessionId, AdminApi.ResetFixSessionMode resetFixSessionMode) {
        findControl(fixSessionId).resetSession(fixSessionId, resetFixSessionMode);
    }

    @Override
    public void setIncomingSeqNum(FixSessionId fixSessionId, long incomingSeqNum) {
        findControl(fixSessionId).setIncomingSeqNum(fixSessionId, incomingSeqNum);
    }

    @Override
    public void setOutgoingSeqNum(FixSessionId fixSessionId, long outgoingSeqNum) {
        findControl(fixSessionId).setOutgoingSeqNum(fixSessionId, outgoingSeqNum);
    }

    @Override
    public void sendFixMessage(FixSessionId fixSessionId, String fixMessage, char separator, boolean possDupFlag) {
        findControl(fixSessionId).sendFixMessage(fixSessionId, fixMessage, separator, possDupFlag);
    }

    @Override
    public long getIncomingSeqNum(FixSessionId fixSessionId) {
        return findControl(fixSessionId).getIncomingSeqNum(fixSessionId);
    }

    @Override
    public long getOutgoingSeqNum(FixSessionId fixSessionId) {
        return findControl(fixSessionId).getOutgoingSeqNum(fixSessionId);
    }

    @Override
    public List<FixSessionSettings> getManagedFixSessionsSettings() {
        List<FixSessionSettings> allSettings = new ArrayList<>();
        sessionAdminControls().forEach(c -> allSettings.addAll(c.getManagedFixSessionsSettings()));
        return allSettings;
    }

    @Override
    public List<String> getFixSessionsSettingsStoresInstanceIds() {
        return fixSessionsSettingsStores.stream()
                .map(FixSessionsSettingsStore::getInstanceId)
                .collect(Collectors.toList());
    }

    @Override
    public void reloadFixSessionsSettingsStore(String instanceId) {
        FixSessionsSettingsStore store = fixSessionsSettingsStores.stream()
                .filter(s -> s.getInstanceId().equals(instanceId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(String.format(
                        "No FixSessionsSettingsStore found for instance id '%s' within: '%s'", instanceId,
                        fixSessionsSettingsStores.stream().map(FixSessionsSettingsStore::getInstanceId).collect(Collectors.joining(",")))));

        // load() reads the backing source without mutating the store; we reconcile the difference here, keyed by
        // FixSessionId, so that the store's add/remove/update fire the listener callbacks the sessions react to.
        Set<FixSessionSettings> loaded = store.load();
        loaded.forEach(FixSessionSettingsValidator::validate);
        Map<FixSessionId, FixSessionSettings> currentBySessionId = store.getSettings().stream()
                .collect(Collectors.toMap(FixSessionSettings::getFixSessionId, s -> s));
        Set<FixSessionId> loadedSessionIds = new HashSet<>();
        for (FixSessionSettings loadedSettings : loaded) {
            loadedSessionIds.add(loadedSettings.getFixSessionId());
            FixSessionSettings current = currentBySessionId.get(loadedSettings.getFixSessionId());
            if (current == null) {
                store.add(loadedSettings);
            } else if (!current.equals(loadedSettings)) {
                store.update(loadedSettings);
            }
        }
        currentBySessionId.values().stream()
                .filter(current -> !loadedSessionIds.contains(current.getFixSessionId()))
                .forEach(store::remove);
    }

    @Override
    public List<FixSession> getManagedFixSessions() {
        return sessionAdminControls()
                .flatMap(c -> c.getManagedFixSessions().stream())
                .collect(Collectors.toList());
    }

    @Override
    public void registerSessionLifecycleListener(SessionLifecycleListener listener) {
        sessionLifecycleListeners.add(listener);
    }

    @Override
    public void unregisterSessionLifecycleListener(SessionLifecycleListener listener) {
        sessionLifecycleListeners.remove(listener);
    }

    @Override
    public void onSessionRegistered(FixSession fixSession) {
        fixSessionRegistry.register(fixSession);
        sessionLifecycleListeners.forEach(l -> l.onSessionRegistered(fixSession));
    }

    @Override
    public void onSessionUnregistered(FixSession fixSession) {
        fixSessionRegistry.unregister(fixSession);
        sessionLifecycleListeners.forEach(l -> l.onSessionUnregistered(fixSession));
    }

    private Stream<FixSessionAdminControl> sessionAdminControls() {
        return Stream.concat(initiators.values().stream(), acceptors.values().stream());
    }

    private FixSessionAdminControl findControl(FixSessionId fixSessionId) {
        return sessionAdminControls()
                .filter(c -> c.getManagedFixSessionsSettings().stream()
                        .anyMatch(s -> s.getFixSessionId().equals(fixSessionId)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No initiator or acceptor manages session " + fixSessionId));
    }

    private List<FixSessionsPlugin<?>> findMatchAmongstPluginsComponent(FixSessionSettings fixSessionSettings) {
        if (fixSessionsPlugins.isEmpty()) {
            // no plugins provided
            return Collections.emptyList();
        }
        List<FixSessionsPlugin<?>> pluginsMatches = new ArrayList<>();
        Map<Class<? extends FixSessionsPlugin<?>>, String> desiredPluginsInstanceIds = new HashMap<>(fixSessionSettings.getFixSessionPluginsInstanceIds());
        Iterator<Map.Entry<Class<? extends FixSessionsPlugin<?>>, String>> i = desiredPluginsInstanceIds.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<Class<? extends FixSessionsPlugin<?>>, String> entry = i.next();
            fixSessionsPlugins.stream()
                    .filter(p -> p.matchesPluginClass(entry.getKey()) && p.getInstanceId().equals(entry.getValue()))
                    .findFirst().ifPresent(p -> {
                        pluginsMatches.add(p);
                        i.remove();
                    });
        }

        if (!desiredPluginsInstanceIds.isEmpty()) {
            Map<String, String> missMatches = desiredPluginsInstanceIds.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getValue, e -> e.getKey().getName()));
            Map<String, String> providedPlugins = fixSessionsPlugins.stream()
                    .collect(Collectors.toMap(InstanceIdSupplier::getInstanceId, e -> e.getClass().getName()));
            throw new IllegalArgumentException(String.format("Unable to match all FIX sessions plugins:%nunmatched plugins:%n%s%nprovided plugins:%n%s",
                    missMatches, providedPlugins));
        }
        return pluginsMatches;
    }

    private FixApplicationFactory findMatchAmongstFixApplicationFactories(FixSessionSettings fixSessionSettings) {
        for (FixApplicationFactory faf : fixApplicationFactories) {
            if (faf.getInstanceId().equals(fixSessionSettings.getFixApplicationFactoryInstanceId())) {
                return faf;
            }
        }
        throw new IllegalArgumentException(String.format("Unable to find any FIX application factory for id %s within: %s", fixSessionSettings.getFixApplicationFactoryInstanceId(),
                fixApplicationFactories.stream().map(FixApplicationFactory::getInstanceId).collect(Collectors.joining(","))));
    }

    private FixMessagesStore findMatchAmongstMessagesStores(FixSessionSettings fixSessionSettings) {
        for (FixMessagesStore fms : fixMessagesStores) {
            if (fms.getInstanceId().equals(fixSessionSettings.getFixMessageStoreInstanceId())) {
                return fms;
            }
        }
        throw new IllegalArgumentException(String.format("Unable to find any FIX message store for id %s within: %s", fixSessionSettings.getFixMessageStoreInstanceId(),
                fixSessionsSettingsStores.stream().map(FixSessionsSettingsStore::getInstanceId).collect(Collectors.joining(","))));
    }

    private FixMessagesLogger findMatchAmongstMessagesLoggers(FixSessionSettings fixSessionSettings) {
        if (fixMessagesLoggers.isEmpty()) {
            // no messages logging configured
            return null;
        }
        for (FixMessagesLogger fml : fixMessagesLoggers) {
            if (fml.getInstanceId().equals(fixSessionSettings.getFixMessageLoggerInstanceId())) {
                return fml;
            }
        }
        throw new IllegalArgumentException(String.format("Unable to find any FIX message logger for id '%s' within: '%s'", fixSessionSettings.getFixMessageLoggerInstanceId(),
                fixMessagesLoggers.stream().map(FixMessagesLogger::getInstanceId).collect(Collectors.joining(","))));
    }

    public static class FixEngineFactoryImpl implements FixEngineFactory {

        @Override
        public FixEngine newInstance(FixEngineBuilder settings) {
            return new FixEngineImpl(settings);
        }

        @Override
        public Class<FixEngineBuilder> getSettingsClass() {
            return FixEngineBuilder.class;
        }
    }
}