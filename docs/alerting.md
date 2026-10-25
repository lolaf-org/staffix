# Alerting

An alert says that something about a session is wrong **right now**: it is down inside its trading hours, its async
store has stopped keeping up, no order has arrived for ten minutes. Staffix evaluates these conditions itself, once a
second, and tells a publisher each time one starts, changes or ends. What you do with that (a log line, a page, a
dashboard) is the publisher's job.

The runnable version of this guide is [`examples/alerting`](../examples/alerting): an application that adds its own
probes once per session (its own evaluator, an alerting counter and timer, and alerts raised from code), with the alerts
logged through SLF4J.

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
        .label("team", "fix_ops")                    // default none
        .runbookUrl(URI.create("https://wiki.example.com/runbooks/order-flow"))
        .onlyWhileActive(true)                     // default true
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

**To alert only on a condition that lasts**, wrap the evaluator with `withRaiseDelay`: its alert is raised only once
reported without a break for the delay, so a condition that comes and goes faster never alerts.

```java
ProbeEvaluator gatewayDown = () -> gateway.isConnected() ? Optional.empty() : GATEWAY_DOWN;
Probe.builder().name("gateway").description("Order gateway connection").evaluator(gatewayDown.withRaiseDelay(Duration.ofSeconds(30)))...
```

Only the first raise waits: a level change while already in alert, and the clear, are reported at once. The alert's
start time is when it was raised, after the delay, so say the delay in its message.

**To stop a flapping condition from raising again and again**, wrap the evaluator with `withClearDelay`: its alert is
cleared only once nothing has been reported for the delay, and the last alert stays meanwhile. A condition back within
the delay keeps the same alert and start time, so no new alert reaches the publishers. A level change is reported at
once, and calling its `clear()` skips the delay. Both delays combine, in either order:
`gatewayDown.withRaiseDelay(Duration.ofSeconds(30)).withClearDelay(Duration.ofMinutes(1))`.

**Labels and a runbook URL travel with every alert of the probe.** Labels are for routing and grouping, such as the
owning team; their names follow Prometheus' label syntax (`[a-zA-Z_][a-zA-Z0-9_]*`) and cannot be one of the names
that already identify an alert: `alertname`, `source` and `severity` (`AlertLabel`), and the session's
`fixSessionGroup` and `fixSessionName`. The runbook URL points an operator at what to do. Both are optional, and both are shown by the admin
API and passed on by the Alertmanager publisher.

**Both intervals are at least one second** (`Probe.MIN_INTERVAL`), and are rounded up to whole seconds: the engine
ticks once a second and evaluates a probe on the first tick at or after it is due.

**Probes live as long as the session object.** `getProbes()` is usable from any thread, and adding a name already
present throws `IllegalArgumentException`. A session rebuilt after a settings change starts with none of the probes
an application added, so add them in `FixApplication.onSessionCreated`, not once at startup. `setup` is called
earlier, before the session's plugins are ready, so an alerting meter could not reach its monitoring there.

### Only while the session is logged in

**A probe is evaluated only while its session is logged in**, by default (`onlyWhileActive`: a probe's scope is
active, and a session is active while logged in). An application probe usually watches business traffic, which stops
at logout: without this, a probe on the order flow would alert all night. While the session is logged out, whether
by an operator, outside its session time or inside it, the probe is suspended:

- **at logout, an ongoing alert is cleared**, published as any other clear;
- **nothing is evaluated** until the next logon, and an operator has nothing to clear;
- **at logon, the evaluator's `reset()` is called**, then it is evaluated at once. Override `reset()` to forget what
  was measured before, such as the time of the previous session's last order, or the probe alerts the moment the
  session is back. The evaluators and alerting meters of this guide already do.

A logout inside session time suspends the probe too: the built-in `session-down` probe raises the one alert that
matters then, and a probe on the order flow would only add a consequence of it.

**For a probe whose subject outlives the logon**, such as a disk, a queue still draining, or a connection of your own,
build it with `.onlyWhileActive(false)`: it is then evaluated whatever the session's state, as every built-in probe
is. The admin API shows both: `onlyWhileActive` on the probe, and `suspended` on its state while it waits for a
logon, its last evaluation then dating from before the logout.

