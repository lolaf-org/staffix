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

import lombok.extern.slf4j.Slf4j;
import org.lolaf.ringos.Deadline;
import org.lolaf.staffix.api.admin.AdminApi;
import org.lolaf.staffix.api.admin.AdminApiExporter;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.api.session.FixSessionId;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers the engine and its sessions as MBeans, so both can be read and driven from any JMX console.
 */
@Slf4j
public class JmxAdminApi implements AdminApiExporter, AdminApi.SessionLifecycleListener {

    private final JmxAdminApiSettings settings;
    private final MBeanServer mBeanServer;
    private final Map<FixSessionId, ObjectName> sessionMBeans = new LinkedHashMap<>();
    private AdminApi adminApi;
    private ObjectName adminMBean;

    JmxAdminApi(JmxAdminApiSettings settings) {
        this.settings = settings;
        this.mBeanServer = settings.getMBeanServer() != null
                ? settings.getMBeanServer()
                : ManagementFactory.getPlatformMBeanServer();
    }

    @Override
    public synchronized void export(AdminApi adminApi) {
        this.adminApi = adminApi;
        String instanceId = adminApi.getInstanceId();
        try {
            adminMBean = new ObjectName(settings.getJmxDomain() + ":type=FixAdmin,instance=" + ObjectName.quote(instanceId));
            mBeanServer.registerMBean(new FixAdminMBeanImpl(adminApi), adminMBean);
            log.info("Registered JMX MBean: {}", adminMBean);
        } catch (Exception e) {
            log.error("Failed to register JMX MBean for instance {}", instanceId, e);
        }
        // Listen first, then snapshot: sessions are created after the engine starts and can be added/removed at
        // runtime, so registering the listener before reading the snapshot avoids missing any in between.
        adminApi.registerSessionLifecycleListener(this);
        adminApi.getManagedFixSessions().forEach(this::onSessionRegistered);
    }

    @Override
    public synchronized void onSessionRegistered(FixSession session) {
        FixSessionId fixSessionId = session.getFixSessionId();
        if (sessionMBeans.containsKey(fixSessionId)) {
            return;
        }
        try {
            ObjectName name = sessionObjectName(session);
            mBeanServer.registerMBean(new FixSessionMBeanImpl(adminApi, fixSessionId), name);
            sessionMBeans.put(fixSessionId, name);
            log.info("Registered JMX MBean: {}", name);
        } catch (Exception e) {
            log.error("Failed to register JMX session MBean for session {}", fixSessionId, e);
        }
    }

    @Override
    public synchronized void onSessionUnregistered(FixSession session) {
        ObjectName name = sessionMBeans.remove(session.getFixSessionId());
        if (name != null) {
            unregister(name);
        }
    }

    private ObjectName sessionObjectName(FixSession session) throws Exception {
        return new ObjectName(settings.getJmxDomain() + ":type=FixSession"
                + ",instance=" + ObjectName.quote(adminApi.getInstanceId())
                + ",session=" + ObjectName.quote(session.getFixSessionId().getId())
                + ",role=" + (session.getFixSessionSettings().getFixSessionType().equals(FixSession.FixSessionType.INITIATOR) ? "Initiator" : "Acceptor"));
    }

    @Override
    public synchronized void shutdown(Deadline deadline) {
        if (adminApi != null) {
            adminApi.unregisterSessionLifecycleListener(this);
        }
        sessionMBeans.values().forEach(this::unregister);
        sessionMBeans.clear();
        if (adminMBean != null) {
            unregister(adminMBean);
            adminMBean = null;
        }
        adminApi = null;
    }

    private void unregister(ObjectName name) {
        try {
            mBeanServer.unregisterMBean(name);
            log.info("Unregistered JMX MBean: {}", name);
        } catch (Exception e) {
            log.error("Failed to unregister JMX MBean {}", name, e);
        }
    }

    public static class JmxAdminApiFactoryImpl implements AdminApiExporter.AdminApiExporterFactory<JmxAdminApiSettings> {

        @Override
        public Class<JmxAdminApiSettings> getSettingsClass() {
            return JmxAdminApiSettings.class;
        }

        @Override
        public AdminApiExporter newInstance(JmxAdminApiSettings settings) {
            return new JmxAdminApi(settings);
        }
    }
}
