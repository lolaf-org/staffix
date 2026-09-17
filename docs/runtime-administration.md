# Runtime administration

Sessions need operating: log one on, reset a sequence number after a counterparty's overnight, reload settings
without a restart. Staffix separates **what those operations are** from **how they reach the engine**, so you are not
forced onto a management protocol you do not use.

---

## The two layers

**`AdminApi`** is the contract: transport-independent, and the whole administrative surface:

| operation | |
|-----------|---|
| `logonSession(FixSessionId)` / `logoutSession(FixSessionId)` | bring a session up or take it down |
| `resetSession(FixSessionId, ResetFixSessionMode)` | reset sequence state |
| `setIncomingSeqNum` / `setOutgoingSeqNum` | set either side's sequence number |
| `getIncomingSeqNum` / `getOutgoingSeqNum` | read them |
| `getManagedFixSessions()` | the live sessions |
| `getManagedFixSessionsSettings()` | their settings |
| `getFixSessionsSettingsStoresInstanceIds()` | which settings stores exist |
| `reloadFixSessionsSettingsStore(String)` | re-read one, picking up changes without a restart |
| `registerSessionLifecycleListener` / `unregister…` | be told as sessions come and go |

**`AdminApiExporter`** is the SPI that publishes that contract over a transport. `JmxAdminApi` is one implementation
of it, and nothing more privileged than that.

---

## JMX, the shipped exporter

```java
FixEngineBuilder.builder()
        .adminApiExporter(JmxAdminApiSettings.builder()
                .instanceId("my-fix-jmx-admin")
                .jmxDomain("com.example.trading")
                .build())
        // …
```

| setting | default |
|---------|---------|
| `jmxDomain` | `org.lolaf.staffix` |
| `mBeanServer` | the platform MBean server |

You get an MBean per session exposing `logon()`, `logout()`, `reset(String resetMode)`, `getIncomingSeqNum()`,
`setIncomingSeqNum(long)`, `getOutgoingSeqNum()`, `setOutgoingSeqNum(long)` and `getFixSessionId()`, plus an
engine-level MBean. Any JMX client (JConsole, VisualVM, your monitoring agent) can drive them.

---

## Writing your own exporter

This is the point of the split. If JMX is not your operational protocol, implement `AdminApiExporter`, register it
with a settings object the same way, and you keep the entire administrative surface over REST, gRPC, a control topic
or your firm's own management bus, without touching the engine or reimplementing session control.

```java
public class RestAdminApi implements AdminApiExporter {
    // handed the AdminApi; expose its operations however you like
}
```

The exporter is chosen by settings like every other pluggable part, so swapping transports is a configuration change
rather than a fork. Look at
[`JmxAdminApi`](../admin-apis/jmx/jmx-impl/src/main/java/org/lolaf/staffix/admin/jmx/JmxAdminApi.java) as the
worked example: it is a thin adapter, and yours should be too.

---

## Spring Boot

The starter wires the JMX exporter from properties:

```properties
staffix.admin-api-jmx.domain=com.example.trading
```

and the actuator module adds a `fix-sessions` endpoint alongside it:

```properties
management.endpoints.web.exposure.include=fix-sessions,health,info
staffix.actuator.enabled=true
staffix.actuator.fix-session-state-contributes-to-heath-status=true
```

That last line is the one worth thinking about: it makes a session being down turn the application's health
endpoint red, which is usually what you want a load balancer or an orchestrator to see, and occasionally exactly
what you do not, if a session is scheduled to be down outside trading hours.

---

## Reloading settings

`reloadFixSessionsSettingsStore(instanceId)` re-reads one settings store. With the file-backed store this is how a
YAML change reaches a running engine: edit the file, call reload, and the engine picks up the new settings without a
restart.

The engine reconciles what it read against what it manages, keyed by `FixSessionId`, and applies the difference
through the store's `add`, `remove` and `update`, which is what initiators and acceptors react to.

**Reloading is not a read-only operation.** By default a session whose settings changed is restarted so the new
values take effect, and a session whose settings disappeared is disconnected. On a running engine that means a reload
can drop sessions, which is rarely what you want in the middle of a trading day.

Each session decides its own exposure, through two settings covered in
[Configuring a session](configuring-sessions.md#when-settings-change-under-a-running-session):

| setting | default | on reload |
|---------|---------|-----------|
| `disconnectOnRemove` | `true` | settings removed → session disconnected |
| `restartLiveSessionOnUpdate` | `true` | settings changed → live session restarted |

Set both to `false` on the sessions that must not be disturbed, and a reload becomes a configuration change that
takes effect at their next connection instead.

The same applies to calling `add`, `remove` or `update` on a store directly; reload is only one of their callers,
and the flags govern every path.