### Raising an alert from your own code

For a problem you learn about as it happens, such as a caught exception, use a `RaisedAlertEvaluator` rather than
writing an evaluator over a field:

```java
RaisedAlertEvaluator gateway = new RaisedAlertEvaluator();
session.getProbes().add(Probe.builder().name("gateway").description("Order gateway errors").evaluator(gateway).build());

// in the catch block, on any thread
gateway.raise(Alert.major("gateway refused: " + reason), Duration.ofMinutes(5));
// once you know it is fixed
gateway.clear();
```

`raise` and `clear` are cheap enough for the session thread. Raising again replaces the alert, even at a lower
level, and restarts its expiry while the alert keeps its original start time. Without an expiry, the alert lasts
until `clear()`. A raise is always reported, even if it is cleared or expires before the next evaluation.

When one occurrence is noise and only a burst is a problem, count the occurrences with a `RateAlertEvaluator`
instead. It raises the highest level whose count is reached over a sliding window, and clears once the burst has
aged out of it:

```java
RateAlertEvaluator rejects = RateAlertEvaluator.builder()
        .window(Duration.ofMinutes(1))
        .threshold(AlertLevel.WARNING, 5)
        .threshold(AlertLevel.MAJOR, 50)
        .build();

// on any thread, each time an order is rejected
rejects.record();
```

The window is counted in about 60 buckets of at least a second, so a count is precise to one bucket; `record()` is
cheap enough for the session thread. `clear()` forgets the events counted so far.

### Clearing an alert by hand

An operator can clear a probe's alert from the admin API (`AdminApi.clearAlert`, HTTP or JMX). The engine asks the
probe's evaluator, so only an evaluator that can forget its alert accepts: a `RaisedAlertEvaluator`, a
`RateAlertEvaluator`, an `AlertingCounter` or `AlertingTimer`, and a `withClearDelay` evaluator whose condition is already back to normal. A polled condition
refuses, since it would only raise the alert again. Once accepted, the alert clears at the next tick, whatever the
probe's evaluation interval, and every publisher is told as for any other clear. To accept it in your own evaluator,
override `clear()`.

## Alerting on a metric

When what a probe watches is also a metric, record it once: an **alerting meter** is a counter, timer or gauge of the
session's [monitoring](monitoring.md) that is also its own probe's evaluator. `watch` adds the probe and returns the
meter, which the application then records on as usual:

```java
FixSessionsMonitoringContext monitoring = session.getPluginContext(FixSessionsMonitoringContext.class)
        .orElse(VoidFixSessionsMonitoringContext.getInstance());

Counter orders = session.getProbes().watch("order-rate", "NewOrderSingle over the last 5 seconds",
        AlertingCounter.builder()
                .counter(monitoring.getCounter("orders.received", "NewOrderSingle received", Map.of()))
                .window(Duration.ofSeconds(5))
                .threshold(AlertLevel.WARNING, 8)
                .build());

// on the session thread, for each order
orders.increment();
```

Without monitoring the meter publishes nothing and still alerts. `watch` gives the probe `Probe`'s defaults; for labels,
a runbook or other intervals, build the `Probe` with the meter as its evaluator and `add` it.

| meter | raises a level when | options |
|-------|---------------------|---------|
| `AlertingCounter` | its count over the window reaches the level's threshold, as a `RateAlertEvaluator` | `window`, `threshold` |
| `AlertingTimer` | its percentile over the window is above the level's latency | `window`, `threshold`, `percentile` (0.95), `minRecordings` (20) |
| `AlertingGauge` | its value reaches the level's threshold going up, or going down | `above`, `below`, `initialValue` (0), `valueLabel` |

**A timer alerts on a percentile, not on its slowest call**, so a short spike does not page anyone. It counts how many
recordings were slower than each threshold: p95 is above 10ms exactly when more than 5% of the recordings were
slower, so the percentile is exact with no histogram. Below `minRecordings` in the window it raises nothing, so one
slow call out of one is not 100%.

**A gauge can watch both edges of a band**, `below` and `above` on the same gauge, the highest level reached winning.
A threshold is the first value that alerts, in either direction.

