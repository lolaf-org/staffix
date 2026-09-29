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
| `switchInitiatorSession(FixSessionId)` | move an initiator to another of its [targets](#switching-an-initiator-to-a-backup) |
| `getInitiatorsTargets()` | each initiator's sessions, and the one it runs |
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

The engine-level MBean names a session by its full `toString()` form, `test:FIX.4.4:SENDER->TARGET`, in both
`getInitiatorsTargets()` and `switchInitiatorSession(String)`: the short id is a label and several sessions may share
it. Copy the string from the first into the second.

---

## Writing your own exporter

If JMX is not your operational protocol, implement `AdminApiExporter` and register it with a settings object like
any other part: the whole administrative surface is then available over REST, gRPC or your own management bus.

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

The starter wires the JMX exporter from properties, and the actuator module adds a `fix-sessions` endpoint; the
properties are in [Spring Boot](spring-boot.md#monitoring-and-admin). Think before setting
`staffix.actuator.fix-session-state-contributes-to-heath-status=true`: a session being down then turns the health
endpoint red, which a load balancer should usually see, but not for a session scheduled to be down outside trading
hours.

---

## Switching an initiator to a backup

A counterparty's backup site does not always take the same session: it may use another session id, another host, or
both. Every session an initiator can run is a `FixInitiatorTarget`, a session id with its own addresses: the main
target it starts on, and backup targets it can be switched to:

```java
FixInitiatorBuilder.builder()
        .mainTarget(FixInitiatorTarget.builder()
                .fixSessionId(mainSessionId)
                .connectAddress(new InetSocketAddress("fix.broker.com", 9876))
                .build())
        .backupTarget(FixInitiatorTarget.builder()
                .fixSessionId(backupSessionId)
                .connectAddress(new InetSocketAddress("dr.broker.com", 9876))
                .build())
        .build();
```

The same session on another host is not a backup target: it is one more `connectAddress` of its target, and the
addresses are tried in turn. Every target needs its own initiator settings in a settings store; `newInitiator` fails if
one is missing, if a session id appears twice, or if a backup target is already used by another initiator or an
acceptor.

The initiator **never switches on its own**: moving to a backup usually comes with a decision about sequence numbers
that only the operator can make. Switch with `FixInitiator.switchTo`, `AdminApi.switchInitiatorSession` or the JMX
engine-level MBean. A switch:

- logs the current session out and waits for the counterparty's answer, up to the initiator's `shutdownMaxDelay`,
  before dialling the backup;
- replaces the session: `FixInitiator.getSession()` returns another object, and the per-session MBean follows;
- keeps each session's own sequence numbers, so switching back resumes where the main target stopped;
- on a stopped initiator, only picks the session the next `start()` runs. A restarted initiator starts on its main
  target;
- if the new session fails to start, stops the initiator and rethrows the failure. That session stays picked, so
  once the cause is fixed, `start()` retries it.

The other admin operations address the session an initiator runs now. Settings changes for a backup are held until
it is switched to, and a backup whose settings were removed cannot be switched to until they are added back.

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
