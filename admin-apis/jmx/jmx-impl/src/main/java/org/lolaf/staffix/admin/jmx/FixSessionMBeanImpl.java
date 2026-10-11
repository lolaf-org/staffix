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
package org.lolaf.staffix.admin.jmx;

import lombok.Value;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.alerting.Alert;
import org.lolaf.staffix.api.alerting.ProbeState;
import org.lolaf.staffix.api.session.FixSessionId;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The per-session MBean: state, sequence numbers, and the operations that change them.
 *
 * <p>Setting a sequence number by hand is how a session that has lost synchronisation is recovered, which is
 * why it is exposed at all - and why it should be used knowing what the counterparty expects.
 */
public class FixSessionMBeanImpl implements FixSessionMXBean {

    private final AdminApi delegate;
    private final FixSessionId fixSessionId;

    FixSessionMBeanImpl(AdminApi delegate, FixSessionId fixSessionId) {
        this.delegate = delegate;
        this.fixSessionId = fixSessionId;
    }

    @Override
    public String getFixSessionId() {
        return fixSessionId.toString();
    }

    @Override
    public void logon() {
        delegate.logonSession(fixSessionId);
    }

    @Override
    public void logout() {
        delegate.logoutSession(fixSessionId);
    }

    @Override
    public void reset(String resetMode) {
        AdminApi.ResetFixSessionMode resetFixSessionMode;
        try {
            resetFixSessionMode = AdminApi.ResetFixSessionMode.valueOf(resetMode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Valid reset modes are: " + Arrays.toString(AdminApi.ResetFixSessionMode.values()));
        }
        delegate.resetSession(fixSessionId, resetFixSessionMode);
    }

    @Override
    public void sendFixMessage(String fixMessage) {
        delegate.sendFixMessage(fixSessionId, fixMessage);
    }

    @Override
    public void sendFixMessage(String fixMessage, char separator, boolean possDupFlag) {
        delegate.sendFixMessage(fixSessionId, fixMessage, separator, possDupFlag);
    }

    @Override
    public long getIncomingSeqNum() {
        return delegate.getIncomingSeqNum(fixSessionId);
    }

    @Override
    public void setIncomingSeqNum(long seqNum) {
        delegate.setIncomingSeqNum(fixSessionId, seqNum);
    }

    @Override
    public long getOutgoingSeqNum() {
        return delegate.getOutgoingSeqNum(fixSessionId);
    }

    @Override
    public void setOutgoingSeqNum(long seqNum) {
        delegate.setOutgoingSeqNum(fixSessionId, seqNum);
    }

    @Override
    public List<Probe> getProbes() {
        return delegate.getProbeDefinitions(fixSessionId).stream()
                .map(probe -> new ProbeImpl(probe.getName(), probe.getDescription(), probe.getEvaluationInterval().toString(),
                        probe.getReminderInterval().map(Object::toString).orElse(null), probe.getLabels(),
                        probe.getRunbookUrl().map(Object::toString).orElse(null)))
                .collect(Collectors.toList());
    }

    @Override
    public List<ProbeStatus> getProbeStates() {
        return delegate.getProbeStates(fixSessionId).stream()
                .map(FixSessionMBeanImpl::status)
                .collect(Collectors.toList());
    }

    private static ProbeStatus status(ProbeState state) {
        Alert alert = state.getAlert().orElse(null);
        return new ProbeStatusImpl(state.getProbeName(),
                alert == null ? null : alert.getLevel().name(),
                alert == null ? null : alert.getMessage(),
                state.getSince().map(Object::toString).orElse(null),
                state.getLastEvaluation().toString());
    }

    @Value
    private static class ProbeImpl implements Probe {
        String name;
        String description;
        String evaluationInterval;
        String reminderInterval;
        Map<String, String> labels;
        String runbookUrl;
    }

    @Value
    private static class ProbeStatusImpl implements ProbeStatus {
        String probe;
        String level;
        String message;
        String since;
        String lastEvaluation;
    }
}