All three take `raiseDelay` and `clearDelay`, the same as `withRaiseDelay` and `withClearDelay` above: a gauge above
its threshold for a minute is `.above(AlertLevel.WARNING, 500).raiseDelay(Duration.ofMinutes(1))`.

For a value the application already holds, such as a gauge registered with a supplier, use a
`ThresholdAlertEvaluator` on that supplier: it alerts as an `AlertingGauge`, without publishing anything.

## The engine's own components

Besides its sessions, the engine watches some of its own components, such as its high availability. Their probes
belong to the component rather than to a session: their alerts carry a `component` label (`ha`) instead of
`fixSessionGroup` and `fixSessionName`, go to the publisher the component's settings name, and are listed by the admin
API under the component's name. A component's probe is suspended while the component is inactive, as a session's is
while logged out.

## What a publisher is told

A publisher receives an `AlertEvent`: its source, the engine's instance id (a probe is only unique within an engine),
the probe's id (its scope, such as the session, and its name), the probe's description, the alert, since when it is
ongoing, and one of four types. `getLabels()` gives every label of the alert, ready for a label-based system.

| type | when |
|------|------|
| `RAISED` | the probe went from normal to an alert |
| `CHANGED` | the level changed |
| `REMINDED` | still ongoing, and the probe has a `reminderInterval` that elapsed |
| `CLEARED` | back to normal; the event carries the last alert, and `clearedManually` when an operator cleared it |

**A message change at the same level is not published**, otherwise a probe whose message carries a count would
publish every second. Opt into `reminderInterval` if an ongoing alert must be repeated.

**Removing a probe clears its alert**, at the next tick, and so does replacing it under the same name, removing the
session from the engine, or the session logging out for a probe evaluated only while logged in. **Stopping the engine does not**: a shutdown is not the problem going away.

## Built-in probes

| probe | level | raised when |
|-------|-------|-------------|
| `session-down` | `CRITICAL` | the session has been logged out inside its session time for longer than `sessionDownAlertDelay` |
| `async-store-queue`, `async-logger-queue` | per threshold | the async store's or logger's queue is deeper than a threshold |
| `async-store-resource`, `async-logger-resource` | `MAJOR` | what the async store or logger writes to is unavailable |
| `messages-store`, `messages-logger` | `WARNING`, then `MAJOR` | a store or logger call failed, then its resource was found down |
| `ha-replication` | `MAJOR` | while leading: a `SYNC` send went out without the follower's ack in time, a message was too long for the replication log, or a follower refuses to replicate the session |

**Every built-in probe is evaluated whether the session is logged in or not**: each watches something a logout does
not fix, or the logout itself.

`session-down` is on every session. An operator logout or being outside session time never raises it, and the delay
restarts after each logon. **`sessionDownAlertDelay` defaults to 10 seconds; zero turns the probe off.**

