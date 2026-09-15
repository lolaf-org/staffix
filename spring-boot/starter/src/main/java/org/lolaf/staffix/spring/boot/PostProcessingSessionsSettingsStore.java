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
package org.lolaf.staffix.spring.boot;

import lombok.AllArgsConstructor;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.session.*;
import org.lolaf.staffix.spring.boot.spi.FixSessionSettingsPostProcessor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decorates a {@link FixSessionsSettingsStore} so that every {@link FixSessionSettings} read out
 * of (or added to) the underlying store is funneled through the configured
 * {@link FixSessionSettingsPostProcessor}s. Used by the starter to give Spring modules a single
 * place to amend session settings (e.g. auto-attach a monitoring plugin id) without each store
 * impl having to know about it.
 */
@AllArgsConstructor
final class PostProcessingSessionsSettingsStore implements FixSessionsSettingsStore {

    private final FixSessionsSettingsStore delegate;
    private final List<FixSessionSettingsPostProcessor> postProcessors;

    private FixSessionSettings apply(FixSessionSettings s) {
        FixSessionSettings current = s;
        for (FixSessionSettingsPostProcessor p : postProcessors) {
            current = p.postProcess(current);
        }
        return current;
    }

    @Override
    public Collection<FixSessionSettings> getSettings() {
        return delegate.getSettings().stream().map(this::apply).toList();
    }

    @Override
    public Optional<FixSessionSettings> find(FixSessionId fixSessionId, FixSession.FixSessionType fixSessionType) {
        return delegate.find(fixSessionId, fixSessionType).map(this::apply);
    }

    @Override
    public void add(FixSessionSettings settings) {
        delegate.add(apply(settings));
    }

    @Override
    public void remove(FixSessionSettings settings) {
        delegate.remove(settings);
    }

    @Override
    public void update(FixSessionSettings settings) {
        delegate.update(apply(settings));
    }

    @Override
    public Set<FixSessionSettings> load() {
        return delegate.load().stream().map(this::apply).collect(Collectors.toSet());
    }

    @Override
    public void register(Listener listener) {
        delegate.register(listener);
    }

    @Override
    public void unregister(Listener listener) {
        delegate.unregister(listener);
    }

    @Override
    public String getInstanceId() {
        return delegate.getInstanceId();
    }

    @Override
    public boolean isStarted() {
        return delegate.isStarted();
    }

    @Override
    public FixSessionsSettingsStore start() {
        delegate.start();
        return this;
    }

    @Override
    public FixSessionsSettingsStore stop(Deadline deadline) {
        delegate.stop(deadline);
        return this;
    }

    /**
     * Adapter {@link FixSessionsSettingsStoreSettings} that wraps another settings instance and,
     * when {@link #instance()} is called, decorates the resulting store with post-processors.
     */
    static final class Settings implements FixSessionsSettingsStoreSettings {

        private final FixSessionsSettingsStoreSettings delegate;
        private final List<FixSessionSettingsPostProcessor> postProcessors;

        Settings(FixSessionsSettingsStoreSettings delegate,
                 List<FixSessionSettingsPostProcessor> postProcessors) {
            this.delegate = delegate;
            this.postProcessors = postProcessors;
        }

        @Override
        public String getInstanceId() {
            return delegate.getInstanceId();
        }

        @Override
        public FixSessionsSettingsStore instance() {
            return new PostProcessingSessionsSettingsStore(delegate.instance(), postProcessors);
        }
    }
}