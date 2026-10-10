# Alerting

An alert says that something about a session is wrong **right now**: it is down inside its trading hours, its async
store has stopped keeping up, no order has arrived for ten minutes. Staffix evaluates these conditions itself, once a
second, and tells a publisher each time one starts, changes or ends. What you do with that (a log line, a page, a
dashboard) is the publisher's job.

The runnable version of this guide is [`examples/alerting`](../examples/alerting): an application that adds its own
probe on logon, with the alerts logged through SLF4J.

---

## Probes, alerts and levels

Everything lives in `staffix-api`, in `org.lolaf.staffix.api.alerting`.

- A **probe** belongs to one session. It has a `name`, unique within the session, a `description`, and a
  `ProbeEvaluator`.
- A **`ProbeEvaluator`** returns `Optional<Alert>`: empty means all is well.
- An **`Alert`** is a level and a message describing the problem.
- The **levels** are `WARNING`, `MAJOR` and `CRITICAL`, in that order. There is no "normal" level: normal is the empty
  optional, and an alert always means something is wrong.

## Writing a probe

```java
Probe probe = Probe.builder()
        .name("order-flow")
        .description("Time since the last NewOrderSingle")
        .evaluator(orderFlowEvaluator)
        .evaluationInterval(Duration.ofSeconds(5))   // default 1s
        .reminderInterval(Duration.ofMinutes(1))     // default none
        .build();

session.getProbes().add(probe);
// later
session.getProbes().remove("order-flow");
```

**The evaluator runs on the engine's probes thread**, never on the session's, and never concurrently with itself. It
may keep state of its own (a grace period, a hysteresis), but what it watches must be read in a thread-safe way: a
`volatile` field or an atomic written by the session thread is the usual shape. Keep it cheap, because one thread
evaluates every probe of the engine.

**An evaluator that throws, or returns `null`, produces a `MAJOR` alert** on its probe naming the reason, so a broken
probe is visible rather than silent.

**Both intervals are at least one second** (`Probe.MIN_INTERVAL`), and are rounded up to whole seconds: the engine
ticks once a second and evaluates a probe on the first tick at or after it is due.

**Probes live as long as the session object.** `getProbes()` is usable from any thread, and adding a name already
present throws `IllegalArgumentException`. A session rebuilt after a settings change starts with none of the probes
an application added, so add them in `FixApplication.setup` or `onLogon`, not once at startup.

## What a publisher is told

A publisher receives an `AlertEvent`: the session, the probe's name and description, the alert, since when it is
ongoing, and one of four types.

| type | when |
|------|------|
| `RAISED` | the probe went from normal to an alert |
| `CHANGED` | the level changed |
| `REMINDED` | still ongoing, and the probe has a `reminderInterval` that elapsed |
| `CLEARED` | back to normal; the event carries the last alert |

**A message change at the same level is not published**, otherwise a probe whose message carries a count would
publish every second. Opt into `reminderInterval` if an ongoing alert must be repeated.

**Removing a probe clears its alert**, at the next tick, and so does replacing it under the same name or removing the
session from the engine. **Stopping the engine does not**: a shutdown is not the problem going away.

## Built-in probes

| probe | level | raised when |
|-------|-------|-------------|
| `session-down` | `CRITICAL` | the session has been logged out inside its session time for longer than `sessionDownAlertDelay` |
| `async-store-queue`, `async-logger-queue` | per threshold | the async store's or logger's queue is deeper than a threshold |
| `async-store-resource`, `async-logger-resource` | `MAJOR` | what the async store or logger writes to is unavailable |

`session-down` is on every session. An operator logout or being outside session time never raises it, and the delay
restarts after each logon. **`sessionDownAlertDelay` defaults to 10 seconds; zero turns the probe off.**

The async probes are added by `staffix-messages-store-async-impl` and `staffix-messages-logger-async-impl` to every
session using them. The queue probe raises the highest level whose depth is reached in
`QueueSizeThresholdsSettings.thresholds` (by default `WARNING` from 250, `MAJOR` from 1000, `CRITICAL` from 5000),
measured every `evaluationInterval` (10 seconds by default). The resource probe holds until what failed to be written
has been replayed. See the [async store README](../messages-stores/async/async-impl/README.md#queue-monitoring).

## Binding a publisher

A publisher is registered on the engine, like a store or a logger, and a session names the one it uses:

```java
FixEngineBuilder.builder()
        .fixAlertsPublisher(Slf4jAlertsPublisherSettings.builder().instanceId("alerts").build())
        // ...
        .build();

FixSessionSettings.builder()
        .fixAlertsPublisherInstanceId("alerts")   // default "default"
        // ...
        .build();
```

**With no publisher registered, alerts are evaluated but go nowhere**, whatever the session names. **Once any is
registered, a session naming an unknown id is refused**, and that includes the default id: either name a publisher
`default`, or bind every session explicitly.

One publisher serves a session. Sending alerts to several destinations is the job of a publisher that forwards to
others.

## The SLF4J publisher

`staffix-alerts-publisher-slf4j-impl` logs one line per event, at `WARN` for a warning, `ERROR` for major and critical,
and `INFO` once cleared:

```
Alert RAISED on session acceptor.acceptor-session-1, probe order-flow (Time since the last NewOrderSingle): WARNING no order for more than 4s (since 2026-10-10T11:23:14.648Z)
```

`loggerNameForFixSession` picks the logger name per session, so one session's alerts can be routed or silenced
independently, and `useMDC` (on by default) puts the session's group and name in the MDC under `fixSessionGroup` and
`fixSessionName`, the keys the SLF4J message logger uses.

From Spring Boot, add `staffix-alerts-publisher-slf4j-spring-boot` and declare an instance:

```properties
staffix.alerts-publishers-slf4j.instances.ALERTS.use-mdc=true
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-alerts-publisher-instance-id=ALERTS
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].session-down-alert-delay=30s
```

## Writing a publisher

Implement `FixAlertsPublisher`, give it a settings class implementing `FixAlertsPublisherSettings` (extending
`AbstractFixAlertsPublisherSettings` gives you the instance id), and a `FixAlertsPublisherFactory` listed in
`META-INF/services/org.lolaf.staffix.api.alerting.FixAlertsPublisherSettings$FixAlertsPublisherFactory`.
`Slf4jAlertsPublisher` is a complete one in about a hundred lines.

**`publish` is called on the probes thread, one event at a time**: a slow publisher delays the next evaluations, so
hand anything blocking (an HTTP call, a pager) to a thread of your own. An exception is logged and the event dropped.
When switching on `AlertLevel`, keep a `default` branch: a level may be added later.

For Spring Boot, implement `FixAlertsPublisherSettingsContributor` from `staffix-spring-boot-spi`, as
`staffix-alerts-publisher-slf4j-spring-boot` does.