The async probes are added by `staffix-messages-store-async-impl` and `staffix-messages-logger-async-impl` to every
session using them. The queue probe raises the highest level whose depth is reached in
`QueueSizeThresholdsSettings.thresholds` (by default `WARNING` from 250, `MAJOR` from 1000, `CRITICAL` from 5000),
measured every `evaluationInterval` (10 seconds by default). The resource probe holds until what failed to be written
has been replayed. See the [async store README](../messages-stores/async/async-impl/README.md#queue-monitoring).

`messages-store` and `messages-logger` are on every session that has a store or a logger, whatever its
implementation. A failed call raises `WARNING`, carrying the call and the error, and has the engine check whether the
store's or logger's resource is available. If it is not, the alert turns `MAJOR` and the calls are skipped until it is
back; see [When a store or logger fails](stores-and-loggers.md#when-a-store-or-logger-fails). The alert clears once
calls have kept succeeding for `alertClearDelay` (30 seconds by default). A database outage raises it on every session
using that database, once each however many calls fail, with the same name, so Alertmanager groups them into one
notification. Behind the async store or logger, the async probes report the outage of what it writes to, and these two
report failures of the async queue itself, such as a full disk.

`ha-replication` is on every replicated session. A late ack or a message too long for the replication log keeps the
alert raised for 5 minutes after the last one, since nothing tells that they stopped; a follower refusing the session
(it has no settings for it, or cannot identify its storage) keeps it raised for as long as it refuses. A send with no
follower in sync is left to the node's `ha-followers`, so one outage raises one alert rather than one per session.

Component `ha` has the node's own probes when `ReplicationHaSettings.alertsPublisherInstanceId` names a publisher
(unknown, the engine refuses to start). Each is raised once its condition has lasted 10 seconds, and reports nothing in
a role it does not apply to:

| probe | level | raised when |
|-------|-------|-------------|
| `ha-followers` | `MAJOR`, `WARNING` | leading with no follower connected; a follower connected but not in sync, or catching up by snapshot |
| `ha-leader-link` | `MAJOR`, `WARNING` | following without a connection to the leader; connected but not holding every session in full |
| `ha-leadership` | `CRITICAL` | the node has no role, or is elected leader but cannot prove it, so it runs no session |

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

`loggerNameForScope` picks the logger name per probe scope, so one session's alerts can be routed or silenced
independently, and `useMDC` (on by default) puts the scope's labels in the MDC: a session's group and name under
`fixSessionGroup` and `fixSessionName`, the keys the SLF4J message logger uses.

From Spring Boot, add `staffix-alerts-publisher-slf4j-spring-boot` and declare an instance:

```properties
staffix.alerts-publishers-slf4j.instances.ALERTS.use-mdc=true
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-alerts-publisher-instance-id=ALERTS
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].session-down-alert-delay=30s
```

## The Alertmanager publisher

`staffix-alerts-publisher-alertmanager-impl` posts alerts to Prometheus Alertmanager's `POST /api/v2/alerts`, the
way a Prometheus server does:

```java
AlertmanagerAlertsPublisherSettings.builder()
        .instanceId("alertmanager")
        .alertmanagerUrl(URI.create("http://alertmanager:9093"))
        .staticLabel("env", "prod")
        .build();
```

Each alert carries the labels `alertname` (the probe name), `source` (the engine's instance id), `fixSessionGroup`,
`fixSessionName` and `severity` (`warning`, `major` or `critical`), plus the probe's labels and your static labels (a
probe label wins over a static one of the same name), and the annotations `summary` (the alert message),
`description` (the probe description) and, when the probe has one, `runbook_url`. `startsAt` is when the alert was
raised.

A change is posted at once, and every ongoing alert is posted again each `resendInterval` (one minute by default)
with an `endsAt` four intervals ahead. If the engine stops or crashes, its alerts therefore resolve in Alertmanager
on their own within four intervals, and an alert lost in a failed post comes back at the next resend. A cleared alert
is posted with `endsAt` set to when it cleared, and re-posted for 15 minutes in case that post was lost.

Since Alertmanager tells alerts apart by their labels, a level change resolves the alert at the old severity and
raises one at the new severity. Route on `severity` rather than relying on one alert changing level.

With several Alertmanagers (a highly available cluster), list each URL: every alert is posted to all of them. Posting
runs on its own thread, so an Alertmanager that is slow or down never delays the probes; a failure is logged once
until that Alertmanager answers again.

TLS, a proxy, timeouts and headers such as an `Authorization` go in `httpSenderSettings`, shared by every
Alertmanager. The JDK HTTP client is used by default; `httpSenderFactory` swaps in another `HttpSender`, such as the
OkHttp one.

From Spring Boot, add `staffix-alerts-publisher-alertmanager-spring-boot` and declare an instance:

```properties
staffix.alerts-publishers-alertmanager.instances.ALERTS.urls[0]=https://alertmanager-1:9093
staffix.alerts-publishers-alertmanager.instances.ALERTS.urls[1]=https://alertmanager-2:9093
staffix.alerts-publishers-alertmanager.instances.ALERTS.static-labels.env=prod
staffix.alerts-publishers-alertmanager.instances.ALERTS.headers.Authorization=Basic ...
staffix.alerts-publishers-alertmanager.instances.ALERTS.ssl-bundle=alertmanager
staffix.sessions-settings-stores-memory.instances.SHARED.sessions[0].fix-alerts-publisher-instance-id=ALERTS
```

`resend-interval`, `connect-timeout` and `request-timeout` take a duration, and `http-sender-factory-bean`,
`proxy-selector-bean` and `authenticator-bean` name a bean.

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
