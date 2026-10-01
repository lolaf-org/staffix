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
| `getAcceptorsSessions()` | each acceptor's sessions |
| `registerSessionLifecycleListener` / `unregister…` | be told as sessions come and go |

**`AdminApiExporter`** is the SPI that publishes that contract over a transport. `JmxAdminApi` and `HttpAdminApi`
are two implementations of it, and nothing more privileged than that.

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

The engine-level MBean names a session by its full `toString()` form, `alpha.trading:VERSION_44:SENDER->TARGET`, in
both `getInitiatorsTargets()` and `switchInitiatorSession(String)`: a session name is unique only within its group.
Copy the string from the first into the second.

---

## HTTP, for the staffix admin console

`staffix-admin-api-http-impl` serves the admin API as JSON over HTTP, with the JDK's own HTTP server, and announces
the engine to a [staffix admin console](https://github.com/lolaf-org/staffix-admin), which then shows its sessions and
drives them. Nothing has to be configured on the console side: the engine tells it where it is and which token to use.

```java
FixEngineBuilder.builder()
        .adminApiExporter(HttpAdminApiSettings.builder()
                .port(8686)
                .announceUrl("https://staffix-admin.example.com")
                .announceUsername("engine")
                .announcePassword(System.getenv("STAFFIX_ADMIN_ENGINE_PASSWORD"))
                .build())
        // …
```

| setting | default | |
|---------|---------|---|
| `bindAddress` | `0.0.0.0` | |
| `port` | `8686` | 0 picks a free port |
| `sslContext` | none | serves HTTPS when set |
| `apiToken` | random | the bearer token every request must carry; random means only the console it is announced to knows it |
| `advertisedUrl` | derived | where the console reaches the engine; set it behind a proxy or a NAT |
| `announceUrl` | none | the console's URL; none serves the API without announcing the engine |
| `announceUsername` / `announcePassword` | none | a console user with the ENGINE role, used only to announce; they grant nothing on this API |
| `announceInterval` | 30 s | the engine announces itself again on every interval, so a restarted console finds it |
| `announceScheduler` | its own thread | runs the announcements; one you pass is left running at shutdown |

Each engine is served under `/engines/{instanceId}/`, so several engines of one JVM can share a port, each with its
own token:

| request | |
|---------|---|
| `GET status` | every session with its state and sequence numbers, and for an initiator its configs and the selected one |
| `GET sessions/{group}/{name}/settings` | the session's settings, values that look secret masked |
| `POST sessions/{group}/{name}/logon`, `logout` | |
| `POST sessions/{group}/{name}/reset` | `{"mode": "RESET_SEQUENCE"}` |
| `PUT sessions/{group}/{name}/seqnums` | `{"incoming": 1, "outgoing": 1}`, either may be left out |
| `POST sessions/{group}/{name}/messages` | `{"message": "35=B|148=hello|", "separator": "|", "possDup": false}` |
| `POST sessions/{group}/{name}/activate` | switch the initiator to this config |
| `GET settings-stores`, `POST settings-stores/{id}/reload` | |
| `GET dictionaries/{id}` | a dictionary a session lists in `status`, with its SHA-256 as `ETag` |

An error answers with its status and an `application/problem+json` body, `{"status": 409, "detail": "..."}`.
A failed announcement is logged and retried; it never stops the engine or the API.

Serve it over HTTPS outside a test setup: the token travels in every request, and the API can send messages on your
sessions.

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

The starter wires the JMX and HTTP exporters from properties, and the actuator module adds a `fix-sessions` endpoint; the
properties are in [Spring Boot](spring-boot.md#monitoring-and-admin).

`staffix.actuator.fix-session-state-contributes-to-health-status=true` turns the health endpoint DOWN when a session
that should be logged in is not: it is inside its schedule, nobody logged it out on purpose, and it is not logged in.
A session outside its trading hours, or logged out through the admin API, stays UP. The health details show each
session's `state`, `desiredState` and `withinSessionTime`, so a DOWN can be traced to its session. It is off by
default: a load balancer should usually see a counterparty outage, but a liveness probe should not restart the
application over it, so feed it to a readiness check, not a liveness one.

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
