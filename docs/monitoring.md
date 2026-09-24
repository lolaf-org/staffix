# Monitoring

Staffix exports metrics through Micrometer and traces through OpenTelemetry, both as **session plugins**: components
registered on the engine and attached to sessions by instance id, exactly like stores and loggers.

The design constraint is the same one that governs everything else: **observability must not land on the message
path.** The plugin wrappers in this guide are how that is arranged.

---

## The pieces

| module | what it does |
|--------|--------------|
| `staffix-monitoring-micrometer-impl` | Micrometer metrics for sessions |
| `staffix-monitoring-micrometer-otlp-impl` | the above, exported over OTLP |
| `staffix-monitoring-tracing-otlp-impl` | OpenTelemetry tracing, with W3C trace propagation |
| `staffix-monitoring-async-plugin-impl` | wraps a plugin so its callbacks run on their own threads |
| `staffix-monitoring-throttling-plugin-impl` | wraps a plugin so its per-message callbacks are sampled |

None of them is privileged. Micrometer and OpenTelemetry are *implementations of an API in `staffix-api`*, and if
neither suits you, the same API is open to you; see [Writing your own](#writing-your-own).

---

## Metrics

Register the manager on the engine, then name it from the session:

```java
FixEngineBuilder.builder()
        .fixSessionsPlugin(OtlpMicrometerMonitoringManagerSettings.builder()
                .instanceId("monitoring-acceptor")
                .otlpEndpointUrl("http://localhost:4318/v1/metrics")
                .step(Duration.ofSeconds(1))
                .baseTimeUnit(TimeUnit.MICROSECONDS)
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_NAME, "my-service")
                .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_INSTANCE_ID, "acceptor")
                .clockOffsetEnabled(true)
                .rttLatencyEnabled(true)
                .build())
        // …
```

```java
FixSessionSettings.builder()
        .fixSessionId(sessionId)
        .fixSessionType(FixSession.FixSessionType.ACCEPTOR)
        .fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, "monitoring-acceptor")
        .build();
```

`baseTimeUnit(MICROSECONDS)` is worth setting deliberately: the default unit will quantise away most of what is
interesting about a FIX engine measured in microseconds.

`clockOffsetEnabled` and `rttLatencyEnabled` publish the `session.rtt` timer and the `session.clock.offset` gauge
from the session's own continuous line measurement; see [Network monitoring](network-monitoring.md). They need
`rttMeasurementSettings.probeInterval` set on the session to have anything to report.

`startedMeterRegistryConsumer` hands you the registry once it is up, which is where to bind JVM metrics so they land
in the same place as the FIX ones:

```java
.startedMeterRegistryConsumer(registry -> {
    new JvmMemoryMetrics().bindTo(registry);
    new JvmThreadMetrics().bindTo(registry);
    new ProcessorMetrics().bindTo(registry);
})
```

---

## Tracing

```java
.fixSessionsPlugin(OtelTracingSettings.builder()
        .instanceId("tracing-acceptor")
        .w3cTracePropagationEnabled(true)
        .otlpEndpointUrl("http://localhost:4318/v1/traces")
        .resourceAttribute(FixMonitoringConstants.OTLP_SERVICE_NAME, "my-service")
        .build())
```

and on the session, `fixSessionPluginsInstanceId(OtelTracing.class, "tracing-acceptor")`.

**W3C trace propagation carries the trace context in a user-defined FIX field**, so a trace can follow an order
across firms. That has a configuration consequence: the session must be willing to accept fields outside the
dictionary. Set `allowUserDefinedFields(true)` in the validation settings, or the incoming context field is rejected.

---

## Keeping monitoring off the message path

A plugin's per-message callbacks run where the message runs. Two wrappers change that, and both work by wrapping
another plugin's instance id.

Both wrappers are registered **instead of** the plugin they wrap, taking its settings as `delegateSettings`. They are
transparent: the wrapper reports the delegate's `instanceId` and matches the delegate's plugin class, so
`fixSessionPluginsInstanceId(…)` references in your sessions keep working unchanged. Wrapping is an engine-side
decision that session configuration never sees.

### Async: move the work to other threads

```java
.fixSessionsPlugin(AsyncFixSessionsPluginSettings.builder()
        .delegateSettings(OtlpMicrometerMonitoringManagerSettings.builder()
                .instanceId("monitoring-acceptor")
                // …
                .build())
        .queueSize(2048)
        .consumerThreadPoolSize(1)
        .backpressurePolicy(BackpressurePolicy.DROP)
        .build())
```

Fire-and-forget callbacks go onto a per-session ring buffer and are replayed on a shared consumer pool.

| setting | default |
|---------|---------|
| `delegateSettings` | *required* |
| `consumerThreadPoolSize` | `1`, threads draining the queues, assigned round-robin |
| `queueSize` | `1024` per session; **must be a power of two** |
| `backpressurePolicy` | `DROP` |
| `consumerIdleStrategySupplier` / `producerIdleStrategySupplier` | `TimerSlackAwareBackoffIdleStrategy` |

`backpressurePolicy(DROP)` is what keeps this honest: when a queue is full, monitoring data is discarded rather than
the session being slowed down. That is the right trade (**metrics are not worth latency**), but it means dashboards
can lose samples under load, and you want to know that before you spend an afternoon debugging a gap in a chart.
`BLOCK` makes the opposite choice, and applies the producer idle strategy while waiting for a slot.

If the delegate is `Startable`, the wrapper drives its lifecycle: started before the pool and stopped after it, so
buffered callbacks are fully drained into the delegate before it shuts down.

### Throttling: do the work less often

```java
.fixSessionsPlugin(ThrottlingFixSessionsPluginSettings.builder()
        .delegateSettings(OtlpMicrometerMonitoringManagerSettings.builder()
                .instanceId("monitoring-acceptor")
                .build())
        .build())
```

Rate-limits the wrapped plugin's per-message callbacks so the delegate is not overwhelmed at peak throughput. Use it
when you want *representative* numbers rather than *complete* ones.

The two compose: throttle to reduce the volume, then wrap that in async to get what remains off the message path.

---

## Writing your own

Both shipped backends are ordinary session plugins, so replacing one is writing a plugin: the mechanics (settings
class, SPI factory, threading, callbacks) are in [Session plugins](session-plugins.md). What follows is specific to
these two.

### Your own metrics backend

`FixSessionsMonitoringManager`, in `staffix-api` rather than in a monitoring module, is the metrics contract:

```java
public interface FixSessionsMonitoringManager
        extends FixSessionsPlugin<FixSessionsMonitoringContext>, Startable<FixSessionsMonitoringManager> {
}
```

A session plugin that can be started and stopped. Implement it, register it with `fixSessionsPlugin(…)`, and sessions
select it exactly as they select the shipped one, because `matchesPluginClass` answers for the interface:

```java
.fixSessionPluginsInstanceId(FixSessionsMonitoringManager.class, "my-metrics")
```

Swapping backends is then a configuration change, not a change to the sessions.

### Your own tracing

**There is no tracing interface in `staffix-api`**: `OtelTracing` lives in the OTLP module. Write a `FixSessionsPlugin`
and use its own class as the selector key, the way `OtelTracing.class` is used. `onDecoderSetup` is where a
trace-context field gets mapped without the application knowing.

Either way, your plugin composes with the [async and throttling wrappers](#keeping-monitoring-off-the-message-path)
for free.

---

## Somewhere to send it

The repository ships a working stack (Grafana, Prometheus, Loki, Tempo and an OpenTelemetry collector) with
datasources and a FIX dashboard already provisioned:

```bash
cd monitoring/grafana
docker compose up -d
```

Grafana is on `http://localhost:3000` (admin / password), and the collector accepts OTLP on
`http://localhost:4318`. Point the settings above at it and run the
[`advanced-monitoring`](../examples/advanced-monitoring) example, which wires up metrics, logs and traces together.

Message logging can go to the same place with the OTLP message logger, so messages, metrics and traces land in one
collector and correlate. See [Stores and loggers](stores-and-loggers.md#message-loggers).
